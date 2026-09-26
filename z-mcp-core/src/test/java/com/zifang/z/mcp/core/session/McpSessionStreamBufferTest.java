package com.zifang.z.mcp.core.session;

import com.zifang.z.mcp.core.properties.McpProperties;
import org.junit.Before;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 会话级 SSE 事件缓冲与 {@code Last-Event-ID} 续传.
 *
 * <p>续传的正确性是一条整体性质 —— "一条事件既不丢也不重", 而丢不丢取决于**并发时序**
 * (广播正好落在补发快照之前、临界区之中、还是挂载之后)。所以这一层直接在会话对象上量:
 * 挂一个只记账的 emitter, 数它拿到了哪些帧、按什么顺序。真 HTTP 那一层
 * (见 starter 的 {@code ZMcpSseStreamTest})另有用例证明这套东西在 Tomcat 的写出路径上也成立。
 */
public class McpSessionStreamBufferTest {

    private McpProperties properties;
    private McpSessionStore store;
    private final AtomicInteger issued = new AtomicInteger();

    @Before
    public void setUp() {
        properties = new McpProperties();
        store = new McpSessionStore(properties);
    }

    private McpSessionStore.McpSession session(int bufferSize) {
        properties.getSse().setBufferSize(bufferSize);
        McpSessionStore.McpSession s = store.create("s-" + issued.incrementAndGet(), "2025-11-25");
        assertTrue("会话没签发出来", s != null);
        return s;
    }

    // -------------------------------------------------------- priming 与 retry

    /**
     * 协议(2025-11-25)点名的 SHOULD: 开流就发一条"只有 event id、data 为空"的事件, 让还没收到
     * 任何通知的客户端立刻拿到一个可续传的坐标。只发注释帧不开 id, 等于第一次断线(生产上最常见的
     * 那次)无据可依。
     */
    @Test
    public void a_stream_opens_with_a_primed_event_id_and_a_retry_field() throws Exception {
        McpSessionStore.McpSession s = session(64);
        Recorder frames = new Recorder();

        assertEquals("没有要补发的东西", 0, s.attach(frames, null, "open", 3000L));

        assertEquals(1, frames.size());
        String prime = frames.get(0);
        assertTrue("开流帧要带注释, 好让人在 curl 里认出这条流: " + flat(prime), prime.contains(":open"));
        assertTrue("priming 帧要带 id: " + flat(prime), prime.contains("id:"));
        assertEquals(1L, idOf(prime));
        assertTrue("要带 retry, 客户端对它是 MUST: " + flat(prime), prime.contains("retry:3000"));
        assertTrue("priming 帧的 data 是空的(它不是一条消息): " + flat(prime), dataOf(prime).isEmpty());
        assertEquals(1, s.emitterCount());
    }

    @Test
    public void the_retry_field_is_left_out_when_the_server_has_no_opinion() throws Exception {
        McpSessionStore.McpSession s = session(64);
        Recorder frames = new Recorder();
        s.attach(frames, null, "open", 0L);
        assertFalse("retry-ms=0 就不该下发: " + flat(frames.get(0)), frames.get(0).contains("retry:"));
    }

    // ------------------------------------------------------------------ 补发

    @Test
    public void a_resuming_stream_gets_exactly_what_it_missed_and_nothing_twice() throws Exception {
        McpSessionStore.McpSession s = session(64);
        s.emit("{\"n\":1}");
        s.emit("{\"n\":2}");
        s.emit("{\"n\":3}");
        Recorder frames = new Recorder();

        // 客户端说"我看到 1 为止": 2、3 补发, 然后才是这条流自己的 priming 帧
        assertEquals(2, s.attach(frames, Long.valueOf(1), "open", 1000L));

        assertEquals(3, frames.size());
        assertEquals("补发要按原顺序, 客户端是增量消费的: " + show(frames), "2,3,4", ids(frames));
        assertEquals("{\"n\":2}", dataOf(frames.get(0)));
        assertEquals("{\"n\":3}", dataOf(frames.get(1)));
        assertTrue("priming 帧要排在补发之后, id 才不会倒退: " + flat(frames.get(2)),
                idOf(frames.get(2)) == 4L);
    }

