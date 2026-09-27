package com.zifang.z.mcp.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.zifang.z.mcp.core.properties.McpProperties;

/**
 * 一个**还在连着、但不再读**的对端不许把别人的心跳带走.
 *
 * <p>前提(不在本仓的代码里, 因此这里用替身在同一个接缝上复现): {@code SseEmitter.send()} 是一次
 * 同步写出, 对端不读时 TCP 发送缓冲写满就会阻塞调用线程 —— 本机真 Tomcat 实测一次这样的写卡住
 * <b>65 秒</b>才抛 {@code SocketTimeoutException}(台账与复算见 README「#60」那一格)。
 *
 * <p>于是心跳不能"一根线程顺序写完所有会话的所有流": 那一个客户的不读, 代价是所有人的流一起安静
 * 到超时, 而中间层正是按安静来掐连接的。现在的形状是 {@code tick()} 把每条流的写出<b>派发</b>到
 * 一个有界的写出手池({@code McpSessionStore.KEEP_ALIVE_WRITERS})上, 计时线程最多等
 * {@code KEEP_ALIVE_WRITE_BUDGET_MS} 就带着"预算内确实落了多少"回来; 一条流同时只允许有一次在飞。
 * 这就是 #45 在服务端一侧的孪生(#45 量的是 hub 的一根读线程被一个上游的请求卡住, 于是那个上游的
 * 其余消息全进不来)。
 *
 * <p>下面五格各钉住这个形状的一环, 而且各有各的猎物(红集是变异网格量出来的, 不是推的 —— 台账在
 * README「#60」那一格): 把派发改回"计时线程上顺序写"红第一、二、四格; 摘掉在飞旗的闸门只红第三格;
 * 把预算常量改成死等只红第四格(前三格盯的是派发, 不看预算); 摘掉 {@code close()} 里对写出手的收编
 * 只红最后一格。每一格都只等"该到的那一次", 不等任何固定时长, 所以不靠 sleep 猜时序
 * (那种断言在 CI 上红过一次, 见 #37 那一格)。
 *
 * <p>第三格读的是 {@code keepAliveInFlightCount()} —— 它是"派出去的心跳把在飞旗还没还回来"的唯一
 * 外部证据, 没有它那一格只能靠时长猜两拍有没有撞在一起。
 */
public class McpSessionHeartbeatBlockingTest {

    private McpProperties properties;
    private McpSessionStore store;

    @Before
    public void setUp() {
        properties = new McpProperties();
        properties.getSse().setKeepAliveSeconds(0);        // 不起调度器, 这一拍由测试手动敲
        store = new McpSessionStore(properties);
    }

    @After
    public void tearDown() {
        store.close();
    }

