package com.zifang.z.mcp.core.session;

import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        /**
         * 一帧一个写出单元.
         *
         * <p>不用 {@code SseEmitter.event()} 那一族 builder: 它把一帧拆成三个写出片段
         * ({@code id:N\ndata:} 前缀 / JSON 体 / 结尾换行), 每个片段各 flush 一次, 于是**帧内**留下两道缝。
         * 这台仓里有闸直接读边界({@code ZMcpSseFrameAtomicityTest}, 原始 socket 上按 HTTP chunk 数写出单元):
         * 改之前一条 {@code list_changed} 事件是 3 个写出单元, 而 CI 上那条 {@code Premature EOF} 每次红,
         * 客户端手里都是恰好第一个写出单元的内容
         * ({@code 读到: id:2}) —— 容器在缝里把连接收走了, 半截帧就到了客户端手上。自己攒整帧交出去之后
         * **帧内没有 flush 缝**: 我们这一侧每帧只交一段字节。
         *
         * <p>但这**不是**"整帧原子送达"的保证, 而且是量出来的: run {@code 36336993342}(与打印
         * {@code torn=0} 那一跑同一枚行为字节)的 jdk17 格照样 {@code rounds=5 torn=1 lost=0} —— 一次
         * flush 自己就能被容器切在中间, 那一半我们控制不了。所以这里守得住的不变量只有一条窄的:
         * 客户端的位置永远停在**它读得完的那一帧**上(SSE 只在空行处提交事件, 没读全的一帧不会推进它的
         * 位置, 于是按 {@code Last-Event-ID} 续传不会续到错的地方)。缝少了意味着"能留下半个事件的位置"
         * 变少了, 不意味着"能切开的位置"没了 —— 后者由 {@code ZMcpSseAbandonResumeStressTest} 断言
         * "切开不许丢事件"({@code lost=0}), 那一半才是承诺。
         *
         * <p>字节与拆帧写法逐字相同: {@link WholeFrame} 只改"几个写出单元", 不改一个字节, 也不改
         * 载荷走的那个转换器与字符集(见 {@link WholeFrame} 里 mediaType 那一句)。
         */
        private static SseEmitter.SseEventBuilder eventFrame(String id, String json) {
            return new WholeFrame("id:" + id + "\ndata:" + json + "\n\n");
        }

        /**
         * 开流帧: 注释 + 可选 {@code retry} + 可选 priming(只给坐标、不给消息的空 data 事件), 以空行收尾。
         *
         * <p>注释帧与 priming 合在**同一个写出单元**里不是为了少写一次, 而是为了字节与旧写法逐字相同 ——
         * 旧写法把注释/retry/id/data 前缀攒在同一段文本里, 只有结尾换行自成一片(实测旧的是 2 片,
         * 拼起来 {@code :注释\nretry:3000\nid:1\ndata:\n\n}), 拆成两次写出会在中间多出一个空行,
         * 而多出来的空行会把 priming 变成"另一帧"。
         * {@code primeId == null}(客户端版本读不动空 data, 或不配 retry)时相应字段就不出现。
         */
        private static SseEmitter.SseEventBuilder openFrame(String comment, long retryMs, String primeId) {
            StringBuilder frame = new StringBuilder(":").append(comment).append('\n');
            if (retryMs > 0) frame.append("retry:").append(retryMs).append('\n');
            if (primeId != null) frame.append("id:").append(primeId).append("\ndata:\n");
            return new WholeFrame(frame.append('\n').toString());
        }

        /** 纯注释帧(心跳): 不带 id 也不带 data, 所以它不能占事件序号. */
        private static SseEmitter.SseEventBuilder commentFrame(String comment) {
            return new WholeFrame(":" + comment + "\n\n");
        }

        /**
         * 只含一个写出片段的 {@code SseEventBuilder}: 把一整帧作为一次 {@code send} 交出去.
         *
         * <p>为什么要有这么个东西 —— {@code SseEmitter} 没有"把这段字节原样写一次"的口子:
         * <ul>
         *   <li>{@code send(Object)} 看着像, 但它不是: 字节码里它是 {@code event().data(object, mt)}
         *       的语法糖, 于是整帧会被塞进一个 {@code data:} 字段。实测(把整帧交给 {@code send(String)})
         *       上线的是 {@code data:id:1\ndata:{...}\n\n\n\n} —— event id 这个字段消失了(续传坐标没了),
         *       而载荷成了 {@code id:1\n{...}}, 任何按 JSON 解析的客户端都会当场报错。这条改法是本机
         *       {@code ZMcpSseFrameAtomicityTest} 的第一批红抓出来的, 不是推演。</li>
         *   <li>{@code send(SseEventBuilder)} 走的是 {@code invokespecial ResponseBodyEmitter.send},
         *       也就是**绕过**上面那个包装, 逐片原样写出。所以"一帧一片"是它能表达的形状, 而
         *       {@code event()} 造不出这一片(它的 {@code data()} 必然把前缀与载荷分成两片)。</li>
         * </ul>
         *
         * <p>mediaType 取 {@code null} —— 与 {@code event().data(json)} 交给转换器的那一枚是同一个值
         * (实测 {@code data(Object)} 就是 {@code data(Object, null)}), 于是载荷的转换器选择与字符集
         * 逐字不变: 改的只有"几次写出", 不是"写出的字节"。取 null 是"与库自己的取值一致", 不是"保住
         * 编码" —— 实测把它换成 {@code TEXT_PLAIN}, 线上的字节、chunk 边界与响应的 Content-Type 三样
         * 全都不动(368 条一条不红), 所以这一处没有尺能钉住, 别把它当成有守卫的不变量。
         *
         * <p>那六个 fluent 方法一律拒: 这个类的全部用途是"承载一段已经攒好的帧", 允许调用方在它上面
         * 继续 {@code id()}/{@code data()} 只会重新攒出缝来。
         */
        private static final class WholeFrame implements SseEmitter.SseEventBuilder {

            private final Set<ResponseBodyEmitter.DataWithMediaType> parts;

            WholeFrame(String frame) {
                this.parts = new LinkedHashSet<ResponseBodyEmitter.DataWithMediaType>(1);
                this.parts.add(new ResponseBodyEmitter.DataWithMediaType(frame, null));
            }

            @Override
            public Set<ResponseBodyEmitter.DataWithMediaType> build() { return parts; }

            @Override public SseEmitter.SseEventBuilder id(String id) { throw readOnly(); }
            @Override public SseEmitter.SseEventBuilder name(String name) { throw readOnly(); }
            @Override public SseEmitter.SseEventBuilder reconnectTime(long ms) { throw readOnly(); }
            @Override public SseEmitter.SseEventBuilder comment(String comment) { throw readOnly(); }
            @Override public SseEmitter.SseEventBuilder data(Object object) { throw readOnly(); }
            @Override public SseEmitter.SseEventBuilder data(Object object, MediaType mt) { throw readOnly(); }

            private static UnsupportedOperationException readOnly() {
                return new UnsupportedOperationException(
                        "WholeFrame 只承载已经攒好的帧: 在它上面继续拼字段等于把缝重新留在帧里");
            }
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
                    e.send(eventFrame(id, json));
                } catch (Exception ex) {
                    emitters.remove(e);
                    // 摘掉名单不等于收掉那条请求: Spring 的 send() 在写失败时只置 sendFailed 并把异常
                    // 原样抛回来, 异步请求仍挂在容器上直到 sse.timeout-ms 才走 onTimeout。
                    // 一次客户端跑掉的广播因此会留下一个"再没人写、但要几十秒才闭合"的异步上下文,
                    // 而容器是在它终于回收时才把这条请求的资源还给池子的。
                    // (别换成 completeWithError(ex): 实测对 CI 上那条 Premature EOF 的复现率没有影响
                    //  —— 换过去是 jdk8 1/5、jdk17 2/5, 与本写法同; 本机两种写法各 200 轮 0 次切开、
                    //  0 行 ERROR。台账见 README「构建与测试」。)
                    log.warn("session {} dropped an SSE stream after a send failure: {}", getId(),
                            ex.toString());
                    try {
                        e.complete();
                    } catch (Exception ignore) {
                        // 对端已经不在了, 闭合本身不会再成功
                    }
                }
            }
        }

        /**
         * 开一条服务端流: 原子地补发 {@code lastEventId} 之后仍留着的事件, 再发一条开流帧
         * (够格的版本还带上 priming), 最后把 emitter 挂上.
         *
         * <p>priming 是协议(2025-11-25)点名的 SHOULD: 一条只有 event id、data 为空的事件,
         * 让还没收到任何通知的客户端立刻拿到一个可续传的坐标; 顺带把 {@code retry} 字段带下去
         * (客户端对它是 MUST)。它的 id 在补发之后才取, 所以流上的 id 严格递增。
         *
         * <p>空 data 只发给 {@link McpSchema#SSE_PRIMING_SINCE} 及之后的版本 —— 更早的客户端
         * 会把它当成一条 JSON-RPC 消息去 parse。开流注释帧与 {@code retry} 不受闸约束, 一条老版本
         * 的流照样拿得到, 只是没有那个可续传的坐标(它本来也就续不了)。
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
                    emitter.send(eventFrame(String.valueOf(e.id), e.json));
                }
                // 空 data 是协议点名的 priming 形状: 只给一个可续传的坐标, 不是一条消息。
                // 但只有 >= 2025-11-25 的客户端读得动它 —— 更早的实现会把空 data 当成一条
                // JSON-RPC 消息去 parse。注释帧与 retry 照发: 它们是标准 SSE 字段, 老客户端
                // 最多忽略注释, 不会碰 JSON。
                // 取号在闸**里面**: 一个从不发出去的 id 会在序列上留个洞, 而流上的 id
                // 唯一的含义就是"客户端读到过它"
                String primeId = McpSchema.supportsEmptySseData(protocolVersion)
                        ? String.valueOf(nextSeq())
                        : null;
                emitter.send(openFrame(comment, retryMs, primeId));
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
                    e.send(commentFrame(KEEP_ALIVE_COMMENT));
                    sent++;
                } catch (Exception ex) {
                    emitters.remove(e);
                    // 与 {@link #emit} 同一件事: 光把它从名单里摘掉, 那条异步请求要一直挂到容器超时.
                    log.warn("session {} dropped an SSE stream during keep-alive: {}", getId(),
                            ex.toString());
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
            if (!copy.isEmpty()) {
                // 服务端主动收尾, 在客户端那一侧只表现为"流提前结束了", 与"网络把流掐了"是同一种症状。
                // 不留这一行, 排障的人就没法把"会话被终止/被回收"和"中间层掉了"分开(实测: 终止会话
                // 之后客户端 readLine 直接读到 null, 而当时服务端一个字都没说)。
                log.info("session {} closed {} SSE stream(s)", id, Integer.valueOf(copy.size()));
            }
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
