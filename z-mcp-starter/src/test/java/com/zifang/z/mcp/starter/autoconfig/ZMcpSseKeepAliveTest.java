package com.zifang.z.mcp.starter.autoconfig;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zifang.z.mcp.core.controller.JsonRpcController;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.LoggerFactory;
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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 心跳注释帧在**真 socket** 上的样子.
 *
 * <p>单元层那几 {@code McpSessionStreamBufferTest} 是直接调 {@code store.tick()} 的 —— 那验的是
 * "心跳这件事做对了没有", 验不到"有没有人真的在按周期敲这一次 tick": 调度器建不起来、
 * 开流时没去叫它、配置没接上, 单元层全都还是绿的, 而生产上看到的症状(Fly.io 75 秒、
 * Cloudflare 100 秒的空闲超时把安静的 MCP 流掐掉)会一模一样地回来。
 * 所以这一类把 {@code keep-alive-seconds} 配成 1, 然后只做一件事: 什么通知都不发,
 * 光等着线上自己掉下来一帧。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpSseKeepAliveTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.mcp.enabled=true",
                "z.mcp.server-name=keepalive-e2e",
                "z.mcp.sse.timeout-ms=20000",
                // 配成 1 秒而不是沿用默认的 30: 这条用例要的是"等得到", 30 秒就超出合理用例时长了
                "z.mcp.sse.keep-alive-seconds=1"
        })
public class ZMcpSseKeepAliveTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    private static final String SESSION = "Mcp-Session-Id";
    private static final String PROTOCOL = "MCP-Protocol-Version";
    private static final String VERSION = "2025-06-18";
    private static final String EVENT_STREAM = "text/event-stream";
    private static final String BOTH = "application/json, text/event-stream";

    /** 一条安静流上等到一帧心跳的上限: 周期 1 秒, 留出调度抖动和连接建立的余量. */
    private static final long HEARTBEAT_WAIT_MS = 12_000L;

    /**
     * 服务端在这几条流上做过的决定.
     *
     * <p>为什么要在判红时把它打出来: CI 上出现过一次 {@code java.io.IOException: Premature EOF}
     * (jdk17 runner, 2.006 s, 红在续传那条流的第一次 readLine), 本机 8 遍 0 复现, 而那条红消息里
     * 只有客户端的症状 —— 服务端是"摘掉了一条流"还是"这条会话压根没找到", 当时没有任何线索。
     * 现在只要服务端说过话, 它就跟着红消息一起出来。
     */
    private static final ListAppender<ILoggingEvent> SERVER_SIDE = new ListAppender<ILoggingEvent>();

    @Before public void watchServerSideDecisions() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        SERVER_SIDE.start();
        ctx.getLogger(McpSessionStore.class.getName()).addAppender(SERVER_SIDE);
        ctx.getLogger(JsonRpcController.class.getName()).addAppender(SERVER_SIDE);
    }

    /** 摘干净: 下一个用例若也挂一遍, 不该读到本用例留下的行; 别处的用例也不该被这个 appender 拖累. */
    @After public void stopWatchingServerSideDecisions() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(McpSessionStore.class.getName()).detachAppender(SERVER_SIDE);
        ctx.getLogger(JsonRpcController.class.getName()).detachAppender(SERVER_SIDE);
        SERVER_SIDE.stop();
        SERVER_SIDE.list.clear();
    }

    private static String serverSide() {
        StringBuilder out = new StringBuilder(" [服务端同期日志: ");
        for (ILoggingEvent e : SERVER_SIDE.list) {
            out.append(e.getLevel()).append(' ').append(e.getFormattedMessage()).append(" | ");
        }
        return out.append(']').toString();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private McpRegistry registry;

    /**
     * 无人敲 {@code tick()}: 只有"开了流 → 调度器自己在跑"才会让这个帧出现。
     * 帧的形状也要钉住 —— 它必须是**注释**(:开头), 因为按 SSE 规范客户端对注释行是忽略的;
     * 一旦它写成 {@code event:}+{@code data:}, 客户端就会把心跳当成一条内容为空的通知消息。
     */
    @Test
    public void a_quiet_stream_keeps_talking_on_its_own() throws Exception {
        String id = handshake();
        HttpURLConnection stream = open(EVENT_STREAM, id);
        try {
            BufferedReader in = reader(stream);
            long prime = eventId(frame(in));

            String beat = frame(in);
            assertTrue("安静流上该掉下心跳注释帧: " + show(beat), beat.contains("keep-alive"));
            assertTrue("心跳必须是注释帧, 不能是一条消息: " + show(beat), beat.trim().startsWith(":"));
            assertFalse("心跳不占事件序号: " + show(beat), beat.contains("id:"));
            assertFalse("心跳不带 data: " + show(beat), beat.contains("data:"));

            registry.registerBuiltin("keepalive_probe", "moves the catalogue", "{}", args -> "x");
            try {
                String pushed = frame(in);
                assertEquals("心跳没花掉序号, 真实通知该紧接 priming: " + show(pushed),
                        prime + 1L, eventId(pushed));
                assertTrue(show(pushed), pushed.contains("notifications/tools/list_changed"));
            } finally {
                registry.unregister("keepalive_probe");
            }
        } finally {
            stream.disconnect();
            terminate(id);
        }
    }

    /**
     * 客户端存下来的位置, 在心跳飞过几十轮之后仍然续得上.
     *
     * <p>这才是心跳占号时真正会炸的场景: 一条流被推到很高的 id, 客户端拿着那个 id 回来, 而中间
     * 那段序号在缓冲里什么都没有 —— 服务端只能给 400, 客户端于是永远续不上。这里等两拍心跳
     * 再断流, 续传时补出来的必须还是那条真实通知, 且 id 紧接客户端已知的位置。
     */
    @Test
    public void heartbeats_do_not_move_the_position_a_client_resumes_from() throws Exception {
        String id = handshake();
        HttpURLConnection first = open(EVENT_STREAM, id);
        long seen;
        try {
            BufferedReader in = reader(first);
            seen = eventId(frame(in));
            for (int i = 0; i < 2; i++) frame(in);   // 两拍心跳, 别的一概不发
        } finally {
            first.disconnect();
        }
        registry.registerBuiltin("keepalive_after_drop", "moves the catalogue", "{}", args -> "x");
        try {
            HttpURLConnection resumed = open(EVENT_STREAM, id);
            resumed.setRequestProperty("Last-Event-ID", String.valueOf(seen));
            try {
                String missed = frame(reader(resumed));
                assertTrue("续传补出来的该是断流期间那条真实通知: " + show(missed),
                        missed.contains("notifications/tools/list_changed"));
                assertEquals("补发的 id 接在客户端已知位置之后, 中间没被心跳占掉: " + show(missed),
                        seen + 1L, eventId(missed));
            } finally {
                resumed.disconnect();
            }
        } finally {
            registry.unregister("keepalive_after_drop");
            terminate(id);
        }
    }

    /**
     * 心跳救不活一条已经没人读的流: 写失败就得把它摘掉, 否则调度器每拍都要撞一次这个墙,
     * 一条被浏览器关掉的标签页能把所有会话的心跳一起拖慢。
     * 单元层用 {@code Boom} 量过同一件事, 这里量的是真 socket 断开之后 Tomcat 那边给出的是
     * 不是同一种失败(异步写要等到真的 flush 才报错)。
     */
    @Test
    public void a_client_that_hung_up_stops_being_written_to() throws Exception {
        String doomed = handshake();
        String kept = handshake();
        HttpURLConnection dead = open(EVENT_STREAM, doomed);
        HttpURLConnection alive = open(EVENT_STREAM, kept);
        try {
            BufferedReader in = reader(alive);
            frame(in);                                    // priming
            reader(dead);                                 // 让这条流真的挂上
            dead.disconnect();                            // 客户端跑了

            // 两拍, 不是一拍: 一次写失败会打断那一轮心跳, 而 scheduleWithFixedDelay 在任务
            // 抛出时是**永久**停掉这个任务的 —— 只等一帧的话, "整条心跳线程被打死了"看起来和正常一样。
            for (int i = 0; i < 2; i++) {
                String beat = frame(in);                  // 挂着的那条还在收心跳
                assertTrue("第 " + (i + 1) + " 拍心跳该到: " + show(beat), beat.contains("keep-alive"));
            }
        } finally {
            alive.disconnect();
            terminate(doomed);
            terminate(kept);
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
                        + "\"clientInfo\":{\"name\":\"keepalive-e2e\",\"version\":\"1\"}}}", headers),
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

    private void terminate(String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, VERSION);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp",
                HttpMethod.DELETE, new HttpEntity<String>(headers), String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
    }

    private HttpURLConnection open(String accept, String sessionId) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout((int) HEARTBEAT_WAIT_MS);
        connection.setRequestProperty("Accept", accept);
        if (sessionId != null) connection.setRequestProperty(SESSION, sessionId);
        return connection;
    }

    private static BufferedReader reader(HttpURLConnection stream) throws IOException {
        assertEquals(HttpStatus.OK.value(), stream.getResponseCode());
        assertTrue(stream.getContentType(), stream.getContentType().toLowerCase().contains(EVENT_STREAM));
        return new BufferedReader(new InputStreamReader(stream.getInputStream(), "UTF-8"));
    }

    private static long eventId(String frame) {
        Long id = null;
        for (String line : frame.split("\n")) {
            if (line.startsWith("id:")) id = Long.valueOf(line.substring(3).trim());
        }
        assertTrue("帧里没有 event id: " + show(frame), id != null);
        return id.longValue();
    }

    /**
     * 读到下一个空行为止的一整帧. 等待上限就是 socket 的 read timeout(见 {@code open()}),
     * 心跳周期配成 1 秒, 所以撞满它意味着这条流真的不会再自己掉帧了。
     *
     * <p>三条出口一律附上 {@link #serverSide()}: "超时没帧"、"流提前结束"与"读的时候抛 IOException"
     * 在服务端分别是"没人敲 tick"、"被摘掉/被 complete"与"响应被收尾", 而客户端症状都是同一行红。
     */
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
            fail(HEARTBEAT_WAIT_MS / 1000L + " 秒内流上没有帧(已读到: " + frame + ")" + serverSide());
        } catch (IOException e) {
            // "Premature EOF" 走的正是这一支: HttpURLConnection 看到服务端把 chunked 响应收尾了.
            // 不在这里吞掉它 —— 只是把服务端当时说过的话一起交出去.
            fail(e + " —— 读到: " + frame + serverSide());
        }
        fail("流在帧完整之前就结束了(已读到: " + frame + ")" + serverSide());
        return frame.toString();
    }

    private static String show(String frame) {
        return frame.replace("\r", "").replace("\n", " <LF> ");
    }
}
