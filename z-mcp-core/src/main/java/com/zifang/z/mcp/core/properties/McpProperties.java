package com.zifang.z.mcp.core.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * z.mcp.* 配置.
 *
 * <p>0.1.x 里 basePath / exposeAdmin / healthCheckIntervalSeconds / servers 全部无人读取,
 * 是死配置; 0.2.0 起每一项都对应一个真实行为, 缺省值按"协议合规优先"选取.
 */
@ConfigurationProperties(prefix = "z.mcp")
public class McpProperties {

    /** 总开关(z-opc 内与遗留 z-agent-mcp-* 共存, 故默认关). */
    private boolean enabled = false;

    /** 网关路径前缀, 拼在 /mcp 之前; 支持 z-opc 反向代理改前缀. */
    private String basePath = "";

    /** 是否暴露 /mcp/admin/* 控制面. */
    private boolean exposeAdmin = false;

    /** server 健康检查周期(秒), 0 = 禁用. */
    private int healthCheckIntervalSeconds = 60;

    // ---- serverInfo ----
    private String serverName = "z-mcp";
    private String serverTitle = "z-opc MCP registry";
    private String serverVersion = "0.2.0";

    /** initialize 返回给客户端的服务级提示词(会进入 LLM 上下文). */
    private String instructions;

    /** 是否在 initialize 之前拒掉除 ping 外的请求(协议 SHOULD 由客户端保证, 此处服务端兜底). */
    private boolean requireInitialized = true;

    /** 工具参数是否按 inputSchema 校验. 校验失败按 2025-11-25 归为执行错误(isError). */
    private boolean validateArguments = true;

    /** 工具名是否强制 [A-Za-z0-9_.-]{1,128}. */
    private boolean strictToolNames = true;

    /**
     * 是否注册内置工具(echo / get_time / generate_uuid / system_info)与三条自省资源.
     *
     * <p>{@code z-mcp-server} 的叶子服务这一项要关: 一个只提供编解码工具的服务被聚合进 hub 之后,
     * 它的 {@code echo} 会撞上 hub 自己的 {@code echo} 而变成 {@code codec__echo} ——
     * 目录里躺着四份"回显输入文本", 客户端只会挑到错的那一份.
     *
     * <p>关掉之后 {@code resources/list} 是空表(不是错误): 那三条资源描述的是注册中心自己,
     * 叶子服务没有中心可描述.
     */
    private boolean builtinToolsEnabled = true;

    /** list 类方法每页条数, 0 = 不分页(一次给全). */
    private int pageSize = 0;

    /** 单个工具调用超时(秒), 0 = 不限. */
    private int toolTimeoutSeconds = 60;

