package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.session.McpSessionStore;
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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SSE 帧的**写出单元**: 一次 flush 的末尾必须同时是一帧的末尾.
 *
 * <p>动因是 CI 上那条本机复现不出的红({@code ZMcpSseKeepAliveTest
 * .heartbeats_do_not_move_the_position_a_client_resumes_from}, jdk17 runner, {@code Premature EOF})。
 * 每次红, 客户端手里都是恰好 {@code 读到: id:2} —— 半截帧。为什么会有半截: Spring 的
 * {@code SseEmitter.event()} 把一条事件拆成 {@code id:N\ndata:} 前缀 / JSON 体 / 结尾换行 三个写出片段,
 * 每个片段各自 flush 一次, 于是一帧之内有两道缝; 容器在缝里收走连接时, 半截帧就到了客户端手上。
 *
 * <p><b>为什么判据落在 chunk 边界上而不是"内容对不对":</b> HTTP chunk 边界就是服务端的一次 flush,
 * 与 TCP 时序无关。而"半截帧"在 SSE 行协议里根本看不出来 —— 它就是 {@code id:2} 这一行, 合法得很。
 * 所以只有边界这一层看得见这道缝。
 *
 * <p>两棵树上的实测读数(本机 JDK 17; 改之前那份是把 {@code fccde51} 的 {@code McpSessionStore.java}
 * 原字节放进一份副本里跑的, 副本在 {@code ~/.cache/zmcp_prey/tree60_teeth}):
 * <ul>
 *   <li>一条广播事件: 改前 3 个写出单元({@code id:1\ndata:} / JSON 体 / {@code \n\n}) ⇒ 帧内 2 道缝;
 *       改后 1 个。</li>
 *   <li>带 priming 的开流帧: 改前 2 个({@code :z-mcp stream open\nretry:3000\nid:1\ndata:} /
 *       {@code \n\n}) ⇒ 1 道缝; 改后 1 个。那个空载荷片段在网络上占 0 字节(改前也只有 2 片, 不是 3 片),
 *       所以两边的拼接结果逐字相同。</li>
 *   <li>续传的补发: 改前 4 个(补发那帧占 3 个 + 开流帧 1 个), 改后 2 个。</li>
 *   <li>不带 priming 的开流帧: 改前改后都是 1 个 —— 这条就是"这台尺分得出 1 片与 3 片"的对照,
 *       它在被修的代码上红不起来, 却把"恒报 1"这种坏法排除了。</li>
 * </ul>
 *
 * <p>三处阳性对照, 都是"改前也绿"的那一类(它们量的是量具与改法的边界, 不是行为):
 * ① {@link #the_open_frame_of_a_plain_stream_is_one_write};
 * ② {@link #the_chunk_decoder_agrees_with_an_ordinary_reader} —— 原始解码得到的字节与一条普通
 * {@code HttpURLConnection} 在同一会话上读到的字节逐字比一遍, 它红则上面所有计数作废;
 * ③ {@link #a_non_ascii_payload_arrives_byte_for_byte} —— 中文与 emoji 载荷原样落地, 证明这次改动
 * 只换了"几次写出", 没换字符集。
 *
 * <p>它钉的是"帧内没有缝", 不是"流不会被收走": 帧与帧之间照样能切, 那一半由
 * {@code ZMcpSseAbandonResumeStressTest} 断言"切开不许丢事件"。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpSseFrameAtomicityTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 这批切片用例一个字节的 SQL 都不碰。本模块 pom 里 druid-spring-boot-starter /
                // spring-boot-starter-jdbc / mysql-connector-j 都是 provided 作用域,宿主拿不到,
                // 生产上没这个问题;但 provided 在**测试**类路径上,@EnableAutoConfiguration 会去
                // 激活 DruidDataSourceAutoConfigure,而容器里没有任何数据源配置,启动期直接死在
                // "Failed to determine a suitable driver class"。症状是整类用例集体 error
                // (9 个类 52 条),而不是某一条断言红。处置与 z-report 的 EndToEndFlowTest#TestApp
                // 一致(2026-10-04)。⚠ Boot 2.7 的 @SpringBootTest 没有 exclude 属性(javap 查过
                // 2.7.18 的字节码:只有 value/properties/args/classes/webEnvironment),所以只能走属性。
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure",
                "z.mcp.enabled=true",
                "z.mcp.server-name=sse-frame-atomicity",
                "z.mcp.sse.timeout-ms=30000",
                // 心跳关掉: 这台尺数的是"一帧几个写出单元", 多出来的注释帧只会让计数看错行
                "z.mcp.sse.keep-alive-seconds=0",
                "z.mcp.sse.buffer-size=64",
                // 一轮开好几条流、发好几条广播, 会先撞上 300/min 的默认限流
                "z.mcp.rate-limit.enabled=false"
        })
public class ZMcpSseFrameAtomicityTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    private static final String SESSION = "Mcp-Session-Id";
    private static final String PROTOCOL = "MCP-Protocol-Version";
    private static final String OLD_VERSION = "2025-06-18";
    private static final String NEW_VERSION = "2025-11-25";
    private static final String EVENT_STREAM = "text/event-stream";
    private static final String BOTH = "application/json, text/event-stream";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private McpRegistry registry;

    /** 直接往会话的流上写一帧(不经注册表), 为的是把载荷写成任意字节 —— 见那条非 ASCII 对照. */
    @Autowired
    private McpSessionStore store;

    /**
     * 阳性对照(改前改后都该绿): 不带 priming 的开流帧本来就是一次写出。
     *
     * <p>它的作用是给下面那些计数一个"1 片"的量程刻度 —— 如果这台尺把什么都读成 1 片, 这条会绿,
     * 但 {@link #a_broadcast_event_arrives_as_one_write} 在改之前的实测读数是 3 片(读数随副本路径记在类注释),
     * 于是"恒报 1"这一种坏法被排除了。
     */
    @Test
    public void the_open_frame_of_a_plain_stream_is_one_write() throws Exception {
        String id = handshake(OLD_VERSION);
        RawStream stream = open(id, OLD_VERSION, null);
        try {
            List<String> chunks = readChunks(stream);
            assertOneFramePerWrite(chunks);
            assertEquals("开流帧该是一个写出单元: " + flat(chunks), 1, chunks.size());
            String frame = frameAt(chunks, 0);
            assertTrue("开流帧要以注释行开头: " + flat(chunks), frame.startsWith(":"));
            assertTrue("开流帧要含 retry 字段: " + flat(chunks), frame.contains("retry:"));
            assertFalse("这一版拿不到 priming, 帧里不该有 event id: " + flat(chunks),
                    frame.contains("id:"));
        } finally {
            stream.close();
            terminate(id);
        }
    }

    /**
     * 广播事件: 一条通知是一个写出单元。
     *
     * <p>这一条就是 CI 上红的那次客户端手里剩下的东西 —— 当时它只读到第一片 {@code id:2}。改之前
     * 这里量到 3 片({@code id:2\ndata:} / JSON 体 / {@code \n\n}), 也就是说"帧内有两道缝"是**确定的
     * 结构事实**, 不是概率; 间歇的只是连接有没有落在缝上。
     */
    @Test
    public void a_broadcast_event_arrives_as_one_write() throws Exception {
        String id = handshake(OLD_VERSION);
        RawStream stream = open(id, OLD_VERSION, null);
        try {
            readChunks(stream);                                 // 开流帧
            registry.registerBuiltin("atomicity_probe", "moves the catalogue", "{}", args -> "x");
            try {
                List<String> chunks = readChunks(stream);
                assertOneFramePerWrite(chunks);
                assertEquals("一条通知该是一个写出单元(改之前这里是 3 个): " + flat(chunks),
                        1, chunks.size());
                String frame = frameAt(chunks, 0);
                assertTrue("帧要按 id: / data: 的形状排: " + flat(chunks),
                        frame.matches("(?s)^id:\\d+\ndata:.+\n\n$"));
                assertTrue("内容要还是那条 list_changed: " + flat(chunks),
                        frame.contains("notifications/tools/list_changed"));
            } finally {
                registry.unregister("atomicity_probe");
            }
        } finally {
            stream.close();
            terminate(id);
        }
    }

    /**
     * 阳性对照(改前改后都该绿): 换"写出单元数"不许顺手换字节 —— 非 ASCII 载荷要原样到客户端。
     *
     * <p>{@code WholeFrame} 的 mediaType 刻意取 {@code null}, 与 {@code event().data(json)} 交给转换器
     * 的那一枚同值, 于是转换器与字符集都没换。这一条就是那个"没换"的读数: 载荷里同时放了中文和 emoji
     * (前者测多字节序列, 后者测需要代理对的四字节序列 —— 只放中文的话, 半路的 ISO-8859-1 化会伪装成"能过")。
     * 它一旦红, 说明这次改动动了编码, 上面那些 chunks=1 就都是代价换来的。
     */
    @Test
    public void a_non_ascii_payload_arrives_byte_for_byte() throws Exception {
        String id = handshake(OLD_VERSION);
        RawStream stream = open(id, OLD_VERSION, null);
        try {
            readChunks(stream);                                 // 开流帧
            store.find(id).emit("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                    + "\"params\":{\"level\":\"info\",\"logger\":\"探针\",\"data\":\"目录已变更 ✅\"}}");
            // 这里刻意不判"几个写出单元": 它量的是编码有没有被换掉, 那是改前改后都该成立的对照
            String frame = allChunksText(readChunks(stream));
            assertTrue("中文与 emoji 要按 UTF-8 原样落地, 不许变成问号或 \\u 转义: " + flat(frame),
                    frame.contains("\"logger\":\"探针\",\"data\":\"目录已变更 ✅\""));
        } finally {
            stream.close();
            terminate(id);
        }
    }

    /**
     * priming 与开流注释帧同属一个写出单元 —— 这是刻意的, 不是为了少写一次。
     *
     * <p>字节必须与 {@code SseEmitter.event()} 的旧写法逐字相同: 旧写法把注释/retry/id/data 前缀攒在
     * 同一段文本里, 只有结尾换行自成一片(实测旧的是 2 片, 拼起来 {@code :注释\nretry:3000\nid:1\ndata:\n\n}),
     * 拆成两次写出会在中间多出一个空行, 而多出来的空行会把 priming 变成"另一帧"。
     */
    @Test
    public void the_primed_open_frame_is_one_write() throws Exception {
        String id = handshake(NEW_VERSION);
        RawStream stream = open(id, NEW_VERSION, null);
        try {
            List<String> chunks = readChunks(stream);
            assertOneFramePerWrite(chunks);
            assertEquals("开流 + priming 该是一个写出单元: " + flat(chunks), 1, chunks.size());
            String frame = frameAt(chunks, 0);
            assertTrue("要同时带开流注释、retry 与 priming 的空 data: " + flat(chunks),
                    frame.matches("(?s)^:z-mcp stream open\nretry:\\d+\nid:1\ndata:\n\n$"));
        } finally {
            stream.close();
            terminate(id);
        }
    }

    /**
     * 续传的补发: 每条事件自己一个写出单元, 本条流的开流帧紧随其后。
     *
     * <p>这一条刻意不靠"弃流"来制造补发 —— 弃流要等服务端写出失败才知道对端走了, 那是另一件事
     * ( {@code ZMcpSseAbandonResumeStressTest} 在量)。这里用第二条流按 {@code Last-Event-ID} 进来,
     * 补发就是确定的。
     */
    @Test
    public void each_replayed_event_is_its_own_write() throws Exception {
        String id = handshake(OLD_VERSION);
        long known;
        RawStream first = open(id, OLD_VERSION, null);
        try {
            readChunks(first);                                  // 开流帧(不带坐标)
            registry.registerBuiltin("atomicity_replay_old", "p", "{}", args -> "x");
            known = eventId(frameAt(readChunks(first), 0));      // 客户端读到的位置
            registry.registerBuiltin("atomicity_replay_new", "p", "{}", args -> "x");
            readChunks(first);                                  // 这条没读, 留在缓冲里等续传
        } finally {
            first.close();
        }
        // 注销放在续传**之后**: 每 unregister 一次就多一条 list_changed, 放在续传之前会把
        // "该补几条"变成我算不准的数(实测这样多补出 id:3 与 id:4, 于是红的是记账不是写出单元)
        RawStream resumed = open(id, OLD_VERSION, String.valueOf(known));
        try {
            List<String> chunks = readChunks(resumed);
            assertOneFramePerWrite(chunks);
            assertEquals("补发一帧 + 开流一帧, 各占一个写出单元: " + flat(chunks),
                    2, chunks.size());
            String replayed = frameAt(chunks, 0);
            assertTrue("补发的必须是那条没读到的 list_changed: " + flat(chunks),
                    replayed.matches("(?s)^id:\\d+\ndata:.+\n\n$"));
            assertTrue("内容还是那条通知: " + flat(chunks),
                    replayed.contains("notifications/tools/list_changed"));
            assertEquals("补发 id 接在客户端已知位置之后: " + flat(chunks),
                    known + 1L, eventId(replayed));
            assertTrue("补完之后要有本条流自己的开流帧: " + flat(chunks),
                    frameAt(chunks, 1).startsWith(":z-mcp stream open"));
        } finally {
            resumed.close();
            registry.unregister("atomicity_replay_old");
            registry.unregister("atomicity_replay_new");
            terminate(id);
        }
    }

    /**
     * 解码器的自证: 原始 socket 逐片解出来的字节, 与一条普通 {@code HttpURLConnection} 在**同一个
     * 会话**上读到的字节逐字相同。
     *
     * <p>没有这一条, "chunks=1" 完全可能是我把边界读错了 —— 而上面每个判据都建立在边界上。
     * 它比的是同一次广播写给两条流的那份字节, 所以改前改后都该绿(它量的是量具); 它一旦红,
     * 上面那些计数全部作废。
     */
    @Test
    public void the_chunk_decoder_agrees_with_an_ordinary_reader() throws Exception {
        String id = handshake(OLD_VERSION);
        RawStream raw = open(id, OLD_VERSION, null);
        HttpURLConnection plain = openOrdinary(id, OLD_VERSION);
        try {
            InputStream in = plain.getInputStream();
            assertSameBytes("开流帧", readChunksText(raw), readFully(in, lastFrameLength));
            // 三次比对都由"这一次动作"引出, 收尾不再另外补动作 ⇒ 两侧看到的帧序列是同一份
            registry.registerBuiltin("atomicity_pair", "p", "{}", args -> "x");
            assertSameBytes("注册那条广播", readChunksText(raw), readFully(in, lastFrameLength));
            registry.unregister("atomicity_pair");
            assertSameBytes("注销那条广播", readChunksText(raw), readFully(in, lastFrameLength));
        } finally {
            raw.close();
            plain.disconnect();
            terminate(id);
        }
    }

    /** 最近一次 {@link #readChunksText} 拿到的字节数; 只为了把同一份长度交给普通读取侧. */
    private int lastFrameLength;

    // ------------------------------------------------------------------ 量具

    /** 一条原始 socket 上的 chunked 响应: 边界就是服务端的一次 flush. */
    private static final class RawStream {
        final Socket socket;
        final BufferedInputStream in;
        final String contentType;

        RawStream(Socket socket, BufferedInputStream in, String contentType) {
            this.socket = socket;
            this.in = in;
            this.contentType = contentType;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignore) {
                // 探针收尾, 关不掉不算病
            }
        }
    }

    private RawStream open(String sessionId, String version, String lastEventId) throws IOException {
        StringBuilder req = new StringBuilder();
        req.append("GET /mcp HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\n")
                .append("Accept: ").append(EVENT_STREAM).append("\r\n")
                .append(SESSION).append(": ").append(sessionId).append("\r\n")
                .append(PROTOCOL).append(": ").append(version).append("\r\n");
        if (lastEventId != null) req.append("Last-Event-ID: ").append(lastEventId).append("\r\n");
        req.append("\r\n");
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
        socket.getOutputStream().write(req.toString().getBytes("US-ASCII"));
        socket.getOutputStream().flush();
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        socket.setSoTimeout(12_000);
        String status = readAsciiLine(in);
        assertTrue("GET /mcp 没拿到 200: " + status, status.startsWith("HTTP/1.1 200"));
        String line;
        boolean chunked = false;
        String contentType = null;
        while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("transfer-encoding") && lower.contains("chunked")) chunked = true;
            if (lower.startsWith("content-type")) {
                contentType = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        assertTrue("响应不是 chunked 编码 ⇒ chunk 边界量的不是 flush", chunked);
        // 整帧只有一个写出片段, 走的是 ResponseBodyEmitter.send(data, mediaType) 那条按类型选转换器
        // 的路: 片段声明成 text/plain 的话, 换掉的不只是"一帧几片", 还有这条响应的类型。
        assertTrue("开流响应的 Content-Type 不该被写出片段自己的类型带走(实得: " + contentType + ")",
                contentType != null && contentType.toLowerCase().startsWith(EVENT_STREAM));
        return new RawStream(socket, in, contentType);
    }

    /**
     * 读到"这一批到齐了"为止: 第一片最多等 {@code firstMs}, 之后的片等 {@code quietMs} 没等到就收。
     * 两片之间的等待是必要的 —— 一次广播在改之前会连着来三片, 收得太早会把缝当成帧边界。
     */
    private static List<String> readChunks(RawStream stream) throws IOException {
        return readChunks(stream, 6_000, 500);
    }

    private static List<String> readChunks(RawStream stream, int firstMs, int quietMs)
            throws IOException {
        List<String> chunks = new ArrayList<String>();
        while (true) {
            stream.socket.setSoTimeout(chunks.isEmpty() ? firstMs : quietMs);
            String sizeLine;
            try {
                sizeLine = readAsciiLine(stream.in);
            } catch (SocketTimeoutException quiet) {
                assertFalse("一帧都没读到(超时前只攒到: " + flat(chunks) + ")", chunks.isEmpty());
                return chunks;
            }
            if (sizeLine == null) {
                fail("流在帧之前就结束了(已读到: " + flat(chunks) + ")");
                return chunks;
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException notASize) {
                fail("chunk 长度行读不懂: " + notASize + " (行内容: " + sizeLine + ")");
                return chunks;
            }
            if (size == 0) {
                fail("服务端把这条流收尾了(已读到: " + flat(chunks) + ")");
                return chunks;
            }
            byte[] body = new byte[size];
            int got = 0;
            while (got < size) {
                int n = stream.in.read(body, got, size - got);
                if (n < 0) break;
                got += n;
            }
            assertEquals("chunk 声明了 " + size + " 字节却没读到那么多", size, got);
            chunks.add(new String(body, 0, got, "UTF-8"));
            readAsciiLine(stream.in);                           // 片尾的 CRLF
        }
    }

    /** 一帧一帧地收: 拼出文本, 顺手把长度留给普通读取侧. */
    private String readChunksText(RawStream stream) throws IOException {
        String all = allChunksText(readChunks(stream));
        lastFrameLength = all.length();
        return all;
    }

    /**
     * 不变式(两个方向): 每个写出单元**恰好**装着一帧 —— 既不许把一帧切开, 也不许把两帧合进一片.
     *
     * <p>切开那一半: 按 SSE 的行协议一帧以空行收尾, 所以判据是"读到第 i 片为止的累计字节以空行结尾"。
     * 改之前的第一片是 {@code id:2\ndata:} —— 它本身合法得很(就是个半截帧), 只有这个累计前缀看得见它。
     * 合帧那一半是互补的: 一片里再出现一个空行, 说明写出单元与帧的对应关系被反向合并了, 那时
     * "切开就丢两条事件"会取代"切开丢半条", 而 chunks==frames 这条计数也会跟着失真。
     */
    private static void assertOneFramePerWrite(List<String> chunks) {
        List<String> seams = new ArrayList<String>();
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            seen.append(chunk);
            if (!seen.toString().endsWith("\n\n")) {
                seams.add("第 " + i + " 片把一帧切开了(累计前缀不以空行收尾): " + flat(seen.toString()));
            } else if (chunk.indexOf("\n\n") != chunk.length() - 2) {
                seams.add("第 " + i + " 片里不止一帧(片内还有空行): " + flat(chunk));
            }
        }
        // 一次断言把所有缝都报出来: 逐片 assertTrue 会在第一道缝就抛出, 而这条尺要看的是
        // "一帧到底被切成几片" —— 只报第一片等于把 3 片读成 1 道缝(改之前的实测正是 3 片)
        assertTrue("帧内有缝(共 " + seams.size() + " 道) | 全部写出单元: " + flat(chunks)
                + (seams.isEmpty() ? "" : " | " + seams), seams.isEmpty());
    }

    /** 拼出这一批读到的全部字节(不做"一帧一片"的假设) —— 给那些只比字节、不比边界的对照用. */
    private static String allChunksText(List<String> chunks) {
        StringBuilder all = new StringBuilder();
        for (String chunk : chunks) all.append(chunk);
        return all.toString();
    }

    private static String readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int k = in.read(buf, got, n - got);
            if (k < 0) fail("普通读取在第 " + got + "/" + n + " 字节上遇到 EOF");
            got += k;
        }
        return new String(buf, 0, got, "UTF-8");
    }

    /**
     * 第 {@code index} 帧的字节: 从"前面各帧之和"之后 cut 到下一个空行.
     *
     * <p>为什么要按帧切而不是 {@code chunks.get(i)} —— 改之前一帧占三片, {@code get(i)} 拿到的是
     * 半截帧, 于是红会落在"这一帧里没有 id"这种解析失败上, 而不是"帧被切开了"这条真判据上。
     */
    private static String frameAt(List<String> chunks, int index) {
        StringBuilder seen = new StringBuilder();
        for (String chunk : chunks) seen.append(chunk);
        String rest = seen.toString();
        for (int i = 0; i < index; i++) {
            int end = rest.indexOf("\n\n");
            assertTrue("前面只有 " + i + " 帧, 没有第 " + index + " 帧: " + flat(chunks), end >= 0);
            rest = rest.substring(end + 2);
        }
        int end = rest.indexOf("\n\n");
        assertTrue("第 " + index + " 帧没有以空行收尾(残余: " + flat(rest) + ")", end >= 0);
        return rest.substring(0, end + 2);
    }

    private static void assertSameBytes(String where, String decodedByGauge, String readByClient) {
        if (!decodedByGauge.equals(readByClient)) {
            fail(where + "两边字节不同 ⇒ chunk 计数读的是幻觉"
                    + "\n  原始解码: " + flat(decodedByGauge)
                    + "\n  普通读取: " + flat(readByClient));
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static long eventId(String frame) {
        Long id = null;
        for (String line : frame.split("\n")) {
            if (line.startsWith("id:")) id = Long.valueOf(line.substring(3).trim());
        }
        assertNotNull("帧里没有 event id: " + flat(frame), id);
        return id.longValue();
    }

    /** 断言消息里先压成一行: surefire 在换行处截断, 红消息只剩半截 id. */
    private static String flat(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String flat(List<String> chunks) {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) all.append(" | ");
            all.append("[").append(i).append("]").append(flat(chunks.get(i)));
        }
        return all.toString();
    }

    private HttpURLConnection openOrdinary(String sessionId, String version) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", EVENT_STREAM);
        connection.setRequestProperty(SESSION, sessionId);
        connection.setRequestProperty(PROTOCOL, version);
        assertEquals(HttpStatus.OK.value(), connection.getResponseCode());
        return connection;
    }

    private String handshake(String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", BOTH);
        // 不写这行的话 RestTemplate 会给 String 体补一个 text/plain ⇒ 415
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"" + version + "\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"atomicity\",\"version\":\"1\"}}}", headers),
                String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        String id = res.getHeaders().getFirst(SESSION);
        assertNotNull("握手没拿到会话 id", id);

        HttpHeaders ack = new HttpHeaders();
        ack.set("Accept", BOTH);
        ack.setContentType(MediaType.APPLICATION_JSON);
        ack.set(SESSION, id);
        ack.set(PROTOCOL, version);
        rest.exchange("http://127.0.0.1:" + port + "/mcp", HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", ack),
                String.class);
        return id;
    }

    private void terminate(String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SESSION, sessionId);
        headers.set(PROTOCOL, OLD_VERSION);
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + "/mcp",
                HttpMethod.DELETE, new HttpEntity<String>(headers), String.class);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
    }
}
