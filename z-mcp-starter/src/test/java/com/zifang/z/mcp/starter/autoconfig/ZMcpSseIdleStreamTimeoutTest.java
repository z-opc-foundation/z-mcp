package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.core.session.McpSessionStore;
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
import static org.junit.Assert.fail;

/**
 * 一条挂着不动的流被容器按 {@code sse.timeout-ms} 收走时的样子.
 *
 * <p>这是五个会结束 SSE 流的出口里最后一个此前不留字的: 写出失败、心跳写出失败、会话收流、
 * 开流 500 四处都已经各有一行日志, 而空闲超时这一处偏偏是生产上最常见的一种(笔记本休眠、
 * 客户端挂着长连接不发请求、反代把上游的连接池按住)。它沉默, 排障的人就只能看到"流结束了",
 * 与"中间层掐了"是同一种症状。
 *
 * <p>为什么要一个独立的上下文: 超时与心跳都是**开流时**从配置里取的, 沿用别的类那套
 * {@code timeout-ms=15000 / keep-alive-seconds=1~30} 就永远撞不到空闲超时这条路。
 *
 * <p>这条用例的两半各自的牙口是量出来的(不是推出来的):
 * <ul>
 *   <li>摘掉那行 {@code log.warn} ⇒ 红在第一半; 把时长换成另一个配置项(心跳秒数)⇒ 同样红,
 *       所以断言里连 {@code 1200} 这个数字一起要。</li>
 *   <li>第二半(会话不该还挂着死流)只有把 {@code onTimeout} 与 {@code onCompletion} 里的两次
 *       摘除<b>一起</b>摘掉才红; 单摘任何一处都是等价变异(两处互为冗余)。这不是断言没牙,
 *       是这一行代码有两道保险 —— 所以别因为"摘掉它测试还是绿"就把它当死代码删了。</li>
 * </ul>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpSseIdleStreamTimeoutTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.mcp.enabled=true",
                "z.mcp.server-name=idle-timeout-e2e",
                // 1.2 秒就收: 这条用例要的就是"被容器收走"那一路
                "z.mcp.sse.timeout-ms=1200",
                // 心跳必须关掉: 开着它会不停刷新这条流, 空闲超时永远不会到
                "z.mcp.sse.keep-alive-seconds=0"
        })
public class ZMcpSseIdleStreamTimeoutTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    private static final String SESSION = "Mcp-Session-Id";
    private static final String PROTOCOL = "MCP-Protocol-Version";
    private static final String VERSION = "2025-06-18";
    private static final String EVENT_STREAM = "text/event-stream";
    private static final String BOTH = "application/json, text/event-stream";

    /** 客户端读超时: 明显大于 1.2 秒的空闲超时, 这样"到点没被收走"会读成读超时而不是 EOF. */
    private static final int CLIENT_READ_TIMEOUT_MS = 5_000;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private McpSessionStore sessions;

    @Before
    public void watchServerSideDecisions() {
        ServerSideLogCapture.start();
    }

    @After
    public void stopWatchingServerSideDecisions() {
        ServerSideLogCapture.stop();
    }

    /**
     * 空闲超时这一路要同时兑现两件事: 服务端**说出来**(否则日志里归不到因),
     * 以及把那条已死的 emitter 从会话上**摘干净**(否则会话会一直挂着它)。
     */
    @Test
    public void an_idle_stream_the_container_reclaims_says_so() throws Exception {
        String id = handshake();
        HttpURLConnection stream = open(id);
        BufferedReader in = reader(stream);
        try {
            // 先确认这条流真的活过。这里只读"有没有一行", 不去挑帧的种类: priming 本身
            // 就是一行注释帧(": z-mcp stream open ..."), 要是不把注释行算数, 这条流明明活着
            // 却会被读成"压根没活过"。
            String opened;
            try {
                opened = in.readLine();
            } catch (SocketTimeoutException neverOpened) {
                fail("开完流 5 秒连一行都没有: " + ServerSideLogCapture.rendered());
                return;
            }
            assertNotNull("开完流连一行都没有, 这条流就没活过, 下面谈不到\"被收走\": "
                    + ServerSideLogCapture.rendered(), opened);

            // 之后一个字都不发, 一直读到这条流结束为止
            try {
                while (in.readLine() != null) {
                    // 读到结束; 结束本身就是要观察的事件
                }
            } catch (SocketTimeoutException stillOpen) {
                fail("5 秒过去这条流还没被收走(配置是 1.2 秒空闲超时、心跳已关): "
                        + "要么 onTimeout 没跑, 要么它没把流闭合 —— 这一路的日志因此没被量到 "
                        + ServerSideLogCapture.rendered());
            } catch (IOException endedAbruptly) {
                // 流是被收走了, 只是收得不体面(没写出终止块)。这正是要留线索的场合,
                // 到底是谁收的, 交给下面那两条断言去问服务端。
            }

            String said = ServerSideLogCapture.lineContaining(id, "closed", "stream",
                    "ms without data", "1200");
            assertNotNull("空闲超时这一路服务端要留下一行(同时说到会话、关闭、流、时长,"
                    + "以及配置里那个 1200): " + ServerSideLogCapture.rendered(), said);

            McpSessionStore.McpSession session = sessions.find(id);
            assertNotNull("会话本身不该在这之前就被清掉, 否则这行日志归因不到任何会话上", session);
            assertEquals("说完那句话之后, 这条会话不该还挂着那条已被收走的流: " + said,
                    0, session.emitterCount());
        } finally {
            stream.disconnect();
        }
    }

    private String handshake() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"" + VERSION + "\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"idle-timeout-e2e\",\"version\":\"1\"}}}", headers),
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

    private HttpURLConnection open(String sessionId) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(CLIENT_READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", EVENT_STREAM);
        connection.setRequestProperty(SESSION, sessionId);
        connection.setRequestProperty(PROTOCOL, VERSION);
        return connection;
    }

    private static BufferedReader reader(HttpURLConnection stream) throws IOException {
        assertEquals(HttpStatus.OK.value(), stream.getResponseCode());
        assertTrue(stream.getContentType(),
                stream.getContentType().toLowerCase().contains(EVENT_STREAM));
        return new BufferedReader(new InputStreamReader(stream.getInputStream(), "UTF-8"));
    }
}
