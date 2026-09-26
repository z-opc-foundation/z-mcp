package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.api.dto.McpResourceDto;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.junit.After;
import org.junit.Before;
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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 本地复现用的压力尺(默认 5 轮, 靠 {@code -Dz.mcp.sse.abandon.resume.rounds=300} 拉长):
 * 把 CI 上那条 {@code Premature EOF} 的形状原样重复, 中间一步都不等。
 *
 * <p>形状: 开一条流 → 读一帧 → 直接 disconnect(服务端还不知道对端走了) → 制造一次广播
 * (这一次写出会失败, 于是我们把那条流摘掉并 complete) → 立刻按 Last-Event-ID 回来续传 →
 * 读补发帧。红的那一次, 客户端只读到 {@code id:3} 就没下文了 —— 也就是说补发那一帧
 * 在**帧内**被切开, 而我们五个出口一个都没说话。
 *
 * <p><b>这台尺断言的是"事件不许丢", 不是"流不许被切开"。</b> 后者在 CI 上每跑必现(见下面的
 * 读数), 而它发生在 Spring 把一帧拆成 {@code id:}/{@code data:} 多个写出片段之后、字节落地之前 ——
 * 不在我们任何一个出口里, 目前修不了; 拿它当门禁只会让 main 常红、把别的回归埋掉。所以切开率
 * 打印出来记账({@code [known-open]}), 而硬断言落在"切开之后按最后一个完整帧续传, 那条事件必须
 * 还在"这一半 —— 那一半是我们承诺的, 而且下面 {@code a_stream_cut_off_in_the_middle_of_a_frame_
 * can_still_be_resumed} 每轮都确定性地走一遍(所以循环里的重试分支不是只在 CI 才走的死代码)。
 *
 * <p>已量的读数: 这台尺进树后 CI 连着两遍都在 5 轮里复现 —— run {@code 36246012762} jdk8
 * 1/5 轮切开、run {@code 36246808027} jdk8 1/5 且 jdk17 2/5(两次都在"补发那一帧"上)。
 * 本机把形状拉满 {@code -Dz.mcp.sse.abandon.resume.rounds=2000}(约 5 ms/轮, 10.3 秒跑完)
 * ⇒ **0 次切开**。两条顺势被证伪的猜测: ① "裸 {@code HttpURLConnection} 把弃掉的那条 socket
 * 交给了下一次请求" —— 一支只读探针在 8 与 17 上各 20 轮, 服务端数到的连接数与请求数都是
 * 40/40, 没有复用; ② "换成 {@code completeWithError(ex) 收尾能把那条 response 收干净" —— 换过去
 * 之后 CI 的切开率一模一样(1/5 与 2/5), 本机 200 轮两种写法都 0 次切开、0 行 ERROR, 已回退。
 * 也就是说剩下能解释 CI 的只有 runner 的负载与容器状态, 而不是我们闭合那条流的写法。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpSseAbandonResumeStressTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.mcp.enabled=true",
                "z.mcp.server-name=sse-abandon-stress",
                "z.mcp.sse.timeout-ms=15000",
                "z.mcp.sse.keep-alive-seconds=0",
                "z.mcp.sse.buffer-size=64",
                // 这台尺硬断的是"不丢事件", 切开率只是它顺手打印的读数; 两者都不是限流的事,
                // 而一轮四五个请求会先撞上 300/min 的默认值, 所以这里把限流关掉
                "z.mcp.rate-limit.enabled=false"
        })
public class ZMcpSseAbandonResumeStressTest {

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

    @Before
    public void watchServerSideDecisions() {
        ServerSideLogCapture.start();
    }

    @After
    public void stopWatchingServerSideDecisions() {
        ServerSideLogCapture.stop();
    }