    /**
     * 同一会话上的两条流: 第一条的对端不读了, 第二条(完全健康的)还要不要收得到心跳.
     *
     * <p>{@code dispatchKeepAlive()} 遍历的是 {@code List}, 顺序由挂载顺序决定, 所以这一格不需要任何
     * 时钟或哈希序就能判.
     */
    @Test(timeout = 30_000L)
    public void a_peer_that_stopped_reading_must_not_starve_the_streams_behind_it() throws Exception {
        McpSessionStore.McpSession s = store.create("s-hol", "2025-11-25");
        Stalling stalled = new Stalling();
        Counter healthy = new Counter();
        s.addEmitter(stalled);
        s.addEmitter(healthy);
        assertEquals("两条流都挂上了", 2, s.emitterCount());

        Thread ticker = new Thread(new Runnable() {
            @Override public void run() { store.tick(); }
        }, "z-mcp-test-tick");
        ticker.setDaemon(true);
        ticker.start();

        assertTrue("tick 该走到那次阻塞的写出上", stalled.entered.await(5, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 3_000L;
        while (healthy.n == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        final int duringTheStall = healthy.n;
        stalled.release.countDown();
        ticker.join(10_000L);
        final int afterTheStall = healthy.n;

        assertTrue("tick 那一拍最终要返回", false == ticker.isAlive());
        assertTrue("一次写不出去(对端不读、阻塞在写出上)的流, 不该把同一次 tick 里排在它后面的"
                + "心跳一起带走: 阻塞期间健康的那条收到 " + duringTheStall
                + " 次心跳, 放开之后才收到第 " + afterTheStall
                + " 次 —— 事件没丢, 丢的是'还连着但暂时不读'那个客户端的邻居们的时间。",
                duringTheStall >= 1);
    }

    /**
     * **跨会话**的那一半: 一个会话里卡住的写出不许把后面那个会话的这一拍一起带走。
     *
     * <p>会话存在 {@code ConcurrentHashMap} 里, 遍历顺序不由插入顺序决定, 所以这一格不猜顺序: 先用
     * 一次"谁都不阻塞"的 tick 把两格会话的**实际访问顺序**量出来, 再把阻塞装到先被访问的那一格上,
     * 然后看后访问的那一格还能不能收到心跳。顺序是量出来的, 判据就与哈希布列无关。
     */
    @Test(timeout = 30_000L)
    public void a_stalled_session_must_not_starve_the_sessions_visited_after_it() throws Exception {
        final List<String> order = Collections.synchronizedList(new ArrayList<String>());
        McpSessionStore.McpSession first = store.create("s-x", "2025-11-25");
        McpSessionStore.McpSession second = store.create("s-y", "2025-11-25");
        Peer a = new Peer("s-x", order);
        Peer b = new Peer("s-y", order);
        first.addEmitter(a);
        second.addEmitter(b);

        assertEquals("一次 tick 该覆盖两条会话", 2, store.tick());
        assertEquals("两格会话各被访问一次(顺序由下面现读, 不猜): " + order, 2, order.size());
        Peer stalled = "s-x".equals(order.get(0)) ? a : b;
        Peer neighbour = stalled == a ? b : a;
        int before = neighbour.beats.get();

        stalled.blocks = true;
        Thread ticker = new Thread(new Runnable() {
            @Override public void run() { store.tick(); }
        }, "z-mcp-test-tick-2");
        ticker.setDaemon(true);
        ticker.start();

        assertTrue("tick 该走到那次阻塞的写出上", stalled.entered.await(5, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 3_000L;
        while (neighbour.beats.get() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        final int duringTheStall = neighbour.beats.get() - before;
        stalled.release.countDown();
        ticker.join(10_000L);

        assertTrue("tick 那一拍最终要返回", false == ticker.isAlive());
        assertTrue("会话 " + order.get(0) + " 的写出卡在阻塞里时, 排在它后面的会话 "
                + order.get(1) + " 一次心跳都没拿到(" + duringTheStall
                + ") —— 一根线程按顺序跑所有会话, 一个不读的对端就能让所有人一起安静到超时。",
                duringTheStall >= 1);
        assertTrue("放开之后邻居该补上这一拍", neighbour.beats.get() - before >= 1);
    }

    /**
     * 一条流同时只许有一次在飞: 上一拍还卡在某个对端上的时候, 下一拍不许再给它排一次.
     *
     * <p>叠任务在这格上有两种坏法, 而两种都不会让前两格变红, 所以单独钉: ① 心跳是幂等的, 排第二次
     * 不会让那个客户端更早收到东西, 只会多占一根写出手(8 根用满之后所有人都没有心跳了);
     * ② 还在飞的流不能算成"这一拍送达了" —— 它没送达, 记账说成送达就是谎。
     * 第二句同时划清分工: "送达到底怎么记账"这一格有立场但不独占 —— 把记账挪到写出之前那种错法红的是
     * 单元层那两格(它们的流会真抛异常), 这一格的流只是卡住、不抛, 所以照旧绿(网格实测).
     */
    @Test(timeout = 30_000L)
    public void a_stream_whose_beat_is_still_writing_does_not_get_a_second_one_queued() throws Exception {
        McpSessionStore.McpSession s = store.create("s-pileup", "2025-11-25");
        final Stalling stalled = new Stalling();
        final Counter healthy = new Counter();
        s.addEmitter(stalled);
        s.addEmitter(healthy);

        Thread first = new Thread(new Runnable() {
            @Override public void run() { store.tick(); }
        }, "z-mcp-test-tick-3a");
        first.setDaemon(true);
        first.start();
        assertTrue("第一拍该走到那次阻塞的写出上", stalled.entered.await(5, TimeUnit.SECONDS));
        // 先等第一拍里**健康那条**把在飞旗还掉(还旗是它自己任务的最后一步), 第二拍的读数才只与派发规则
        // 有关而不是与调度速度有关 —— 否则"这一拍送达到底算几条"取决于两拍撞没撞在一起, 就是条时序耦合的判据。
        assertTrue("第一拍里健康的那条该写回来并把在飞旗还掉(只剩卡住的那条还挂着): 实测还挂着 "
                + s.keepAliveInFlightCount() + " 条", waitInFlight(s, 1));

        final int second = store.tick();
        final int sendsWhileStalled = stalled.sends.get();
        final int stillInFlight = s.keepAliveInFlightCount();
        stalled.release.countDown();
        first.join(10_000L);
        assertTrue("放开之后在飞旗要全部还回来: 还剩 " + s.keepAliveInFlightCount() + " 条挂着",
                waitInFlight(s, 0));

        assertEquals("卡在对方那里的那条不该被记成这一拍送达了(健康的那条才是): " + second, 1, second);
        assertEquals("上一拍还没写回来的流不该被叠第二次写出: 阻塞期间它被写了 "
                + sendsWhileStalled + " 次", 1, sendsWhileStalled);
        assertEquals("第二拍不许给卡住的那条重新挂旗", 1, stillInFlight);
    }

    /**
     * 预算这一环单独钉: 一个卡住的对端最多让邻居**晚一拍**, 不许让所有人停摆。
     *
     * <p>形状照调度器来 —— 连续敲 {@code tick()}(上一拍回来才敲下一拍)。前两格盯的是"派发"(卡住的
     * 那条不带走同批别的流), 第三格盯的是"不叠任务", 而把等待改成"死等那次写回来"的写法**三格都会绿**:
     * 第一拍派完健康那条的写出之后就再也不回来, 于是邻居只收到一次心跳然后一起安静 —— 那正是修之前
     * 实测到的塌法(README「#60」那一格, 六条邻居从每秒一次塌成 8 秒一次)。
     *
     * <p>判据是"阻塞期间至少两拍"而不是"多久": 每拍最多花 {@code KEEP_ALIVE_WRITE_BUDGET_MS},
     * 两拍就是两秒量级, 下面 10 秒的界是给失败方向的余量, 不是断言的对象。
     */
    @Test(timeout = 30_000L)
    public void beats_keep_coming_for_the_healthy_stream_while_one_peer_is_stalled() throws Exception {
        McpSessionStore.McpSession s = store.create("s-cadence", "2025-11-25");
        final Stalling stalled = new Stalling();
        final Counter healthy = new Counter();
        s.addEmitter(stalled);
        s.addEmitter(healthy);

        final AtomicBoolean stop = new AtomicBoolean();
        Thread ticker = new Thread(new Runnable() {
            @Override public void run() {
                while (!stop.get()) store.tick();
            }
        }, "z-mcp-test-tick-5");
        ticker.setDaemon(true);
        ticker.start();

        assertTrue("连续 tick 该走到那次阻塞的写出上", stalled.entered.await(5, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 10_000L;
        while (healthy.n < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        final int duringTheStall = healthy.n;
        stop.set(true);
        stalled.release.countDown();
        ticker.join(10_000L);

        assertTrue("ticker 最终要停下来", false == ticker.isAlive());
        assertTrue("阻塞期间健康的那条只收到 " + duringTheStall
                + " 次心跳 —— 一拍要是不在预算内回来, 计时线程就陪着那个不读的对端站住, "
                + "所有邻居一起安静到中间层掐线", duringTheStall >= 2);
    }

    /**
     * {@code close()} 之后不许再派心跳写出: 容器正在关停, 这时候再往流上写等于在没人收的响应上
     * 又挂一次异步请求。这一格数的是"心跳机制留下的线程与写出都不再增加", 判据是纯结构的
     * (一次调用返回 0 且一条流都没被碰), 所以不依赖任何时长。
     */
    @Test(timeout = 30_000L)
    public void closing_the_store_stops_dispatching_beats() throws Exception {
        McpSessionStore.McpSession s = store.create("s-closed", "2025-11-25");
        Counter healthy = new Counter();
        s.addEmitter(healthy);

        assertEquals("关之前一拍该有一条送达", 1, store.tick());

        store.close();

        assertEquals("close() 之后不该再有写出", 0, store.tick());
        assertEquals("close() 之后那条流收到的心跳数不该再涨", 1, healthy.n);
    }

    /** 有界地等到在飞名单只剩 {@code want} 条: 等的是因果(旗子还不还), 不是固定时长. */
    private static boolean waitInFlight(McpSessionStore.McpSession s, int want) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (s.keepAliveInFlightCount() == want) return true;
            Thread.sleep(10L);
        }
        return s.keepAliveInFlightCount() == want;
    }

    /** 一条流: 平时记账(并留下被访问的时刻), 装上 {@code blocks} 之后那次写出就卡在对端不读的形状上. */
    private static final class Peer extends SseEmitter {
        final String label;
        final List<String> visitOrder;
        final AtomicInteger beats = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean blocks;

        Peer(String label, List<String> visitOrder) {
            super(Long.valueOf(Long.MAX_VALUE));
            this.label = label;
            this.visitOrder = visitOrder;
        }

        @Override public void send(SseEventBuilder builder) throws IOException {
            if (blocks) {
                entered.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("probe timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                return;
            }
            visitOrder.add(label);
            beats.incrementAndGet();
        }
    }

    /** 写出阻塞在对端上的那条流: {@code entered} 在真正卡住之前喊一声, {@code sends} 数被派了几次. */
    private static final class Stalling extends SseEmitter {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger sends = new AtomicInteger();

        Stalling() {
            super(Long.valueOf(Long.MAX_VALUE));
        }

        @Override public void send(SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("probe timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }

    /** 只数"收到几次心跳"的流. */
    private static final class Counter extends SseEmitter {
        private final List<String> seen = new ArrayList<String>();
        volatile int n;

        Counter() {
            super(Long.valueOf(Long.MAX_VALUE));
        }

        @Override public synchronized void send(SseEventBuilder builder) throws IOException {
            n++;
            seen.add("beat");
        }
    }
}