    private Session session = new Session();
    private Sse sse = new Sse();
    private Security security = new Security();
    private RateLimit rateLimit = new RateLimit();
    private List<ServerConfig> servers = new ArrayList<ServerConfig>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath == null ? "" : basePath; }

    public boolean isExposeAdmin() { return exposeAdmin; }
    public void setExposeAdmin(boolean exposeAdmin) { this.exposeAdmin = exposeAdmin; }

    public int getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
    public void setHealthCheckIntervalSeconds(int s) { this.healthCheckIntervalSeconds = s; }

    public String getServerName() { return serverName; }
    public void setServerName(String n) { this.serverName = n; }

    public String getServerTitle() { return serverTitle; }
    public void setServerTitle(String t) { this.serverTitle = t; }

    public String getServerVersion() { return serverVersion; }
    public void setServerVersion(String v) { this.serverVersion = v; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String i) { this.instructions = i; }

    public boolean isRequireInitialized() { return requireInitialized; }
    public void setRequireInitialized(boolean b) { this.requireInitialized = b; }

    public boolean isValidateArguments() { return validateArguments; }
    public void setValidateArguments(boolean b) { this.validateArguments = b; }

    public boolean isStrictToolNames() { return strictToolNames; }
    public void setStrictToolNames(boolean b) { this.strictToolNames = b; }

    public boolean isBuiltinToolsEnabled() { return builtinToolsEnabled; }
    public void setBuiltinToolsEnabled(boolean b) { this.builtinToolsEnabled = b; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int n) { this.pageSize = n; }

    public int getToolTimeoutSeconds() { return toolTimeoutSeconds; }
    public void setToolTimeoutSeconds(int s) { this.toolTimeoutSeconds = s; }

    public Session getSession() { return session; }
    public void setSession(Session s) { this.session = s == null ? new Session() : s; }

    public Sse getSse() { return sse; }
    public void setSse(Sse s) { this.sse = s == null ? new Sse() : s; }

    public Security getSecurity() { return security; }
    public void setSecurity(Security s) { this.security = s == null ? new Security() : s; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit r) { this.rateLimit = r == null ? new RateLimit() : r; }

    public List<ServerConfig> getServers() { return servers; }
    public void setServers(List<ServerConfig> servers) {
        this.servers = servers == null ? new ArrayList<ServerConfig>() : servers;
    }

    // ------------------------------------------------------------------

    public static class Session {
        /** 是否签发 Mcp-Session-Id. false = 无状态模式(每请求独立). */
        private boolean enabled = true;
        private int ttlSeconds = 3600;
        private int maxSessions = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int s) { this.ttlSeconds = s; }
        public int getMaxSessions() { return maxSessions; }
        public void setMaxSessions(int n) { this.maxSessions = n; }
    }

    public static class Sse {
        /** 是否支持 GET /mcp 服务端主动流(不支持则按协议返回 405). */
        private boolean enabled = true;
        private long timeoutMs = 300000L;
        /**
         * 断线续传缓冲: 每个会话留住最近多少条已发事件, 供 {@code Last-Event-ID} 补发.
         *
         * <p>0 = 关掉缓冲, 任何续传请求都回 400(即本功能之前的行为)。默认 256 的依据是:
         * 一条通知的 JSON 一两百字节, 会话级 256 条约几十 KB —— 而 SSE 在反代空闲超时、
         * 发布、笔记本休眠下断流是常态, 连最近这几十条都补不回来, 等于把 list_changed 静默丢掉。
         */
        private int bufferSize = 256;
        /**
         * 下发给客户端的 SSE {@code retry} 字段(毫秒), 即"流断了隔多久重来"; 0 = 不带该字段。
         * 协议对这一项是客户端 MUST 遵守, 所以只有服务端配了它才真的生效。
         */
        private long retryMs = 3000L;
        /**
         * 心跳周期(秒): 流安静时每这么多秒往每条流上写一个 SSE 注释帧({@code :keep-alive});
         * 0 = 不发. 协议没有管这一项, 但生产上它是"推送到底有没有生效"的分水岭 ——
         * 云上/反代后的空闲超时普遍在 60~100 秒(Fly.io 75s、Cloudflare 100s 是被报出来的真实数字),
         * 一个半天没有通知的流会先被中间层掐掉, 而客户端只看到"再也没收到过".
         * 取 30 秒是为了压过最常见的 60 秒那道闸.
         */
        private long keepAliveSeconds = 30L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long ms) { this.timeoutMs = ms; }
        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int n) { this.bufferSize = n; }
        public long getRetryMs() { return retryMs; }
        public void setRetryMs(long ms) { this.retryMs = ms; }
        public long getKeepAliveSeconds() { return keepAliveSeconds; }
        public void setKeepAliveSeconds(long s) { this.keepAliveSeconds = s; }
    }

    public static class Security {
        /** 允许的 Origin; 空 = 只允许与 Host 同源(防 DNS rebinding). */
        private List<String> allowedOrigins = new ArrayList<String>();
        /** 无 Origin 头的请求(非浏览器客户端)是否放行. */
        private boolean allowMissingOrigin = true;
        /** 允许的 Host; 空 = 不校验. 生产上应收敛为显式白名单. */
        private List<String> allowedHosts = new ArrayList<String>();
        /** Bearer token 白名单; 空 = 关闭鉴权(仅限本机/内网可信网络). */
        private List<String> bearerTokens = new ArrayList<String>();
        /** 取 X-Forwarded-Host 作为 Host 依据(仅当部署在可信反代后). */
        private boolean trustedProxy = false;

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> l) {
            this.allowedOrigins = l == null ? new ArrayList<String>() : l;
        }
        public boolean isAllowMissingOrigin() { return allowMissingOrigin; }
        public void setAllowMissingOrigin(boolean b) { this.allowMissingOrigin = b; }
        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> l) {
            this.allowedHosts = l == null ? new ArrayList<String>() : l;
        }
        public List<String> getBearerTokens() { return bearerTokens; }
        public void setBearerTokens(List<String> l) {
            this.bearerTokens = l == null ? new ArrayList<String>() : l;
        }
        public boolean isTrustedProxy() { return trustedProxy; }
        public void setTrustedProxy(boolean b) { this.trustedProxy = b; }
    }

    public static class RateLimit {
        /** 协议要求服务端 MUST 对工具调用限流; 默认开. */
        private boolean enabled = true;
        private int requestsPerMinute = 300;
        private int burst = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int n) { this.requestsPerMinute = n; }
        public int getBurst() { return burst; }
        public void setBurst(int n) { this.burst = n; }
    }

    public static class ServerConfig {
        private String name;
        private String endpoint;
        /** http(=Streamable HTTP) / stdio; 遗留值 http+sse 会被拒绝, 因为该传输已废弃. */
        private String transport = "http";
        private Map<String, String> headers = new LinkedHashMap<String, String>();
        /** stdio 传输的可执行文件. */
        private String command;
        private List<String> args = new ArrayList<String>();
        private boolean enabled = true;
        /** 连上游的超时(毫秒). 没有超时的客户端等于把本机的线程交给别人. */
        private int connectTimeoutMillis = 5_000;
        /** 读上游响应的超时(毫秒), 覆盖 initialize/tools/list/tools/call. */
        private int readTimeoutMillis = 60_000;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> h) {
            this.headers = h == null ? new LinkedHashMap<String, String>() : h;
        }
        public String getCommand() { return command; }
        public void setCommand(String c) { this.command = c; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> a) { this.args = a == null ? new ArrayList<String>() : a; }
        public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public void setConnectTimeoutMillis(int ms) { this.connectTimeoutMillis = ms; }
        public int getReadTimeoutMillis() { return readTimeoutMillis; }
        public void setReadTimeoutMillis(int ms) { this.readTimeoutMillis = ms; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
    }
}