    @Test
    public void abandoning_a_stream_then_resuming_never_loses_an_event() throws Exception {
        int rounds = Integer.getInteger("z.mcp.sse.abandon.resume.rounds", 5).intValue();
        StringBuilder tears = new StringBuilder();
        StringBuilder losses = new StringBuilder();
        int torn = 0, lost = 0;
        for (int i = 0; i < rounds; i++) {
            Round r = oneRound(i);
            if (r.torn != null) {
                torn++;
                tears.append("\n  第 ").append(i).append(" 轮: ").append(r.torn);
            }
            if (r.lost != null) {
                // 这一句一度是漏的: 计数器不涨, 下面那句硬断言就永远是 0==0 的空跑
                // (P1 注入实测: 补发把客户端已经收过的那条再补一遍, 循环那支照样全绿)。
                lost++;
                losses.append("\n  第 ").append(i).append(" 轮: ").append(r.lost);
            }
        }
        // 切开率是要看的读数, 不是断言: CI 上它非零, 本机它是 0 —— 两边都得看得见同一个数。
        System.out.println("[known-open] 弃流→立刻续传: rounds=" + rounds + " torn=" + torn
                + " lost=" + lost + (torn == 0 ? "" : tears));
        assertEquals("重复 " + rounds + " 次\"弃流→立刻续传\", 每一次按最后一个完整帧都该把那条事件"
                + "补回来(流被切开不丢事件的承诺) —— 有 " + lost + " 次没兑现" + losses, 0, lost);
    }

    /**
     * 客户端读到**半截帧**就撒手, 再回来续传该拿到什么。
     *
     * <p>这是上面那个循环在 CI 上的客户端形状, 但把"切"做成确定性的: 只 {@code readLine()} 一次
     * 就 disconnect, 于是客户端手里多了一段没头没尾的 {@code id:N}(SseEmitter 把一帧拆成
     * {@code id:}/{@code data:} 多个片段写出去, 所以半截帧是真的会出现的形状)。半截帧不算一帧,
     * 客户端的位置还停在上一条完整帧上 —— 按那个位置续, 那条被切开的事件必须完整地再来一遍。
     */
    @Test
    public void a_stream_cut_off_in_the_middle_of_a_frame_can_still_be_resumed() throws Exception {
        String id = handshake();
        long lastComplete;
        HttpURLConnection first = open(id, null);
        try {
            BufferedReader in = reader(first);
            frame(in);                                                        // priming
            registry.registerBuiltin("cut_probe", "probe", "{}", args -> "x");
            lastComplete = eventId(frame(in));                                // 最后一条完整的帧
            // 下一条: 只让它露出半截就撒手
            registry.registerResource(new McpResourceDto("z-mcp://cut_target",
                    "cut_target", "d", "text/plain", params -> null));
            String half = in.readLine();
            assertNotNull("切开之前总得先读到一行: 什么都没读到就说明这条流压根没活过", half);
            assertTrue("这一段必须是半截帧的开头, 否则这台尺没在量\"切在帧中\": " + half,
                    half.startsWith("id:"));
        } finally {
            first.disconnect();
        }
        try {
            // 走上面那台循环尺同一个续传出口: 一次注入探针要同时红两处, 而不是各自有一份判定。
            Attempt a = resume(id, lastComplete);
            assertTrue("半截帧不算数: 按上一个完整帧续, 那条被切开的 list_changed 要完整回来"
                    + " (期望 id=" + (lastComplete + 1) + ")"
                    + (a.wrong == null ? "" : " | 补错了: " + a.wrong)
                    + (a.detail == null ? "" : " | 没续上: " + a.detail), a.ok);
        } finally {
            registry.unregister("cut_probe");
            registry.unregisterResource("z-mcp://cut_target");
            terminate(id);
        }
    }

    /** 一轮的两种后果分开记: 切开(容器层面, 打印)与丢事件(我们的承诺, 断言). */
    private static final class Round {
        String torn;
        String lost;
    }

    /** 一轮; 切开之后当场按最后一个完整帧再续一次, 三次之内还补不回来才算丢了事件. */
    private Round oneRound(int i) throws Exception {
        Round round = new Round();
        String id = handshake();
        long seen;
        HttpURLConnection first = open(id, null);
        try {
            BufferedReader in = reader(first);
            frame(in);                                        // priming
            registry.registerBuiltin("stress_probe_" + i, "probe", "{}", args -> "x");
            String live = frame(in);                          // 断流之前看得见的这一条
            seen = eventId(live);
        } catch (IOException e) {
            round.lost = "第一条流上就没读全: " + e;
            return round;
        } finally {
            first.disconnect();                               // 弃流: 不告而别
        }
        try {
            // 这次广播会撞上那条已经没人读的流 ⇒ 服务端写出失败 ⇒ 摘掉并 complete
            registry.registerResource(new McpResourceDto("z-mcp://stress_down_" + i,
                    "stress_down", "d", "text/plain", params -> null));
            for (int attempt = 1; attempt <= 3; attempt++) {
                Attempt a = resume(id, seen);
                if (a.ok) return round;
                if (a.wrong != null) {
                    round.lost = "第 " + attempt + " 次续传补错了: " + a.wrong;
                    return round;
                }
                round.torn = "第 " + attempt + " 次续传被切在帧中间: " + a.detail;
            }
            round.lost = "连着 3 次续传都被切开, 那条事件补不回来了" + (round.torn == null ? "" :
                    " | 最后一次: " + round.torn);
            return round;
        } finally {
            registry.unregister("stress_probe_" + i);
            registry.unregisterResource("z-mcp://stress_down_" + i);
            terminate(id);
        }
    }

