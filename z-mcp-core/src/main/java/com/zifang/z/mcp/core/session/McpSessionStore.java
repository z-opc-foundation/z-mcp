package com.zifang.z.mcp.core.session;

import com.zifang.z.mcp.core.properties.McpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 会话存储 — Streamable HTTP 的 {@code Mcp-Session-Id} 生命周期.
 *
 * <p>协议约束: 会话 id 必须是可见 ASCII(0x21-0x7E)、全局唯一且**密码学安全**
 * (可猜的 id 等于会话劫持); 服务端不再认识该 id 时返回 404, 客户端据此重新 initialize.
 */
public class McpSessionStore {

    private static final Logger log = LoggerFactory.getLogger(McpSessionStore.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 开流注释帧的内容. 做成常量是因为测试按它认帧 —— 两处各写一遍字面量迟早会漂移. */
    public static final String STREAM_OPEN_COMMENT = "z-mcp stream open";

    /**
     * 心跳注释帧的内容. 注释帧不带 id 也不带 data, 因此在 SSE 客户端那里是不可见的空行 ——
     * 它唯一的作用是"让中间层看到这条连接还在说话", 所以它**不能**去占事件序号(见 {@link McpSession#sendKeepAlive()}).
     */
    public static final String KEEP_ALIVE_COMMENT = "keep-alive";

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<String, McpSession>();
    private final McpProperties properties;
    private final AtomicLong lastSweep = new AtomicLong(System.currentTimeMillis());
    private final Object keepAliveLock = new Object();
    private ScheduledExecutorService keepAliveExecutor;
    private ScheduledFuture<?> keepAliveTask;
    private volatile boolean closed;

    public McpSessionStore(McpProperties properties) {
        this.properties = properties;
    }

    /**
     * 心跳线程**只在第一条流挂上来时**才建: 绝大多数 MCP 客户端只用 POST, 一个从未开过流的
     * 服务不该因此常驻一个线程。周期取配置值, 配成 0(或负数)则永远不建, 也不会有任何心跳。
     * 由传输层在 {@code GET /mcp} 开流前调用 —— 会话对象是静态内嵌类, 拿不到这里。
     */
    public void startKeepAliveIfNeeded() {
        final long seconds = properties.getSse().getKeepAliveSeconds();
        if (seconds <= 0 || closed) {
            return;
        }
        synchronized (keepAliveLock) {
            if (keepAliveTask != null || closed) {
                return;
            }
            if (keepAliveExecutor == null) {
                keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "z-mcp-sse-keepalive-" + seconds + "s");
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            keepAliveTask = keepAliveExecutor.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() {
                    try {
                        tick();
                    } catch (Throwable e) {
                        // scheduleWithFixedDelay 一遇异常就永久停摆: 一次抛出去就等于所有会话从此没有心跳
                        log.warn("keep-alive tick failed", e);
                    }
                }
            }, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    /** 给每条挂着流的会话各发一个心跳注释帧. @return 收到心跳的流数, 便于测试与日志按它认账. */
    int tick() {
        int streams = 0;
        for (McpSession s : new ArrayList<McpSession>(sessions.values())) {
            streams += s.sendKeepAlive();
        }
        return streams;
    }

    /** 容器关闭时收掉心跳线程(不关的话一个 daemon 线程会跟着 JVM 走, 但会话多起来时会拖在中间层之后). */
    public void close() {
        closed = true;
        synchronized (keepAliveLock) {
            if (keepAliveTask != null) {
                keepAliveTask.cancel(false);
                keepAliveTask = null;
            }
            if (keepAliveExecutor != null) {
                keepAliveExecutor.shutdownNow();
                keepAliveExecutor = null;
            }
        }
    }


    /** 签发一个新会话 id: 18 字节 SecureRandom → base64url(无 padding). */
    public String newSessionId() {
        byte[] buf = new byte[18];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public McpSession create(String id, String protocolVersion) {
        sweepIfNeeded();
        int max = properties.getSession().getMaxSessions();
        if (max > 0 && sessions.size() >= max) {
            // 到上限先清过期, 仍满则拒绝新会话而不是无声丢弃已有会话
            sweep(true);
            if (sessions.size() >= max) {
                log.warn("session cap reached ({}), rejecting new session", max);
                return null;
            }
        }
        McpSession s = new McpSession(id, protocolVersion, clockNow(),
                properties.getSse().getBufferSize());
        sessions.put(id, s);
        return s;
    }

    /** @return 会话, 不存在/已过期返回 null(调用方须回 404) */
    public McpSession find(String id) {
        if (id == null) return null;
        McpSession s = sessions.get(id);
        if (s == null) return null;
        if (isExpired(s)) {
            terminate(id);
            return null;
        }
        s.touch(clockNow());
        return s;
    }

    public void terminate(String id) {
        McpSession s = sessions.remove(id);
        if (s != null) s.closeEmitters();
    }

    public int count() {
        return sessions.size();
    }

    public List<McpSession> snapshot() {
        return Collections.unmodifiableList(new ArrayList<McpSession>(sessions.values()));
    }

    private boolean isExpired(McpSession s) {
        int ttl = properties.getSession().getTtlSeconds();
        if (ttl <= 0) return false;
        return clockNow() - s.getLastAccess() > ttl * 1000L;
    }

    private void sweepIfNeeded() {
        int ttl = properties.getSession().getTtlSeconds();
        if (ttl <= 0) return;
        long now = clockNow();
        long prev = lastSweep.get();
        if (now - prev < Math.min(ttl * 1000L, 60000L)) return;
        if (lastSweep.compareAndSet(prev, now)) sweep(false);
    }

    private void sweep(boolean force) {
        long now = clockNow();
        int ttl = properties.getSession().getTtlSeconds();
        Iterator<Map.Entry<String, McpSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            McpSession s = it.next().getValue();
            if (force || (ttl > 0 && now - s.getLastAccess() > ttl * 1000L)) {
                it.remove();
                s.closeEmitters();
            }
        }
    }

    long clockNow() {
        return System.currentTimeMillis();
    }

    /** 一个 MCP 会话. */
    public static final class McpSession {

        /** 客户端没调过 {@code logging/setLevel} 时的门槛. 协议未规定默认值, 这里取 info. */
        public static final String DEFAULT_LOG_LEVEL = "info";

        private final String id;
        private final long createdAt;
        private final List<SseEmitter> emitters =
                Collections.synchronizedList(new ArrayList<SseEmitter>());
        private final AtomicLong seq = new AtomicLong();
        /**
         * 在飞请求表: 规范化后的 JSON-RPC id → 这一次正在跑的工具调用.
         *
         * <p>它按会话各留一张是刻意的: 请求 id 是客户端自己挑的小整数, 全局一张表就等于任何会话
         * 都能拿一个 {@code requestId: 1} 停掉别的会话正在跑的工具。
         */
        private final Map<String, InFlight> inFlight = new ConcurrentHashMap<String, InFlight>();

        /**
         * 最近发过的事件, 按 id 递增. {@code Last-Event-ID} 续传时从这里补发.
         *
         * <p>只在 {@code synchronized (emitters)} 里读写(和取号同一把锁), 否则"这条事件算不算
         * 已经发给你了"就没有一致的答案。
         */
        private final ArrayDeque<StreamEvent> buffered = new ArrayDeque<StreamEvent>();
        private final int bufferSize;
        private volatile long lastAccess;
        private volatile boolean initialized;
        private volatile String protocolVersion;
        private volatile String logLevel = DEFAULT_LOG_LEVEL;
        private volatile Map<String, Object> clientInfo;

        McpSession(String id, String protocolVersion, long now, int bufferSize) {
            this.id = id;
            this.protocolVersion = protocolVersion;
            this.createdAt = now;
            this.lastAccess = now;
            this.bufferSize = bufferSize;
        }

        public String getId() { return id; }

        public long getCreatedAt() { return createdAt; }

        public long getLastAccess() { return lastAccess; }

        void touch(long now) { this.lastAccess = now; }

        public boolean isInitialized() { return initialized; }

        public void markInitialized() { this.initialized = true; }

        public String getProtocolVersion() { return protocolVersion; }

        public void setProtocolVersion(String v) { this.protocolVersion = v; }

        public String getLogLevel() { return logLevel; }

        public void setLogLevel(String l) { this.logLevel = l; }

        public Map<String, Object> getClientInfo() { return clientInfo; }

        public void setClientInfo(Map<String, Object> info) { this.clientInfo = info; }

        /** SSE 事件 id 单调递增, 供 Last-Event-ID 续传定位. */
        public long nextSeq() { return seq.incrementAndGet(); }

        /** 只挂流, 不补发 —— 传输层开流走 {@link #attach}; 这里给的是低层原语(测试与非 HTTP 场景). */
        public void addEmitter(SseEmitter e) { emitters.add(e); }

        public void removeEmitter(SseEmitter e) { emitters.remove(e); }

        public int emitterCount() { return emitters.size(); }

        /** 缓冲里现存的事件条数. 仅测试用: 这条是内存上界的唯一量具. */
        public int bufferedCount() {
            synchronized (emitters) { return buffered.size(); }
        }

        // ------------------------------------------------------------ 在飞请求

        /**
         * 登记一次正在跑的请求, 返回的那颗 handle 交给跑请求的线程, 它用完要还回来。
         * 先登记再提交任务: 取消通知完全可能比工具真正开跑更早到达(同一个会话上客户端可以并发
         * 发这两条 POST), 反过来——等 submit 完再登记——那条取消就会打空。
         */
        public InFlight beginRequest(String key) {
            InFlight in = new InFlight();
            inFlight.put(key, in);
            return in;
        }

        /**
         * @return {@code true} = 这次取消命中了本会话一条在飞的请求; {@code false} = 本会话没有
         *         这个 id 在飞(已经答完了、从来没有过、或者那是别的会话的 id)——协议要求这种 race
         *         必须被"优雅地"处理, 所以打空不是错误, 也不该回任何响应。
         */
        public boolean cancelRequest(String key, String reason) {
            InFlight in = inFlight.get(key);
            return in != null && in.cancel(reason);
        }

        /** 只摘自己那一次: 同 id 撞车时, 后跑完的那条不能把前一条的登记抹掉。 */
        public void endRequest(String key, InFlight mine) {
            inFlight.remove(key, mine);
        }

        /** 现存在飞请求数. 仅测试用: 它是"登记/注销有没有漏"的唯一量具。 */
        public int inFlightCount() {
            return inFlight.size();
        }

        /**
         * 一次在飞的请求. 状态做成一个小对象而不是直接存 Future, 因为"已取消"这件事可能发生在
         * {@code Future} 还不存在的时候(取消比 submit 早), 也可能发生在工具根本没用线程池跑的时候
         * ({@code tool-timeout-seconds=0} ⇒ 直接在调用线程上跑, 没人能中断它)。
         */
        public static final class InFlight {

            private Future<?> future;
            private boolean cancelled;
            private String reason;

            public void attach(Future<?> f) {
                synchronized (this) {
                    future = f;
                    if (cancelled && f != null) f.cancel(true);
                }
            }

            synchronized boolean cancel(String why) {
                if (cancelled) return false;                    // 第二次取消不重做中断
                cancelled = true;
                reason = why;
                if (future != null) future.cancel(true);
                return true;
            }

            /** 工具跑完了才发现中途被取消过 —— 这条请求不该再给出一份"成功"结果. */
            public synchronized boolean isCancelled() { return cancelled; }

            public synchronized String reason() { return reason; }
        }

        /** 向所有已建立的 SSE 流推一条通知. */
        public void emit(String json) {
            List<SseEmitter> copy;
            // 一条通知一个 id: 读 seq.get() 会让流上每一个事件的 id 都停在同一个数字,
            // 客户端按 id 去重就会把后来的通知当成重放的旧事件丢掉.
            long eventId;
            synchronized (emitters) {
                // 取号、入缓冲、摘 emitter 快照必须是同一步: 续传是按"缓冲里有的才补得上"来推的,
                // 一旦入缓冲和摘快照之间能被 attach 插进去, 这条事件就既不在补发里也不在实发里 —— 丢了。
                eventId = nextSeq();
                if (bufferSize > 0) {
                    buffered.addLast(new StreamEvent(eventId, json));
                    while (buffered.size() > bufferSize) buffered.removeFirst();
                }
                copy = new ArrayList<SseEmitter>(emitters);
            }
            // 写出刻意放在锁**外面**: SseEmitter.send 会走阻塞式 socket 写, 一个不再读的连接
            // 能把同会话里所有别的流(以及触发这次广播的那次工具注册)拖住几十秒。
            String id = String.valueOf(eventId);
            for (SseEmitter e : copy) {
                try {
                    e.send(SseEmitter.event().id(id).data(json));
                } catch (Exception ex) {
                    emitters.remove(e);
                    // 摘掉名单不等于收掉那条请求: Spring 的 send() 在写失败时只置 sendFailed 并把异常
                    // 原样抛回来, 异步请求仍挂在容器上直到 sse.timeout-ms 才走 onTimeout。
                    // 一次客户端跑掉的广播因此会留下一个"再没人写、但要几十秒才闭合"的异步上下文,
                    // 而容器是在它终于回收时才把这条请求的资源还给池子的。
                    try {
                        e.complete();
                    } catch (Exception ignore) {
                        // 对端已经不在了, 闭合本身不会再成功
                    }
                }
            }
        }

        /**
         * 开一条服务端流: 原子地补发 {@code lastEventId} 之后仍留着的事件, 再发一条 priming 事件,
         * 最后把 emitter 挂上.
         *
         * <p>priming 是协议(2025-11-25)点名的 SHOULD: 一条只有 event id、data 为空的事件,
         * 让还没收到任何通知的客户端立刻拿到一个可续传的坐标; 顺带把 {@code retry} 字段带下去
         * (客户端对它是 MUST)。它的 id 在补发之后才取, 所以流上的 id 严格递增。
         *
         * <p>补发与挂载同锁: 期间新推的事件要么已进缓冲(于是这条流补得到)、要么在挂载之后才发
         * (于是走实发), 不存在两边都不沾的窗口。写出走的是 SseEmitter 尚未初始化时的内存早发队列
         * (响应体还没开始写), 所以持锁写不会阻塞别的会话。
         *
         * @param lastEventId 客户端 {@code Last-Event-ID}; {@code null} = 不开续传, 只起一条新流
         * @param comment     开流注释帧的内容, 便于人在 curl 里认出这是本服务的流
         * @param retryMs     {@code retry} 字段的毫秒数; {@code <= 0} 则不带该字段
         * @return 补发的事件条数; {@code -1} 表示要的位置已经不在缓冲里 —— 此时一条都没发出去,
         *         调用方须回 400 让客户端退回"不续传"的用法
         */
        public int attach(SseEmitter emitter, Long lastEventId, String comment, long retryMs)
                throws IOException {
            synchronized (emitters) {
                List<StreamEvent> replay = Collections.emptyList();
                if (lastEventId != null) {
                    long from = lastEventId.longValue();
                    if (from < seq.get()) {
                        // 客户端落在我们发过的位置之后(或恰好追平)才不需要补发; 否则就要看缓冲还留没留下
                        StreamEvent first = buffered.peekFirst();
                        if (first == null || first.id > from + 1) return -1;
                        replay = new ArrayList<StreamEvent>();
                        for (StreamEvent e : buffered) {
                            if (e.id > from) replay.add(e);
                        }
                    }
                }
                for (StreamEvent e : replay) {
                    emitter.send(SseEmitter.event().id(String.valueOf(e.id)).data(e.json));
                }
                SseEmitter.SseEventBuilder prime = SseEmitter.event().comment(comment);
                if (retryMs > 0) prime.reconnectTime(retryMs);
                // 空 data 是协议点名的 priming 形状: 只给一个可续传的坐标, 不是一条消息
                prime.id(String.valueOf(nextSeq())).data("");
                emitter.send(prime);
                emitters.add(emitter);
                return replay.size();
            }
        }

        /** 发过的一条事件: id + 原样可重放的 JSON 体. */
        private static final class StreamEvent {
            final long id;
            final String json;

            StreamEvent(long id, String json) {
                this.id = id;
                this.json = json;
            }
        }

        /**
         * 往这条会话的每条流上写一个心跳注释帧.
         *
         * <p>刻意**不取事件序号、也不入缓冲**: 心跳既不是一个通知, 也就不能占掉客户端用来续传的坐标 ——
         * 否则一个安静的流会被自己的心跳推到很高的 id, 而那段 id 区间里什么都不在缓冲里,
         * 客户端按 {@code Last-Event-ID} 回来只会撞到一个没意义的 400。
         * 写出同样放在会话锁外(与 {@link #emit} 同一个理由)。
         *
         * @return 成功写出的流数
         */
        int sendKeepAlive() {
            List<SseEmitter> copy;
            synchronized (emitters) { copy = new ArrayList<SseEmitter>(emitters); }
            int sent = 0;
            for (SseEmitter e : copy) {
                try {
                    e.send(SseEmitter.event().comment(KEEP_ALIVE_COMMENT));
                    sent++;
                } catch (Exception ex) {
                    emitters.remove(e);
                    // 与 {@link #emit} 同一件事: 光把它从名单里摘掉, 那条异步请求要一直挂到容器超时.
                    try {
                        e.complete();
                    } catch (Exception ignore) {
                        // 对端已经不在了
                    }
                }
            }
            return sent;
        }

        void closeEmitters() {
            List<SseEmitter> copy;
            synchronized (emitters) { copy = new ArrayList<SseEmitter>(emitters); emitters.clear(); }
            for (SseEmitter e : copy) {
                try {
                    e.complete();
                } catch (Exception ignore) {
                    // 客户端已断开
                }
            }
        }
    }
}
