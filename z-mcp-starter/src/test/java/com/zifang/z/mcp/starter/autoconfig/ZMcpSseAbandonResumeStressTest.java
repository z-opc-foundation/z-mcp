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
 * <p>这条不是门禁, 是取证。已量的两侧读数: CI 连着三遍里两遍红 jdk17; 本机把整条形状拉满
 * {@code -Dz.mcp.sse.abandon.resume.rounds=2000}(约 5 ms/轮, 10.3 秒跑完)⇒ **0 次切开**。
 * 另一条同时被证伪的猜测是"裸 {@code HttpURLConnection} 把弃掉的那条 socket 交给了下一次请求"
 * —— 一支只读探针在 8 与 17 上各 20 轮, 服务端看到的连接数与请求数都是 40/40, 没有复用。
 * 也就是说这台机器上"弃流→立刻续传"这个形状本身是干净的, 剩下能解释 CI 的只有 runner 的负载。
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
                // 这台尺量的是"帧有没有被切开", 不是限流; 一轮四五个请求, 300/min 默认值会先撞墙
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
    public void abandoning_a_stream_then_resuming_never_splits_a_frame() throws Exception {
        int rounds = Integer.getInteger("z.mcp.sse.abandon.resume.rounds", 5).intValue();
        StringBuilder trouble = new StringBuilder();
        int truncated = 0;
        for (int i = 0; i < rounds; i++) {
            String why = oneRound(i);
            if (why != null) {
                truncated++;
                trouble.append("\n  第 ").append(i).append(" 轮: ").append(why);
            }
        }
        assertTrue("重复 " + rounds + " 次\"弃流→立刻续传\"里有 " + truncated + " 次把帧切开了"
                + trouble, truncated == 0);
    }

    /** 一轮; 返回 null 表示这一轮没出事, 否则返回"怎么出的事". */
    private String oneRound(int i) throws Exception {
        String id = handshake();
        long seen;
        HttpURLConnection resumed = null;
        HttpURLConnection first = open(id, null);
        try {
            BufferedReader in = reader(first);
            frame(in);                                        // priming
            registry.registerBuiltin("stress_probe_" + i, "probe", "{}", args -> "x");
            String live = frame(in);                          // 断流之前看得见的这一条
            seen = eventId(live);
        } catch (IOException e) {
            return "第一条流上就没读全: " + e;
        } finally {
            first.disconnect();                               // 弃流: 不告而别
        }
        try {
            // 这次广播会撞上那条已经没人读的流 ⇒ 服务端写出失败 ⇒ 摘掉并 complete
            registry.registerResource(new McpResourceDto("z-mcp://stress_down_" + i,
                    "stress_down", "d", "text/plain", params -> null));
            resumed = open(id, String.valueOf(seen));
            try {
                BufferedReader in = reader(resumed);
                String missed = frame(in);
                if (!missed.contains("notifications/resources/list_changed")) {
                    return "补发的不是该补的那条: " + missed.replace('\n', ' ');
                }
                if (eventId(missed) != seen + 1) {
                    return "补发 id 不接着已知位置: 期望 " + (seen + 1);
                }
                String prime = frame(in);
                if (!prime.contains("z-mcp stream open")) {
                    return "补完之后没有本条流自己的 priming 帧: " + prime.replace('\n', ' ');
                }
                return null;
            } catch (IOException e) {
                return "续传那条流被切在帧中间: " + e + " |" + ServerSideLogCapture.rendered();
            } finally {
                if (resumed != null) resumed.disconnect();
            }
        } finally {
            registry.unregister("stress_probe_" + i);
            registry.unregisterResource("z-mcp://stress_down_" + i);
            terminate(id);
        }
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