    /** 一条全新的流不该收到历史: 客户端没要续传, 把开流之前的事件塞进去是在编造它没看过的状态. */
    @Test
    public void a_stream_without_last_event_id_replays_nothing() throws Exception {
        McpSessionStore.McpSession s = session(64);
        s.emit("{\"n\":1}");
        Recorder frames = new Recorder();

        assertEquals(0, s.attach(frames, null, "open", 1000L));
        assertEquals(1, frames.size());
        assertTrue("只有 priming 帧: " + flat(frames.get(0)), !frames.get(0).contains("\"n\""));
    }

    @Test
    public void a_resumed_stream_still_receives_live_events_afterwards() throws Exception {
        McpSessionStore.McpSession s = session(64);
        s.emit("{\"n\":1}");
        s.emit("{\"n\":2}");
        s.emit("{\"n\":3}");
        Recorder frames = new Recorder();
        s.attach(frames, Long.valueOf(2), "open", 1000L);
        int afterAttach = frames.size();

        s.emit("{\"n\":4}");

        assertEquals("补发之后这条流要接着收实时事件", afterAttach + 1, frames.size());
        assertEquals("{\"n\":3}", dataOf(frames.get(0)));
        // 补发 1 帧(id 3) + priming(id 4), 之后实时那条才是 id 5 —— priming 也占号, 不然两条流会撞号
        assertEquals(3L, idOf(frames.get(0)));
        assertEquals(4L, idOf(frames.get(1)));
        assertEquals(5L, idOf(frames.get(afterAttach)));
        assertEquals("{\"n\":4}", dataOf(frames.get(afterAttach)));
    }

