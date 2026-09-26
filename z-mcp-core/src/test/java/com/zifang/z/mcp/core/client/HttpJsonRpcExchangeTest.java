package com.zifang.z.mcp.core.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Streamable HTTP 传输的真网络往返 —— 服务端是 JDK 自带的 {@link HttpServer}, 不是 mock.
 *
 * <p>{@code HttpURLConnection} 有几个只有真网络才能暴露的坑: 4xx 的体在 getErrorStream()
 * 而不是 getInputStream(); 头大小写不敏感; 不设 readTimeout 就等于把线程交给别人。
 */
public class HttpJsonRpcExchangeTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 收尾等待上限: 正常 stop(0) 是微秒级, 只有撞上死锁才会用到它。 */
    private static final long STOP_WAIT_MS = 10_000L;

    private HttpServer server;
    private ExecutorService executor;
    private String endpoint;
    private final List<String> methods = new ArrayList<String>();
    private final List<String> bodies = new ArrayList<String>();
    private final List<Map<String, String>> headers =
            new ArrayList<Map<String, String>>();
    private final Map<String, String> responseHeaders = new LinkedHashMap<String, String>();

    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String responseBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
    private volatile long stallMillis;

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.createContext("/mcp", new com.sun.net.httpserver.HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                methods.add(exchange.getRequestMethod());
                Map<String, String> sent = new LinkedHashMap<String, String>();
                for (String name : exchange.getRequestHeaders().keySet()) {
                    sent.put(name.toLowerCase(), exchange.getRequestHeaders().getFirst(name));
                }
                headers.add(sent);
                bodies.add(read(exchange.getRequestBody()));
                if (stallMillis > 0) {
                    try {
                        Thread.sleep(stallMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                for (Map.Entry<String, String> h : responseHeaders.entrySet()) {
                    exchange.getResponseHeaders().add(h.getKey(), h.getValue());
                }
                byte[] payload = responseBody.getBytes(UTF8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                if (status == 204 || payload.length == 0) {
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    exchange.sendResponseHeaders(status, payload.length);
                    OutputStream out = exchange.getResponseBody();
                    try {
                        out.write(payload);
                    } finally {
                        out.close();
                    }
                }
                exchange.close();
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        responseHeaders.clear();
        responseHeaders.put("Mcp-Session-Id", "sess-from-wire");
    }

    @After
    public void stopServer() throws InterruptedException {
        // JDK 8 的 sun.net.httpserver 收尾有一处实测死锁: ServerImpl.stop(delay) 先做
        // schan.close() (ServerImpl.java:227), 之后才置 finished —— 而那次 close 曾卡在
        // native preClose0 里再也没回来 (本仓一次全量运行撞上过一次: 主线程 RUNNABLE 在
        // preClose0、dispatcher 在 select(), SIGKILL 只能把进程停在 ?E, 整个 fork 永久挂住).
        // 断言此时已经跑完, 没道理让收尾决定 CI 的寿命, 所以清理放守护线程上并给一个上限。
        final HttpServer toStop = server;
        final ExecutorService pool = executor;
        server = null;
        executor = null;
        Thread stopper = new Thread("z-mcp-test-httpserver-stop") {
            @Override public void run() {
                toStop.stop(0);
                pool.shutdownNow();
            }
        };
        stopper.setDaemon(true);
        stopper.start();
        stopper.join(STOP_WAIT_MS);
        if (stopper.isAlive()) {
            // 不判红: 这是 JDK/内核的毛病, 不是被测代码的。留一行可 grep 的痕迹即可。
            System.err.println("WARN z-mcp-test-httpserver-stop: stop(0) 未在 "
                    + STOP_WAIT_MS + "ms 内返回, 放弃等待 (JDK8 sun.net.httpserver 收尾死锁)");
        }
    }

    private HttpJsonRpcExchange exchange(int connectTimeout, int readTimeout) throws IOException {
        return new HttpJsonRpcExchange(endpoint, connectTimeout, readTimeout);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while ((n = in.read(chunk)) >= 0) out.write(chunk, 0, n);
        return new String(out.toByteArray(), UTF8);
    }

    private static Map<String, String> hdr(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    // ------------------------------------------------------------------ 请求侧

    @Test
    public void post_carries_the_body_verbatim_with_the_json_and_sse_accept() throws Exception {
        HttpJsonRpcExchange exchange = exchange(5_000, 5_000);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";

        exchange.post(body, hdr("Authorization", "Bearer tok"));

        assertEquals(1, methods.size());
        assertEquals("POST", methods.get(0));
        assertEquals(body, bodies.get(0));
        Map<String, String> sent = headers.get(0);
        assertEquals("application/json", sent.get("content-type"));
        // 协议要求客户端同时声明两种媒体类型, 只声明 json 的客户端会被 SSE-only 的 server 拒掉
        assertEquals("application/json, text/event-stream", sent.get("accept"));
        assertEquals("Bearer tok", sent.get("authorization"));
        // Content-Length 必须在: 没有它的请求会被某些网关挂起
        assertEquals(String.valueOf(body.getBytes(UTF8).length), sent.get("content-length"));
    }

    @Test
    public void delete_is_a_real_verb_with_the_session_header() throws Exception {
        HttpJsonRpcExchange exchange = exchange(5_000, 5_000);

        exchange.delete(hdr("Mcp-Session-Id", "sess-1"));

        assertEquals("DELETE", methods.get(0));
        assertEquals("", bodies.get(0));
        assertEquals("sess-1", headers.get(0).get("mcp-session-id"));
        assertTrue("DELETE 不该带 Content-Type", !headers.get(0).containsKey("content-type"));
    }

    @Test
    public void the_exchange_can_be_used_as_the_delete_capable_transport() throws Exception {
        JsonRpcExchange asInterface = exchange(5_000, 5_000);
        assertTrue(asInterface instanceof McpRemoteClient.DeleteCapable);
    }

    // ------------------------------------------------------------------ 响应侧

    @Test
    public void response_headers_are_readable_regardless_of_wire_case() throws Exception {
        HttpJsonRpcExchange exchange = exchange(5_000, 5_000);

        JsonRpcExchange.Response res = exchange.post("{}", null);

        assertEquals(200, res.status());
        assertTrue(res.is2xx());
        assertEquals("sess-from-wire", res.header("Mcp-Session-Id"));
        assertEquals("sess-from-wire", res.header("mcp-session-id"));
        assertEquals("sess-from-wire", res.header("MCP-SESSION-ID"));
    }

    @Test
    public void an_error_status_still_exposes_the_body_the_upstream_wrote() throws Exception {
        status = 404;
        contentType = "text/plain";
        responseBody = "session not found";
        HttpJsonRpcExchange exchange = exchange(5_000, 5_000);

        JsonRpcExchange.Response res = exchange.post("{}", hdr("Mcp-Session-Id", "gone"));

        assertEquals(404, res.status());
        assertTrue("404 不能算成功", !res.is2xx());
        assertEquals("session not found", res.body());
        assertTrue(res.contentType(), res.contentType().startsWith("text/plain"));
    }

    @Test
    public void a_202_with_no_body_is_a_success_and_not_a_parse_error() throws Exception {
        status = 202;
        responseBody = "";
        HttpJsonRpcExchange exchange = exchange(5_000, 5_000);

        JsonRpcExchange.Response res = exchange.post("{}", null);

        assertEquals(202, res.status());
        assertTrue(res.is2xx());
        assertEquals("", res.body());
    }

    @Test
    public void an_sse_content_type_reaches_the_protocol_layer_intact() throws Exception {
        contentType = "text/event-stream";
        responseBody = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n";
        HttpJsonRpcExchange exchange = exchange(5_000, 5_000);

        JsonRpcExchange.Response res = exchange.post("{}", null);

        assertNotNull(res.contentType());
        assertTrue(res.contentType(), res.contentType().toLowerCase().contains("text/event-stream"));
        assertTrue(res.body(), res.body().contains("data:"));
    }

    @Test
    public void a_stalled_upstream_hits_the_read_timeout_instead_of_owning_our_thread()
            throws Exception {
        stallMillis = 1_500L;
        HttpJsonRpcExchange exchange = exchange(2_000, 250);
        long start = System.currentTimeMillis();
        try {
            exchange.post("{}", null);
            fail("没有读超时的客户端等于把本机线程交给别人");
        } catch (IOException expected) {
            long cost = System.currentTimeMillis() - start;
            assertTrue("超时太晚: " + cost + "ms", cost < 3_000L);
        } finally {
            stallMillis = 0L;
        }
    }

    @Test
    public void an_endpoint_nobody_listens_on_fails_fast() throws Exception {
        HttpJsonRpcExchange exchange =
                new HttpJsonRpcExchange("http://127.0.0.1:1/mcp", 400, 400);
        long start = System.currentTimeMillis();
        try {
            exchange.post("{}", null);
            fail();
        } catch (IOException expected) {
            assertTrue("连不上却耗了 " + (System.currentTimeMillis() - start) + "ms",
                    System.currentTimeMillis() - start < 5_000L);
        }
    }

    @Test
    public void a_missing_or_blank_endpoint_is_refused_at_construction() {
        for (String bad : new String[]{null, "", "   "}) {
            try {
                new HttpJsonRpcExchange(bad, 100, 100);
                fail("空 endpoint 不能构造: " + bad);
            } catch (IllegalArgumentException expected) {
                assertNull(expected.getCause());
            } catch (IOException e) {
                fail("参数校验不该抛 IOException: " + e);
            }
        }
    }

    @Test
    public void a_malformed_endpoint_is_refused_at_construction() {
        try {
            new HttpJsonRpcExchange("not-a-url", 100, 100);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }
}
