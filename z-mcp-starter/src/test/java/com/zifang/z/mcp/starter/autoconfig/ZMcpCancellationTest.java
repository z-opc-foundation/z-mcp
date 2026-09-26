package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.api.exception.McpException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code notifications/cancelled} 跨过真 Tomcat 之后还剩什么.
 *
 * <p>core 那一层是直接调 {@code handler.handle(...)} 的: 它量得到"命中之后做了什么", 量不到
 * "取消通知到底找不找得到那条请求"。中间这三件事只有真 HTTP 能证:
 * <ul>
 *   <li>{@code RequestContext} 必须**每条请求一个新的** —— 控制器要是把它做成字段, 一条迟到的
 *       请求会把别人的 {@code requestKey} 顶掉, 于是取消会打在完全无关的那条上;</li>
 *   <li>寻址靠的是头里的 {@code Mcp-Session-Id}: 在飞表按会话分, 这件事只有两条真连接能验;</li>
 *   <li>回执要是一条**完整**的 JSON-RPC error 落在原来那条连接上, 而不是一个断掉的 socket ——
 *       客户端(zod 严格校验那类)拿到残缺包络会直接抛, 用户看到的就不是"取消了"而是"服务炸了"。</li>
 * </ul>
 *
 * <p>{@code tool-timeout-seconds=30} 不是随手写的: 它必须 {@code > 0}, 否则工具跑在 Tomcat 自己
 * 的线程上、谁都没法中断它, 那第一条用例就会红在"中断没落地" —— 那是这条配置的唯一量具。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpCancellationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.mcp.enabled=true",
                "z.mcp.server-name=cancel-e2e",
                "z.mcp.tool-timeout-seconds=30"
        })