    /**
     * 丢事件的唯一窗口是"广播落在补发快照之后、挂载之前", 所以取号 + 入缓冲 + 挂载得在同一把锁里。
     * 这里让一次并发广播正好撞进 attach 写 priming 帧的时刻: 它只能阻塞在会话锁上, 于是事件必然在
     * 挂载完成之后走实时投递 —— 要钉的是它**到了**, 而且只到一次。
     */
    @Test
    public void an_event_published_mid_attach_is_delivered_exactly_once() throws Exception {
        final McpSessionStore.McpSession s = session(64);
        s.emit("{\"n\":1}");
        final Recorder frames = new Recorder();
        // 广播线程等 attach 真的进了临界区(钩子跑到 priming 帧的 send)才动手 —— 不靠 sleep 猜时序,
        // 否则"谁先拿到锁"就变成随机的, 这条用例也就变成偶发红。
        final CountDownLatch insideCritical = new CountDownLatch(1);
        final AtomicInteger observedState = new AtomicInteger(-1);
        final Thread broadcaster = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    assertTrue("等不到 attach 进临界区",
                            insideCritical.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                s.emit("{\"n\":\"racy\"}");
            }
        }, "z-mcp-racy-broadcaster");
        // 钩子跑在 attach 的临界区内(锁已持有): 放广播进来, 然后确认它确实被挡在门外。
        frames.onFirstSend = new Runnable() {
            @Override public void run() {
                insideCritical.countDown();
                long deadline = System.currentTimeMillis() + 5000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.State state = broadcaster.getState();
                    if (state == Thread.State.BLOCKED) {
                        observedState.set(state.ordinal());
                        return;
                    }
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                observedState.set(broadcaster.getState().ordinal());
            }
        };

        broadcaster.start();
        s.attach(frames, Long.valueOf(1), "open", 1000L);
        broadcaster.join(10_000L);

        assertFalse("广播线程没跑完: " + broadcaster.getState(), broadcaster.isAlive());
        assertEquals("广播线程本该阻塞在会话锁上; 它没等到, 说明 attach 的锁没盖住这段: "
                        + Thread.State.values()[observedState.get()],
                Thread.State.BLOCKED.ordinal(), observedState.get());
        // 补发快照里没有它, 挂载之后才实时到 —— 于是这条流上它恰好一次
        List<String> racy = framesWith(frames, "\"racy\"");
        assertEquals("这条事件在流上恰好出现一次(丢是事故, 重了得靠客户端去重): " + show(frames),
                1, racy.size());
        assertEquals("priming 帧排在实时事件之前: " + show(frames), 2L, idOf(frames.get(0)));
        assertEquals("实时事件的 id 必须排在 priming 之后: " + show(frames), 3L, idOf(racy.get(0)));
    }

    // -------------------------------------------------------------- 缓冲边界

    @Test
    public void the_buffer_is_bounded_so_a_quiet_session_cannot_grow_it_forever() throws Exception {
        McpSessionStore.McpSession s = session(4);
        for (int i = 1; i <= 20; i++) {
            s.emit("{\"n\":" + i + "}");
        }
        assertEquals("缓冲要停在配置值上, 不能跟着广播一直长", 4, s.bufferedCount());

        Recorder frames = new Recorder();
        // 淘汰到只剩 17..20: 16 之后的都能补上
        assertEquals(4, s.attach(frames, Long.valueOf(16), "open", 1000L));
        assertEquals("17,18,19,20,21", ids(frames));

        Recorder refused = new Recorder();
        assertEquals("id 1 已经被淘汰, 补不上了就该拒", -1, s.attach(refused, Long.valueOf(1), "open", 1000L));
        assertEquals("拒绝时一条都不能发出去(半截流比明着失败更难查)", 0, refused.size());
        assertEquals("拒绝也不该把流挂上去", 1, s.emitterCount());
    }

    /** {@code buffer-size=0} 是显式关掉续传: 此时任何真要续传的请求都回不了头. */
    @Test
    public void turning_the_buffer_off_refuses_resumption_but_still_opens_a_stream() throws Exception {
        McpSessionStore.McpSession s = session(0);
        s.emit("{\"n\":1}");
        assertEquals(0, s.bufferedCount());

        Recorder refused = new Recorder();
        assertEquals(-1, s.attach(refused, Long.valueOf(0), "open", 1000L));
        assertEquals(0, refused.size());

        Recorder fresh = new Recorder();
        assertEquals(0, s.attach(fresh, null, "open", 1000L));
        assertEquals(1, fresh.size());
    }

    /** 客户端报的 id 比服务端发过的还大(比如服务端换过): 没什么可补, 但也别把人家拒了. */
    @Test
    public void a_client_ahead_of_the_server_is_not_turned_away() throws Exception {
        McpSessionStore.McpSession s = session(4);
        s.emit("{\"n\":1}");
        Recorder frames = new Recorder();
        assertEquals(0, s.attach(frames, Long.valueOf(9999), "open", 1000L));
        assertEquals(1, frames.size());
        assertEquals(2L, idOf(frames.get(0)));
    }

    /**
     * 协议 MUST: 事件 id 在一个会话的**所有**流之间唯一 —— 两条流各自拿到自己的 priming id,
     * 同一个 id 只可能对应同一份内容。重了的话, 按 id 去重的客户端会把后来的通知当成重放丢掉。
     */
    @Test
    public void event_ids_stay_unique_across_every_stream_of_one_session() throws Exception {
        McpSessionStore.McpSession s = session(64);
        Recorder a = new Recorder();
        s.attach(a, null, "open", 1000L);            // priming id 1
        s.emit("{\"n\":1}");                         // id 2
        s.emit("{\"n\":2}");                         // id 3
        Recorder b = new Recorder();
        s.attach(b, null, "open", 1000L);            // priming id 4: 不复用 a 用过的号
        s.emit("{\"n\":3}");                         // id 5, 两条流都收到

        assertEquals("1,2,3,5", ids(a));
        assertEquals("4,5", ids(b));

        Map<Long, String> byId = new LinkedHashMap<Long, String>();
        List<Recorder> both = new ArrayList<Recorder>();
        both.add(a);
        both.add(b);
        for (Recorder stream : both) {
            for (String frame : stream.all()) {
                String earlier = byId.put(Long.valueOf(idOf(frame)), dataOf(frame));
                assertTrue("同一个 id 发了两份不同内容: " + flat(frame),
                        earlier == null || earlier.equals(dataOf(frame)));
            }
        }
        assertEquals("两条流合起来覆盖了 5 个事件号", 5, byId.size());
    }

    // ------------------------------------------------------------------ 量具

    // -------------------------------------------------------------- 心跳注释帧

    /**
     * 心跳只是"这条连接还在说话", 不是一条通知. 它一旦去占事件序号, 一个安静的流会被自己的
     * 心跳推到很高的 id, 而那段 id 区间里缓冲什么都没有 —— 客户端拿它回来续传只会撞到一个
     * 没意义的 400. 所以这里量的正是"心跳一个号都没花掉".
     */
    @Test
    public void a_heartbeat_costs_the_client_nothing_on_the_stream() throws Exception {
        McpSessionStore.McpSession s = session(64);
        Recorder frames = new Recorder();
        s.attach(frames, null, "open", 1000L);
        int afterPrime = frames.size();
        long primeId = idOf(frames.get(afterPrime - 1));
        assertEquals("priming 该是流上第一条带 id 的帧", 1L, primeId);

        assertEquals("一条挂着流就该有一次心跳", 1, store.tick());
        assertEquals("两次心跳", 1, store.tick());

        List<String> beats = framesWith(frames, "keep-alive");
        assertEquals("每次心跳各落一帧: " + show(frames), 2, beats.size());
        String beat = beats.get(0);
        assertTrue("心跳必须是注释帧: " + flat(beat), beat.startsWith(":keep-alive"));
        assertFalse("心跳不带 id, 否则客户端会把它当成一个可续传的事件: " + flat(beat),
                beat.contains("id:"));
        assertFalse("心跳不带 data, 否则就成了一条空通知: " + flat(beat), beat.contains("data:"));

        s.emit("{\"n\":2}");
        assertEquals("心跳一个号都没占, 真实通知紧接 priming: " + show(frames),
                primeId + 1L, idOf(frames.get(frames.size() - 1)));
    }

    /** 心跳不进缓冲 —— 补发出来的必须只有真实事件. */
    @Test
    public void heartbeats_never_enter_the_resume_buffer() throws Exception {
        McpSessionStore.McpSession s = session(64);
        s.emit("{\"n\":1}");
        s.emit("{\"n\":2}");
        Recorder first = new Recorder();
        s.attach(first, null, "open", 1000L);          // priming id 3
        for (int i = 0; i < 5; i++) store.tick();

        assertEquals("缓冲里还是那两条真实事件", 2, s.bufferedCount());

        Recorder resumed = new Recorder();
        // 从 0 续 = 客户端一条都没收过, 缓冲里留着的全该补回来. 从 1 续只会补 id 2 那一条 ——
        // 心跳若占了号, 补发的条数会跟着漂, 所以钉在这里.
        assertEquals("补发条数不受心跳影响", 2,
                s.attach(resumed, Long.valueOf(0L), "open", 1000L));
        assertEquals("补出来的还是那两条, 没有心跳混进来: " + show(resumed),
                "{\"n\":1},{\"n\":2}", dataOf(resumed.get(0)) + "," + dataOf(resumed.get(1)));
    }

    /** 客户端早就不读了: 心跳是第一个发现这件事的人, 它得把那条流摘掉而不是每轮撞一次墙. */
    @Test
    public void a_stream_that_cannot_be_written_to_is_dropped_by_the_heartbeat() throws Exception {
        McpSessionStore.McpSession s = session(64);
        Boom boom = new Boom();
        s.addEmitter(boom);
        s.addEmitter(new Recorder());
        assertEquals(2, s.emitterCount());

        assertEquals("只有那条写得出去的算送达", 1, store.tick());
        assertEquals("写不出去的那条该被摘掉: " + s.emitterCount(), 1, s.emitterCount());
        assertSame("摘掉名单不等于收掉请求: 心跳发现的这条死流要当场按写出失败的那个因闭合",
                boom.failure.get(), boom.closedWith.get());
        assertEquals("摘完剩下的那条继续收心跳", 1, store.tick());
    }

    /**
     * 一次**广播**撞上跑掉的客户端: 死掉的那条要闭合, 活着的那条不能受牵连.
     *
     * <p>{@code emit()} 里那次写出是通知路径上唯一会碰到对端的动作, 也是它可能失败的地方。摘掉
     * emitter 只解决了"别再撞第二次墙", 不解决"那条异步请求还挂在容器上" —— Spring 的 {@code send()}
     * 在写失败时只置 {@code sendFailed} 并把异常原样抛回来, 不 complete 的话它要一直挂到
     * {@code sse.timeout-ms} 才走 onTimeout(生产上就是几十个没人读却占着异步上下文的请求)。
     * 另一半判据同样要有猎物: 摘 emitter 不能顺手把事件本身弄丢, 别的客户端仍然要按那个 id 续得上。
     */
    @Test
    public void a_broadcast_to_a_client_that_hung_up_closes_it_without_disturbing_the_others()
            throws Exception {
        McpSessionStore.McpSession s = session(64);
        Boom boom = new Boom(1);            // 开流那帧写得出去, 之后的广播就撞墙了
        Recorder live = new Recorder();
        s.attach(boom, null, "open", 0L);
        s.attach(live, null, "open", 0L);
        assertEquals(2, s.emitterCount());
        long liveKnows = idOf(live.get(0));

        s.emit("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");

        assertEquals("活着的那条该收到 priming + 通知: " + show(live), 2, live.size());
        assertTrue("通知要原样落到活着的流上: " + show(live),
                live.get(1).contains("notifications/tools/list_changed"));
        assertEquals("写不出的那条该从名单里摘掉: " + s.emitterCount(), 1, s.emitterCount());
        assertSame("摘掉的同时要闭合它, 别让异步请求挂到 sse.timeout-ms; 而且要说清是失败收尾",
                boom.failure.get(), boom.closedWith.get());

        Recorder resumed = new Recorder();
        assertEquals("那条事件不能跟着 emitter 一起丢: 还留在缓冲里补得上",
                1, s.attach(resumed, Long.valueOf(liveKnows), "open", 0L));
        assertTrue("续传补出来的正是跑掉那个客户端没听到的那条: " + show(resumed),
                dataOf(resumed.get(0)).contains("notifications/tools/list_changed"));
        assertEquals("补发的 id 还是广播时那个: " + show(resumed),
                idOf(live.get(1)), idOf(resumed.get(0)));
    }

    /**
     * {@code keep-alive-seconds} 得是一条真的在说话的配置: 0 = 连线程都不建,
     * 配了值 = 线程起来, {@code close()} = 线程收掉(容器关停后不该留下一个还在写流的调度器)。
     */
    @Test
    public void the_heartbeat_thread_exists_only_while_the_config_wants_it() throws Exception {
        McpSessionStore.McpSession s = session(64);
        s.addEmitter(new Recorder());

        properties.getSse().setKeepAliveSeconds(0L);
        int before = keepAliveThreads();
        store.startKeepAliveIfNeeded();
        store.startKeepAliveIfNeeded();
        assertEquals("关掉就不该建心跳线程", before, keepAliveThreads());

        properties.getSse().setKeepAliveSeconds(41L);
        store.startKeepAliveIfNeeded();
        store.startKeepAliveIfNeeded();                 // 幂等: 不能起第二个调度器
        assertEquals("配了就该有一个, 而且只能有一个", before + 1, keepAliveThreads());

        store.close();
        long deadline = System.currentTimeMillis() + 5000L;
        while (keepAliveThreads() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertEquals("close() 之后心跳线程要消失", before, keepAliveThreads());
    }

    /** 数一下本进程里还活着的心跳线程 —— 这条配置唯一的外部证据, 不用 sleep 猜时序. */
    private static int keepAliveThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("z-mcp-sse-keepalive-")) n++;
        }
        return n;
    }

    /**
     * 一条"从第 {@code diesFrom + 1} 次写出起对端就不读了"的流: 写出每次都炸, 并且记下自己有没有
     * 被真的收掉。
     *
     * <p>{@code diesFrom} 是为广播那条用例留的 —— {@code attach()} 自己就要写一帧 priming,
     * 一个"从一开始就写不出"的假流根本挂不进名单, 于是测不到"跑掉的客户端"这个真实形状
     * (流是先开好的, 客户端半路关了)。
     */
    private static final class Boom extends Recorder {
        /** 只记"按错误闭合"那一条路: 记成 {@code complete()} 也算红, 两种收尾对容器说的话不一样. */
        private final AtomicReference<Throwable> closedWith = new AtomicReference<Throwable>();
        /** 那次写出真正抛出去的东西 —— 闭合时必须原样带出去, 不能换个别的. */
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private final int diesFrom;
        private int sends;

        Boom() {
            this(0);
        }

        Boom(int diesFrom) {
            this.diesFrom = diesFrom;
        }

        @Override public void send(SseEventBuilder builder) throws IOException {
            if (sends++ >= diesFrom) {
                IOException gone = new IOException("client is gone");
                failure.set(gone);
                throw gone;
            }
            super.send(builder);
        }

        @Override public void completeWithError(Throwable ex) {
            closedWith.set(ex);
            super.completeWithError(ex);
        }
    }

    /** 断言消息里的帧原文必须先压成一行: surefire 在换行处截断, 红消息只剩半截 id. */
    private static String show(Recorder frames) {
        StringBuilder all = new StringBuilder();
        for (String frame : frames.all()) {
            if (all.length() > 0) all.append(" || ");
            all.append(flat(frame));
        }
        return all.toString();
    }

    private static List<String> framesWith(Recorder frames, String needle) {
        List<String> hits = new ArrayList<String>();
        for (String frame : frames.all()) {
            if (frame.contains(needle)) hits.add(frame);
        }
        return hits;
    }

    private static long idOf(String frame) {
        for (String line : frame.split("\n")) {
            if (line.startsWith("id:")) return Long.parseLong(line.substring(3).trim());
        }
        throw new AssertionError("帧里没有 id: " + flat(frame));
    }

    private static String dataOf(String frame) {
        StringBuilder data = new StringBuilder();
        for (String line : frame.split("\n")) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5));
            }
        }
        return data.toString();
    }

    private static String ids(Recorder frames) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) ids.append(',');
            ids.append(idOf(frames.get(i)));
        }
        return ids.toString();
    }

    private static String flat(String frame) {
        return frame.replace("\r", "").replace("\n", " <LF> ");
    }

    /** 记账用的 emitter: 把每一帧渲染成 SSE 原文, 于是 id / data / retry 都能按字段断言. */
    private static class Recorder extends SseEmitter {
        Runnable onFirstSend;
        private final List<String> frames = new ArrayList<String>();

        Recorder() {
            super(Long.valueOf(Long.MAX_VALUE));
        }

        @Override public void send(SseEventBuilder builder) throws IOException {
            Runnable hook = onFirstSend;
            if (hook != null) {
                onFirstSend = null;
                hook.run();
            }
            StringBuilder frame = new StringBuilder();
            for (DataWithMediaType part : builder.build()) {
                if (part.getData() != null) frame.append(part.getData());
            }
            String rendered = flatTail(frame.toString());
            synchronized (frames) { frames.add(rendered); }
        }

        int size() {
            synchronized (frames) { return frames.size(); }
        }

        String get(int i) {
            synchronized (frames) { return frames.get(i); }
        }

        List<String> all() {
            synchronized (frames) { return new ArrayList<String>(frames); }
        }
    }

    /**
     * Spring 在 {@code build()} 里给每帧补的分隔符是平台相关的({@code \r\n} 在 Windows 上),
     * 断言按字段读, 先把行尾归一, 免得量具在别的机器上假红.
     */
    private static String flatTail(String frame) {
        return frame.replace("\r\n", "\n");
    }
}
