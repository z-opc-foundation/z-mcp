package com.zifang.z.mcp.core.security;

import com.zifang.z.mcp.core.properties.McpProperties;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传输层安全校验 — 对应协议里三条 MUST:
 *
 * <ol>
 *   <li><b>Origin 校验</b>: 不合法直接 403, 这是浏览器侧跨站请求的闸门.</li>
 *   <li><b>Host 校验 / DNS rebinding 防护</b>: 未显式配白名单时退化为"只允许同源",
 *       即 Origin 的 host 必须与 Host 头一致 — 恶意域名解析到 127.0.0.1 打本机服务
 *       这条路径就此封死.</li>
 *   <li><b>工具调用限流</b>: 令牌桶, 按 会话/来源 IP 分桶.</li>
 * </ol>
 *
 * <p>鉴权(OAuth 2.1 resource server)是 SHOULD 而非 MUST; 这里提供 bearer 白名单作为
 * 内网场景的最低门槛, 显式留了 {@code WWW-Authenticate} 出口给后续接授权服务器.
 */
public class TransportSecurityGuard {

    private final McpProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<String, Bucket>();

    public TransportSecurityGuard(McpProperties properties) {
        this.properties = properties;
    }

    /** 一次违规. httpStatus 是传输层状态码, 与 JSON-RPC error code 分属两层. */
    public static final class Violation {
        public final int httpStatus;
        public final String message;
        public final String wwwAuthenticate;

        Violation(int httpStatus, String message, String wwwAuthenticate) {
            this.httpStatus = httpStatus;
            this.message = message;
            this.wwwAuthenticate = wwwAuthenticate;
        }
    }

    /**
     * 一次请求的完整安全闸 —— 数据面与控制面必须走同一个入口。
     *
     * <p>控制面以前是"裸"的：{@code security.bearer-tokens} 配了也只挡 {@code /mcp}，
     * {@code /mcp/admin} 照样把上游 endpoint、{@code lastError}、完整 JSON Schema 白送给
     * 任何能连上端口的人。一把尺量两扇门，"配了锁"才等于"锁上了"。
     */
    public Violation checkRequest(javax.servlet.http.HttpServletRequest request) {
        return checkRequest(trim(request.getHeader("Origin")),
                effectiveHost(request),
                trim(request.getHeader("Authorization")));
    }

    /** Host 依据：只有显式声明"我在可信反代后面"才认 {@code X-Forwarded-Host}。 */
    public String effectiveHost(javax.servlet.http.HttpServletRequest request) {
        if (properties.getSecurity().isTrustedProxy()) {
            String forwarded = trim(request.getHeader("X-Forwarded-Host"));
            if (forwarded != null) return forwarded;
        }
        return trim(request.getHeader("Host"));
    }

    private static String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /**
     * @param origin    请求 Origin 头, 可为 null
     * @param host      生效 Host(已按 trustedProxy 决定取 X-Forwarded-Host 还是 Host)
     * @param authorization Authorization 头, 可为 null
     */
    public Violation checkRequest(String origin, String host, String authorization) {
        McpProperties.Security sec = properties.getSecurity();

        if (host != null && !sec.getAllowedHosts().isEmpty()
                && !containsIgnoreCase(sec.getAllowedHosts(), host)) {
            return new Violation(400, "invalid Host header: " + host, null);
        }

        if (origin == null || origin.isEmpty()) {
            if (!sec.isAllowMissingOrigin()) {
                return new Violation(403, "missing Origin header", null);
            }
        } else if (!sec.getAllowedOrigins().isEmpty()) {
            if (!containsIgnoreCase(sec.getAllowedOrigins(), origin)) {
                return new Violation(403, "Origin not allowed: " + origin, null);
            }
        } else {
            // 空白名单 = 同源策略. 注意这是保守默认, 不是"没配就等于开放".
            if (host == null || !originHostEquals(origin, host)) {
                return new Violation(403, "cross-origin request blocked: " + origin
                        + " != " + host, null);
            }
        }

        List<String> tokens = sec.getBearerTokens();
        if (!tokens.isEmpty()) {
            String token = bearerOf(authorization);
            if (token == null || !tokens.contains(token)) {
                return new Violation(401, "missing or invalid bearer token",
                        "Bearer realm=\"z-mcp\", error=\"invalid_request\"");
            }
        }
        return null;
    }

    /**
     * 令牌桶.
     *
     * @param key 分桶键(会话 id 或来源 IP)
     * @return true 表示放行; false 表示超限(调用方须回 429)
     */
    public boolean tryAcquire(String key) {
        McpProperties.RateLimit rl = properties.getRateLimit();
        if (!rl.isEnabled() || rl.getRequestsPerMinute() <= 0) return true;
        long rate = rl.getRequestsPerMinute() + rl.getBurst();
        Bucket b = buckets.get(key);
        if (b == null) {
            b = new Bucket(rl.getBurst(), rate);
            Bucket prev = buckets.putIfAbsent(key, b);
            if (prev != null) b = prev;
        }
        return b.take(System.nanoTime());
    }

    /** 会话终止/过期时回收桶, 否则长期运行的进程里桶会随客户端数量单调增长. */
    public void forget(String key) {
        buckets.remove(key);
    }

    public int bucketCount() {
        return buckets.size();
    }

    /** 粗略回收: 超过一分钟没动过的桶. */
    public void sweepBuckets() {
        long now = System.nanoTime();
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastRefill > 60_000_000_000L) it.remove();
        }
    }

    static String bearerOf(String authorization) {
        if (authorization == null) return null;
        String a = authorization.trim();
        if (a.length() < 7 || !a.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return a.substring(7).trim();
    }

    private static boolean containsIgnoreCase(List<String> list, String v) {
        for (String s : list) if (s != null && s.equalsIgnoreCase(v)) return true;
        return false;
    }

    /** origin 形如 scheme://host[:port]; 与 Host 头比对(Host 恒为 host[:port]). */
    private static boolean originHostEquals(String origin, String host) {
        int i = origin.indexOf("://");
        String oh = i < 0 ? origin : origin.substring(i + 3);
        int slash = oh.indexOf('/');
        if (slash >= 0) oh = oh.substring(0, slash);
        return oh.equalsIgnoreCase(host);
    }

    private static final class Bucket {
        private final double capacity;
        private final double perSecond;
        private double tokens;
        private long lastRefill;

        Bucket(int burst, double ratePerMinute) {
            this.capacity = Math.max(1.0, burst);
            this.perSecond = ratePerMinute / 60.0;
            this.tokens = this.capacity;
            this.lastRefill = System.nanoTime();
        }

        synchronized boolean take(long now) {
            double elapsed = (now - lastRefill) / 1_000_000_000.0;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * perSecond);
                lastRefill = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