public class ZMcpCancellationTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    private static final String SESSION = "Mcp-Session-Id";
    private static final String PROTOCOL = "MCP-Protocol-Version";
    private static final String VERSION = "2025-06-18";
    private static final String BOTH = "application/json, text/event-stream";

    /** 等"工具真的开跑": 撞到就说明服务端根本没把这条请求交给工具. */
    private static final long PREY_WAIT_MS = 10_000L;
    /** 那条挂住的请求自己的读超时; 等待上限要比它大一档, 否则红消息只说 latch 没等到、不说 socket 先炸. */
    private static final long CALL_WAIT_MS = 20_000L;
    private static final int SOCKET_READ_TIMEOUT_MS = 15_000;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private McpRegistry registry;

    // ------------------------------------------------------------------ 用例

    /**
     * 一条连接上挂着 {@code tools/call}, 另一条连接上同一会话投取消: 中断要真的落到工具线程,
     * 原来那条连接要拿回一条带 -3280 的完整错误包络(并且这个会话之后还能用)。
     */
    @Test
    public void a_cancelled_tools_call_comes_back_as_a_json_rpc_error() throws Exception {
        Prey prey = new Prey();
        registry.registerBuiltin("cancel_e2e_slow", "blocks until released",
                "{\"type\":\"object\",\"properties\":{}}", prey);
        try {
            String id = handshake();
            PendingCall call = new PendingCall(id, "{\"jsonrpc\":\"2.0\",\"id\":\"77\",\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"cancel_e2e_slow\",\"arguments\":{}}}");
            assertTrue("猎物没开跑, 这条用例就没有东西可取消",
                    prey.entered.await(PREY_WAIT_MS, TimeUnit.MILLISECONDS));

            assertEquals("取消通知自己只能是 202", HttpStatus.ACCEPTED.value(),
                    cancel(id, "{\"requestId\":\"77\",\"reason\":\"用户按了停止\"}"));

            String body = call.awaitBody();
            assertTrue("取消掉的请求要给出 JSON-RPC error: " + body,
                    body.contains("\"code\":" + McpException.REQUEST_CANCELLED));
            assertTrue("客户端给的理由要跟着回来: " + body, body.contains("用户按了停止"));
            assertTrue("错误要挂回原来那个请求 id: " + body, body.contains("\"id\":\"77\""));
            assertFalse("不能一边报错一边又给一份结果: " + body, body.contains("\"result\""));
            assertTrue("中断要真的送到工具线程, 而不是等它自己跑完", prey.interrupted.get());

            // 取消不该把这条会话弄脏: 它只是这一条请求的事
            String listing = postSync(id, "{\"jsonrpc\":\"2.0\",\"id\":\"78\",\"method\":\"tools/list\"}");
            assertTrue("取消之后同会话仍能拿到工具清单: " + listing, listing.contains("\"tools\""));
            terminate(id);
        } finally {
            prey.releaseNow();
            registry.unregister("cancel_e2e_slow");
        }
    }

    /**
     * 别的会话拿着**同一个 id** 来取消, 打不动.
     *
     * <p>请求 id 是客户端随手挑的小整数: {@code requestId: 1} 是任何人猜得到的地址。全局一张表的
     * 话, 任何一条已握手的会话都能停掉别人的工具调用 —— 这里故意让攻击者用**同型同值**的 id,
     * 这样挡住它的只可能是会话归属, 而不是 id 类型。
     */
    @Test
    public void another_session_cannot_cancel_this_sessions_call() throws Exception {
        Prey prey = new Prey();
        registry.registerBuiltin("cancel_e2e_victim", "blocks until released",
                "{\"type\":\"object\",\"properties\":{}}", prey);
        String victim = null;
        String attacker = null;
        try {
            victim = handshake();
            attacker = handshake();
            PendingCall call = new PendingCall(victim,
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"cancel_e2e_victim\",\"arguments\":{}}}");
            assertTrue("猎物没开跑", prey.entered.await(PREY_WAIT_MS, TimeUnit.MILLISECONDS));

            assertEquals(HttpStatus.ACCEPTED.value(), cancel(attacker, "{\"requestId\":5}"));
            assertFalse("别的会话的取消不该送到这条工具线程", prey.interrupted.get());

            prey.releaseNow();
            String body = call.awaitBody();
            assertTrue("被别的会话取消过, 这条请求必须照常答完: " + body, body.contains("\"result\""));
            assertTrue("答完的内容要还在: " + body, body.contains("late"));
            assertTrue("它一次都没被打断", prey.finishedWithoutInterrupt);
        } finally {
            prey.releaseNow();
            registry.unregister("cancel_e2e_victim");
            if (victim != null) terminate(victim);
            if (attacker != null) terminate(attacker);
        }
    }

    /**
     * 工具**不理中断**、照样返回了一个值: 客户端仍然只能看到 -3280, 绝不能看到那份结果。
     *
     * <p>这条钉的是 {@code CancellationException} 那一分支。生产上大量工具体卡在 socket 读上,
     * 中断被吞掉、然后返回一个已经过期的答案 —— 把它当成功发回去, 就是让 LLM 拿一份客户端
     * 已经声明不要的东西继续往下走。少了那个 catch, {@code CancellationException} 会掉进
     * 通用分支、变成一条 {@code isError}, 客户端于是看到"工具失败了"而不是"我取消掉了它"。
     */
    @Test
    public void a_tool_that_ignores_the_interrupt_still_reports_cancelled() throws Exception {
        StubbornPrey prey = new StubbornPrey();
        registry.registerBuiltin("cancel_e2e_stubborn", "swallows interrupts",
                "{\"type\":\"object\",\"properties\":{}}", prey);
        try {
            String id = handshake();
            PendingCall call = new PendingCall(id,
                    "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"cancel_e2e_stubborn\",\"arguments\":{}}}");
            assertTrue("猎物没开跑", prey.entered.await(PREY_WAIT_MS, TimeUnit.MILLISECONDS));

            assertEquals(HttpStatus.ACCEPTED.value(), cancel(id, "{\"requestId\":6}"));
            assertTrue("中断该发出去过(工具体把它吞了)",
                    prey.interrupted.await(PREY_WAIT_MS, TimeUnit.MILLISECONDS));
            prey.releaseNow();

            String body = call.awaitBody();
            assertTrue("被取消的这一条只能以取消收口: " + body,
                    body.contains("\"code\":" + McpException.REQUEST_CANCELLED));
            assertFalse("工具体那份已经没人要的答案不得当成结果发回去: " + body,
                    body.contains("stale answer"));
            terminate(id);
        } finally {
            prey.releaseNow();
            registry.unregister("cancel_e2e_stubborn");
        }
    }

    // ------------------------------------------------------------------ 量具

    /** 一个真的会挂住的工具体: 它同时是"猎物在飞"的证据和"中断落地"的探针. */
    private static final class Prey implements McpRegistry.ToolExecutor {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        volatile boolean finishedWithoutInterrupt;

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            entered.countDown();
            try {
                if (!release.await(CALL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("工具体等放行等超时了");
                }
                finishedWithoutInterrupt = true;
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return "late";
        }

        void releaseNow() { release.countDown(); }
    }

    /** 吞掉中断、坚持产出一个值的工具体 —— 生产上"卡住但不错"的那种. */
    private static final class StubbornPrey implements McpRegistry.ToolExecutor {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AtomicBoolean running = new AtomicBoolean(true);

        @Override
        public Object execute(Map<String, Object> args) {
            entered.countDown();
            long until = System.currentTimeMillis() + CALL_WAIT_MS;
            while (running.get() && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    running.set(false);                     // 它"不理中断", 但也不该空转二十秒
                }
            }
            return "stale answer nobody asked for";
        }

        void releaseNow() { running.set(false); }
    }

    /** 一条**还在飞**的请求: 自己的连接、自己的线程, 答完之前读不到任何东西. */
    private final class PendingCall {
        private final HttpURLConnection connection;
        private final CountDownLatch done = new CountDownLatch(1);
        private final String json;
        private volatile int status = -1;
        private volatile String body;
        private volatile Throwable failure;

        PendingCall(String sessionId, String json) throws IOException {
            this.json = json;
            connection = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(SOCKET_READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", BOTH);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty(SESSION, sessionId);
            connection.setRequestProperty(PROTOCOL, VERSION);
            connection.setDoOutput(true);
            Thread runner = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        OutputStream out = connection.getOutputStream();
                        out.write(json.getBytes("UTF-8"));
                        out.flush();
                        status = connection.getResponseCode();
                        body = drain(connection);
                    } catch (Throwable t) {
                        failure = t;
                    } finally {
                        done.countDown();
                    }
                }
            }, "cancel-e2e-call");
            runner.setDaemon(true);
            runner.start();
        }

        String awaitBody() {
            try {
                assertTrue("那条挂住的请求在 " + CALL_WAIT_MS / 1000L + " 秒内没有收回执"
                        + " (取消没有真的让它在 socket 上收口), status=" + status,
                        done.await(CALL_WAIT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertNull("那条请求的连接直接失败了, status=" + status + ": " + failure, failure);
            assertEquals("取消后的回执仍要是一个正常的 HTTP 202/200 应答: " + body,
                    HttpStatus.OK.value(), status);
            assertTrue("回执不能是空体: status=" + status, body != null && !body.isEmpty());
            return body;
        }
    }

    private static String drain(HttpURLConnection connection) throws IOException {
        InputStream in = connection.getErrorStream() != null
                ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int read;
        while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    /** initialize + notifications/initialized; 协议要求这一步之后才允许用这条会话. */
    private String handshake() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"" + VERSION + "\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"cancel-e2e\",\"version\":\"1\"}}}", headers),
                String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        String id = res.getHeaders().getFirst(SESSION);
        assertTrue("initialize 必须签发会话 id, 否则取消没有可寻址的对象", id != null && !id.isEmpty());

        HttpHeaders ack = new HttpHeaders();
        ack.set("Accept", BOTH);
        ack.set(SESSION, id);
        ack.set(PROTOCOL, VERSION);
        ResponseEntity<String> notified = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", ack),
                String.class);
        assertEquals(notified.getBody(), HttpStatus.ACCEPTED, notified.getStatusCode());
        return id;
    }

    /** 在已经握手过的会话上发一条请求, 拿回响应体原文. */
    private String postSync(String sessionId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, VERSION);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        return res.getBody();
    }

    /** 投一条取消通知; 协议: notification 只有 202 这一个出口. */
    private int cancel(String sessionId, String paramsJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, VERSION);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                        + "\"params\":" + paramsJson + "}", headers), String.class);
        assertEquals(res.getBody(), HttpStatus.ACCEPTED, res.getStatusCode());
        return HttpStatus.ACCEPTED.value();
    }

    private void terminate(String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, VERSION);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp",
                HttpMethod.DELETE, new HttpEntity<String>(headers), String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
    }
}
