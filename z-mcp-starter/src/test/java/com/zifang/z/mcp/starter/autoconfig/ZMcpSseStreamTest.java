package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.api.dto.McpResourceDto;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 服务端主动流 {@code GET /mcp} 的真 socket 用例.
 *
 * <p>通知的*分发*在 {@code ZMcpListChangedNotificationTest} 里已经钉住了, 但那一层挂的是测试
 * 自己造的 emitter —— 从"广播调用"到"客户端在 HTTP 上收到一帧"之间还隔着 Tomcat 的异步写、
 * chunked 传输, 以及 GET 这一路自己的门禁(它的 MUST 和 POST 那条不一样: 这里只能收流)。
 * 这一段以前没人量过: 整个测试套件里没有一次真的向 {@code GET /mcp} 发过请求,
 * 所以"推送出得去"始终是个没被证伪过的假设。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpSseStreamTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.mcp.enabled=true",
                "z.mcp.server-name=sse-e2e",
                "z.mcp.sse.timeout-ms=15000",
                // 故意配小: 淘汰要在这条用例能推到的小数目上发生, 而不是攒够 256 条才量到
                "z.mcp.sse.buffer-size=4"
        })
public class ZMcpSseStreamTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    private static final String SESSION = "Mcp-Session-Id";
    private static final String PROTOCOL = "MCP-Protocol-Version";
    private static final String VERSION = "2025-06-18";
    private static final String EVENT_STREAM = "text/event-stream";
    private static final String BOTH = "application/json, text/event-stream";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private McpRegistry registry;

    @Test
    public void a_catalog_change_arrives_on_an_open_stream_as_a_real_frame() throws Exception {
        String id = handshake();
        HttpURLConnection stream = open(EVENT_STREAM, id, null);
        try {
            // 这里不能借用 code(): 它会断开连接, 而这条用例要的正是接着往下读
            assertEquals(stream.getHeaderField(SESSION), HttpStatus.OK.value(), stream.getResponseCode());
            assertTrue(stream.getContentType(), stream.getContentType().toLowerCase().contains(EVENT_STREAM));
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(stream.getInputStream(), "UTF-8"));

            String greeting = frame(in);
            assertTrue("开流第一帧是注释帧: " + show(greeting), greeting.contains("z-mcp stream open"));

            registry.registerBuiltin("sse_probe_tool", "probe", "{}", args -> "x");
            try {
                String pushed = frame(in);
                assertTrue("流上要真的落到一帧工具目录变更: " + show(pushed),
                        pushed.contains("notifications/tools/list_changed"));
                assertTrue("通知帧要带 event id, 否则客户端没法去重: " + show(pushed), pushed.contains("id:"));
            } finally {
                registry.unregister("sse_probe_tool");
            }
        } finally {
            stream.disconnect();
            terminate(id);
        }
    }

    /**
     * GET 这一路协议只给了两个出口: 要么 text/event-stream, 要么 405. 所以一个只列
     * application/json 的 GET 拿到的必须是 405 —— 不是 406: 全文没有 406 这条规则
     * (那条 MUST 属于 POST), 而真客户端把 GET 流上的 406 当成"连接坏了", 把 405 当成
     * "这里没有推送流", 后者才是它们能处理的形状.
     */
    @Test
    public void a_get_that_does_not_accept_a_stream_is_405_not_406() throws Exception {
        String id = handshake();
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), code(open("application/json", id, null)));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), code(open("application/json;q=0.9", id, null)));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), code(open("text/html", id, null)));
        try {
            assertEquals("*/* 表示什么都能收, 放行", HttpStatus.OK.value(), code(open("*/*", id, null)));
        } finally {
            terminate(id);   // 这一条真的开成了流, 不 DELETE 就要挂到超时才收
        }
    }

    @Test
    public void a_stream_needs_a_session_the_server_knows() throws Exception {
        String id = handshake();
        assertEquals("GET 也要带 Mcp-Session-Id", HttpStatus.BAD_REQUEST.value(),
                code(open(EVENT_STREAM, null, null)));
        assertEquals(HttpStatus.NOT_FOUND.value(),
                code(open(EVENT_STREAM, "a-session-this-server-never-issued", null)));
        try {
            assertEquals(HttpStatus.OK.value(), code(open(EVENT_STREAM, id, null)));
        } finally {
            terminate(id);
        }
    }

    /**
     * 开流那一帧要带可续传的坐标(协议 2025-11-25 的 SHOULD): 只有注释帧的开流等于让第一次断线
     * ——生产上最常见的那一次——无据可依。顺带钉 retry: 客户端对它是 MUST, 不下发就只能各猜各的。
     */
    @Test
    public void the_opening_frame_primes_the_client_with_a_resumable_position() throws Exception {
        String id = handshake();
        HttpURLConnection stream = open(EVENT_STREAM, id, null);
        long prime;
        try {
            BufferedReader in = reader(stream);
            String greeting = frame(in);
            assertTrue("开流帧要带注释: " + show(greeting), greeting.contains("z-mcp stream open"));
            assertEquals("priming 帧不是一条消息, data 必须空着: " + show(greeting),
                    "", dataOf(greeting));
            assertTrue("要下发 retry, 断线重连的节奏由服务端定: " + show(greeting),
                    greeting.contains("retry:"));
            prime = eventId(greeting);
        } finally {
            stream.disconnect();
        }
        // 拿这个号回来, 服务端认得 —— 这才是"能续"的意思, 而不是回一个 200 让人自己猜
        HttpURLConnection again = open(EVENT_STREAM, id, String.valueOf(prime));
        try {
            assertEquals(HttpStatus.OK.value(), again.getResponseCode());
            assertEquals(prime + 1, eventId(frame(reader(again))));
        } finally {
            again.disconnect();
            terminate(id);
        }
    }

    /**
     * 断流期间落下的通知, 重新挂上来时要补得到. 这条是整套续传机制的存在理由: 生产上
     * SSE 断流是常态(反代空闲超时、发布、休眠), 不补就是静默少事件 —— 客户端界面从此停在旧目录上。
     */
    @Test
    public void a_stream_that_dropped_out_gets_back_what_it_missed() throws Exception {
        String id = handshake();
        HttpURLConnection first = open(EVENT_STREAM, id, null);
        long seen;
        try {
            BufferedReader in = reader(first);
            frame(in);                                   // priming
            register("sse_probe_before_drop");           // 断流之前看到的: 工具类目变更
            String live = frame(in);
            assertTrue("实时帧该是工具目录变更: " + show(live),
                    live.contains("notifications/tools/list_changed"));
            seen = eventId(live);
        } finally {
            first.disconnect();                          // 断流: 之后的通知没有 emitter 可收
        }
        // 断流期间落的这一条挑**另一个类目**: list_changed 的载荷里没有工具名, 只有类目分得开
        // "补的是漏掉那条"和"把看过那条又发了一遍"。unregister 同样是一次目录变更, 所以收尾
        // 必须排在续传之后 —— 否则先进缓冲的是自己的清理动作(这条当时就是这么红的)。
        registry.registerResource(new McpResourceDto("z-mcp://sse_probe_down", "sse_probe_down",
                "d", "text/plain", params -> null));
        try {
            HttpURLConnection resumed = open(EVENT_STREAM, id, String.valueOf(seen));
            try {
                BufferedReader in = reader(resumed);
                String missed = frame(in);
                assertTrue("续传要先补上断流期间漏掉的那条: " + show(missed),
                        missed.contains("notifications/resources/list_changed"));
                assertEquals("补发的 id 要接在客户端已知的位置后面", seen + 1, eventId(missed));
                String greeting = frame(in);
                assertTrue("补完才轮到这条流自己的 priming 帧: " + show(greeting),
                        greeting.contains("z-mcp stream open"));
                assertEquals("id 在流上严格递增, 否则客户端会把自己的位置说小",
                        seen + 2, eventId(greeting));
            } finally {
                resumed.disconnect();
            }
        } finally {
            registry.unregister("sse_probe_before_drop");
            registry.unregisterResource("z-mcp://sse_probe_down");
            terminate(id);
        }
    }

    /** 补不上就得明着拒(400), 而不是给一条看着正常、少了几条事件的流. */
    @Test
    public void a_position_the_buffer_has_already_dropped_is_refused_on_the_wire() throws Exception {
        String id = handshake();
        HttpURLConnection stream = open(EVENT_STREAM, id, null);
        long prime;
        long last = -1L;
        try {
            BufferedReader in = reader(stream);
            prime = eventId(frame(in));
            for (int i = 0; i < 6; i++) {               // 比 buffer-size(4) 多两条
                register("sse_probe_evict_" + i);
                last = eventId(frame(in));
            }
        } finally {
            stream.disconnect();
        }
        try {
            assertTrue("六条通知都该在流上看到, id 一路递增: prime=" + prime + ", last=" + last,
                    last > prime + 5);
            assertEquals("最早那两条已经出缓冲了, 从那里续就得凭空跳过",
                    HttpStatus.BAD_REQUEST.value(), code(open(EVENT_STREAM, id, String.valueOf(prime))));
            assertEquals("倒数第二条还在缓冲里, 这条必须续得上(否则上面那条 400 是闸坏不是淘汰)",
                    HttpStatus.OK.value(), code(open(EVENT_STREAM, id, String.valueOf(last - 1))));
            assertEquals("非数字的位置同样拒", HttpStatus.BAD_REQUEST.value(),
                    code(open(EVENT_STREAM, id, "not-an-id")));
        } finally {
            for (int i = 0; i < 6; i++) registry.unregister("sse_probe_evict_" + i);
            terminate(id);
        }
    }

    /**
     * 上面那条"非数字也拒"其实是**双保险**的: 那个会话的最早事件已经出了缓冲, 就算把
     * {@code not-an-id} 悄悄退成 {@code 0} 也照样补不上, 于是淘汰路径也会给出 400 —— 分不出
     * 是谁给的。这条把猎物收窄到只剩解析那一条路: 缓冲里第一条就是 id=1, 退成 0 会补发成功、
     * 回 200。探针实测: 把 {@code NumberFormatException} 分支改成"退成 0", 只有这条会红。
     */
    @Test
    public void a_non_numeric_position_is_refused_on_a_session_that_could_replay() throws Exception {
        String id = handshake();
        register("sse_probe_parse");                       // 缓冲里的第一条, id 就是 1
        try {
            HttpURLConnection stream = open(EVENT_STREAM, id, "not-a-number");
            try {
                assertEquals("非数字的位置只能按解析失败拒, 不能悄悄退成 0 再补发",
                        HttpStatus.BAD_REQUEST.value(), code(stream));
            } finally {
                stream.disconnect();
            }
        } finally {
            registry.unregister("sse_probe_parse");
            terminate(id);
        }
    }

    /** 没要求续传的流不该收到历史 —— 负向断言的猎物就是同一会话缓冲里那条真实事件. */
    @Test
    public void a_fresh_stream_replays_nothing_even_though_events_were_buffered() throws Exception {
        String id = handshake();
        register("sse_probe_before_open");                // 进缓冲, 但没有流可发
        try {
            HttpURLConnection stream = open(EVENT_STREAM, id, null);
            try {
                String greeting = frame(reader(stream));
                assertTrue("开流第一帧还是注释帧: " + show(greeting), greeting.contains("z-mcp stream open"));
                assertEquals("客户端没要续传, 就不该把历史塞给它: " + show(greeting),
                        "", dataOf(greeting));
            } finally {
                stream.disconnect();
            }
        } finally {
            registry.unregister("sse_probe_before_open");
            terminate(id);
        }
    }

    /**
     * 服务端广告了 {@code logging} 能力, 那么"工具炸了"这件事除了留在 POST 的回执里,
     * 还必须真的落到客户端那条流上 —— 单元层用的是测试自造的 emitter, 这一条要的是
     * 跨过 Tomcat 异步写出之后仍然在的那一帧.
     */
    @Test
    public void a_failing_tool_reaches_the_stream_as_a_log_notification() throws Exception {
        registry.registerBuiltin("sse_probe_boom", "always fails",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> { throw new IllegalStateException("kaboom-e2e"); });
        try {
            String id = handshake();
            HttpURLConnection stream = open(EVENT_STREAM, id, null);
            try {
                assertEquals(HttpStatus.OK.value(), stream.getResponseCode());
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(stream.getInputStream(), "UTF-8"));
                assertTrue(frame(in).contains("z-mcp stream open"));

                String receipt = post(id, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"sse_probe_boom\",\"arguments\":{}}}");
                assertTrue("回执仍要表明执行失败: " + receipt, receipt.contains("\"isError\":true"));

                String pushed = frame(in);
                assertTrue("流上要有一条日志帧: " + show(pushed), pushed.contains("notifications/message"));
                assertTrue(show(pushed), pushed.contains("\"level\":\"warning\""));
                assertTrue(show(pushed), pushed.contains("kaboom-e2e"));
            } finally {
                stream.disconnect();
                terminate(id);
            }
        } finally {
            registry.unregister("sse_probe_boom");
        }
    }

    /**
     * 门槛也要跨过 Tomcat. 这里不等超时: {@code setLevel(error)} 之后再注册一个工具,
     * 那条 list_changed 是一枚**记号** —— 被压住的 warning 若真发了, 就会排在它前面
     * (同一个会话的 emitter 是 FIFO), 所以"下一帧就是 list_changed"即证明没发.
     */
    @Test
    public void the_session_log_level_governs_what_the_stream_carries() throws Exception {
        registry.registerBuiltin("sse_probe_boom", "always fails",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> { throw new IllegalStateException("kaboom-e2e"); });
        try {
            String id = handshake();
            HttpURLConnection stream = open(EVENT_STREAM, id, null);
            try {
                assertEquals(HttpStatus.OK.value(), stream.getResponseCode());
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(stream.getInputStream(), "UTF-8"));
                assertTrue(frame(in).contains("z-mcp stream open"));

                post(id, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"logging/setLevel\","
                        + "\"params\":{\"level\":\"error\"}}");
                post(id, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"sse_probe_boom\",\"arguments\":{}}}");
                registry.registerBuiltin("sse_probe_marker", "moves the catalogue",
                        "{}", args -> "x");

                String next = frame(in);
                assertTrue("记号帧该到, 且被压住的日志不该抢在它前面: " + show(next),
                        next.contains("list_changed"));
                assertFalse("要 error 以上, warning 就不该上流: " + show(next),
                        next.contains("notifications/message"));
            } finally {
                stream.disconnect();
                terminate(id);
            }
        } finally {
            registry.unregister("sse_probe_boom");
            registry.unregister("sse_probe_marker");
        }
    }

    // ------------------------------------------------------------------ 量具

    /** initialize + notifications/initialized; 协议要求这一步之后才允许用这条会话. */
    private String handshake() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        // 不写这行的话 RestTemplate 会给 String 体补一个 text/plain;charset=UTF-8 ⇒ 415
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"" + VERSION + "\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"sse-e2e\",\"version\":\"1\"}}}", headers),
                String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        String id = res.getHeaders().getFirst(SESSION);
        assertTrue("initialize 必须签发会话 id, 否则没有流可开", id != null && !id.isEmpty());

        HttpHeaders ack = new HttpHeaders();
        ack.set("Accept", BOTH);
        ack.setContentType(MediaType.APPLICATION_JSON);
        ack.set(SESSION, id);
        ack.set(PROTOCOL, VERSION);
        ResponseEntity<String> notified = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", ack),
                String.class);
        assertEquals(notified.getBody(), HttpStatus.ACCEPTED, notified.getStatusCode());
        return id;
    }

    /** 在已经握手过的会话上发一条请求, 拿回响应体原文. */
    private String post(String sessionId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, VERSION);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        return res.getBody();
    }

    private void register(String name) {
        registry.registerBuiltin(name, "probe", "{}", args -> "x");
    }

    /** 真开一条流并交出读帧的 reader: 状态码与内容类型在这里钉, 因为这条流之后还要接着读. */
    private static BufferedReader reader(HttpURLConnection stream) throws IOException {
        assertEquals(HttpStatus.OK.value(), stream.getResponseCode());
        assertTrue(stream.getContentType(), stream.getContentType().toLowerCase().contains(EVENT_STREAM));
        return new BufferedReader(new InputStreamReader(stream.getInputStream(), "UTF-8"));
    }

    /** 帧里的 SSE event id; 读不出数字就判红 —— 续传全靠它. */
    private static long eventId(String frame) {
        Long id = null;
        for (String line : frame.split("\n")) {
            if (line.startsWith("id:")) id = Long.valueOf(line.substring(3).trim());
        }
        assertTrue("帧里没有 event id: " + show(frame), id != null);
        return id.longValue();
    }

    /** 一帧里所有 data: 字段拼回来的载荷(priming 帧应当是空串). */
    private static String dataOf(String frame) {
        StringBuilder data = new StringBuilder();
        for (String line : frame.split("\n")) {
            if (!line.startsWith("data:")) continue;
            if (data.length() > 0) data.append('\n');
            data.append(line.substring(5));
        }
        return data.toString();
    }

    /**
     * 用完把会话 DELETE 掉. 不放掉的话服务端的异步请求要挂满 sse.timeout-ms 才收, 而那条被客户端
     * 丢开的连接在这期间会留在 TestRestTemplate 的池子里 —— 下一次握手复用它就会撞到
     * "Unexpected end of file"(实测撞上过一次, 那一轮整类耗时从 4 秒涨到 25 秒)。
     */
    private void terminate(String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, VERSION);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp",
                HttpMethod.DELETE, new HttpEntity<String>(headers), String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
    }

    private HttpURLConnection open(String accept, String sessionId, String lastEventId) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        // 每个用例都显式给 Accept: HttpURLConnection 自己会塞一串默认值(含 */*),
        // 不覆盖的话"客户端到底声明了什么"就说不清, 那条分支也就没被量到.
        connection.setRequestProperty("Accept", accept);
        if (sessionId != null) connection.setRequestProperty(SESSION, sessionId);
        if (lastEventId != null) connection.setRequestProperty("Last-Event-ID", lastEventId);
        return connection;
    }

    /** 只取状态码并收尾(这些用例不打算把流读完). */
    private static int code(HttpURLConnection connection) throws IOException {
        try {
            int status = connection.getResponseCode();
            try {
                InputStream body = connection.getErrorStream() != null
                        ? connection.getErrorStream() : connection.getInputStream();
                body.close();
            } catch (IOException drain) {
                // 4xx 的空响应体可能压根没有流可取; 要量的是状态码, 不是这个
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 把一帧压成一行放进断言消息里: surefire 会在换行处截断消息, 所以多行帧直接拼进消息
     * 只会留下 "id:1" 这半截 —— 而红消息后半段往往还藏着第二个缺陷.
     */
    private static String show(String frame) {
        return frame.replace("\r", "").replace("\n", " <LF> ");
    }

    /** 读到下一个空行为止的一整帧(注释帧以 ':' 开头). */
    private static String frame(BufferedReader in) throws IOException {
        StringBuilder frame = new StringBuilder();
        String line;
        try {
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    if (frame.length() > 0) return frame.toString();
                    continue;
                }
                if (frame.length() > 0) frame.append('\n');
                frame.append(line);
            }
        } catch (SocketTimeoutException e) {
            fail("15 秒内流上没有帧(已读到: " + frame + ")");
        }
        fail("流在帧完整之前就结束了(已读到: " + frame + ")");
        return frame.toString();
    }
}