    /** 一次续传的结果: 要么成了, 要么补错(丢事件), 要么被切开(可重试). */
    private static final class Attempt {
        final boolean ok;
        final String wrong;
        final String detail;

        private Attempt(boolean ok, String wrong, String detail) {
            this.ok = ok;
            this.wrong = wrong;
            this.detail = detail;
        }

        static Attempt done() { return new Attempt(true, null, null); }

        static Attempt wrong(String why) { return new Attempt(false, why, null); }

        static Attempt torn(String detail) { return new Attempt(false, null, detail); }
    }

    private Attempt resume(String id, long seen) {
        HttpURLConnection resumed = null;
        try {
            resumed = open(id, String.valueOf(seen));
            BufferedReader in = reader(resumed);
            String missed = frame(in);
            if (!missed.contains("notifications/resources/list_changed")) {
                return Attempt.wrong("补发的不是该补的那条: " + show(missed));
            }
            if (eventId(missed) != seen + 1) {
                return Attempt.wrong("补发 id 不接着已知位置: 期望 " + (seen + 1)
                        + ", 实得 " + show(missed));
            }
            String prime = frame(in);
            if (!prime.contains("z-mcp stream open")) {
                return Attempt.wrong("补完之后没有本条流自己的 priming 帧: " + show(prime));
            }
            return Attempt.done();
        } catch (IOException e) {
            // 判红消息里必须带着服务端同期说了什么: 这一次切开不属于我们五个出口里的任何一个
            return Attempt.torn(e + " |" + ServerSideLogCapture.rendered());
        } finally {
            if (resumed != null) resumed.disconnect();
        }
    }

    /** 断言消息里的帧原文先压成一行: surefire 在换行处截断, 红消息只剩半截 id. */
    private static String show(String frame) {
        return frame.replace('\n', ' ');
    }

    private HttpURLConnection open(String sessionId, String lastEventId) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", EVENT_STREAM);
        if (sessionId != null) connection.setRequestProperty(SESSION, sessionId);
        if (lastEventId != null) connection.setRequestProperty("Last-Event-ID", lastEventId);
        return connection;
    }

    private static BufferedReader reader(HttpURLConnection stream) throws IOException {
        assertEquals(HttpStatus.OK.value(), stream.getResponseCode());
        assertTrue(stream.getContentType(),
                stream.getContentType().toLowerCase().contains(EVENT_STREAM));
        return new BufferedReader(new InputStreamReader(stream.getInputStream(), "UTF-8"));
    }

    private String handshake() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"" + VERSION + "\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"stress\",\"version\":\"1\"}}}", headers),
                String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        String id = res.getHeaders().getFirst(SESSION);
        assertNotNull("握手没拿到会话 id", id);
        HttpHeaders ack = new HttpHeaders();
        ack.set("Accept", BOTH);
        ack.setContentType(MediaType.APPLICATION_JSON);
        ack.set(SESSION, id);
        ack.set(PROTOCOL, VERSION);
        rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", ack),
                String.class);
        return id;
    }

    private void terminate(String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, VERSION);
        rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.DELETE,
                new HttpEntity<String>(headers), String.class);
    }

    private static long eventId(String frame) {
        Long id = null;
        for (String line : frame.split("\n")) {
            if (line.startsWith("id:")) id = Long.valueOf(line.substring(3).trim());
        }
        assertNotNull("帧里没有 event id: " + frame.replace('\n', ' '));
        return id.longValue();
    }

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
            throw new IOException("8 秒内没有完整一帧(已读到: " + frame + ")", e);
        }
        throw new IOException("流在帧完整之前就结束了(已读到: " + frame + ")");
    }
}
