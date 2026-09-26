package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.McpServerDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 外部 MCP server 接入器 — 让 {@code z.mcp.servers[]} 与 {@code healthCheckIntervalSeconds}
 * 真的产生行为.
 *
 * <p>0.1.x 里这两个配置项没有任何读取方, 所以"多 server 聚合"只是 README 上的说法.
 * 现在一个 server 的生命周期是:
 * <ol>
 *   <li>initialize 握手(拿会话与版本)</li>
 *   <li>tools/list 翻页拉全目录</li>
 *   <li>逐个注册进 {@link McpRegistry}(撞名由 registry 加 {@code server__} 前缀)</li>
 *   <li>周期性 healthCheck: 连不上的 server 会**摘掉**它的工具 —— 广告一个调不动的工具
 *       比不广告更糟, 客户端只会拿到一次失败</li>
 * </ol>
 *
 * <p>单个 server 失败绝不影响其它 server 或本机内置工具: 异常一律记成该 server 的状态.
 */
public class ExternalServerManager {

    private static final Logger log = LoggerFactory.getLogger(ExternalServerManager.class);

    /** 怎么把一条配置变成一个传输 — 测试在这里换掉真网络. */
    public interface ExchangeFactory {
        JsonRpcExchange create(McpProperties.ServerConfig config) throws IOException;
    }

    private final McpRegistry registry;
    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final ExchangeFactory factory;
    private final Map<String, Managed> managed = new LinkedHashMap<String, Managed>();
    private ScheduledExecutorService scheduler;
    /**
     * {@link #destroy()} 是终点, 不是"暂时停一下". 首轮同步跑在守护线程里, 它完全可能在
     * 容器已经关闭之后才抢到这把锁 —— 而 {@code scheduler = null} 会把
     * {@link #startSchedulerIfNeeded()} 的门重新打开, 于是**已经死了的 bean** 会再起一个
     * 没人负责关掉的周期线程(同 JVM 里每关启一次就漏一条)。
     */
    private boolean destroyed;

    public ExternalServerManager(McpRegistry registry, McpProperties properties, ObjectMapper mapper) {
        this(registry, properties, mapper, defaultFactory(properties, mapper));
    }

    public ExternalServerManager(McpRegistry registry, McpProperties properties, ObjectMapper mapper,
                                 ExchangeFactory factory) {
        this.registry = registry;
        this.properties = properties;
        this.mapper = mapper;
        this.factory = factory;
    }

    public static ExchangeFactory defaultFactory(final McpProperties properties,
                                                 final ObjectMapper mapper) {
        return new ExchangeFactory() {
            @Override
            public JsonRpcExchange create(McpProperties.ServerConfig config) throws IOException {
                String transport = config.getTransport() == null
                        ? "http" : config.getTransport().trim().toLowerCase();
                if ("stdio".equals(transport)) {
                    List<String> command = new ArrayList<String>();
                    command.add(config.getCommand());
                    if (config.getArgs() != null) command.addAll(config.getArgs());
                    return new StdioJsonRpcExchange(command, mapper,
                            config.getReadTimeoutMillis());
                }
                if ("http".equals(transport) || "streamable-http".equals(transport)
                        || "http+json".equals(transport)) {
                    if (config.getEndpoint() == null || config.getEndpoint().trim().isEmpty()) {
                        throw new IOException("mcp server " + config.getName()
                                + " uses transport " + transport + " but has no endpoint");
                    }
                    return new HttpJsonRpcExchange(config.getEndpoint(),
                            config.getConnectTimeoutMillis(), config.getReadTimeoutMillis());
                }
                // http+sse(已废弃)/websocket(未实现)一律显式拒绝: 静默忽略配置是更难查的 bug
                throw new IOException("unsupported transport '" + config.getTransport()
                        + "' for mcp server " + config.getName()
                        + " (supported: http, stdio; http+sse is deprecated by the protocol)");
            }
        };
    }

    // ------------------------------------------------------------------ 同步

    /** 启动时调用: 连上所有 enabled 的 server 并聚合目录. */
    public synchronized void syncAll() {
        for (McpProperties.ServerConfig config : properties.getServers()) {
            syncOne(config);
        }
        startSchedulerIfNeeded();
    }

    private void syncOne(McpProperties.ServerConfig config) {
        String name = config.getName();
        if (name == null || name.trim().isEmpty()) {
            log.warn("skipping an entry in z.mcp.servers[] without a name");
            return;
        }
        Managed m = managed.get(name);
        if (m == null) {
            m = new Managed(name);
            managed.put(name, m);
        }
        // 连不上的 server 也要在 admin 里看得见 endpoint, 否则运维只能看到一串 null
        m.config = config;
        if (!config.isEnabled()) {
            detach(m, "disabled by configuration");
            m.lastError = "disabled";
            return;
        }
        try {
            if (m.client == null) {
                JsonRpcExchange exchange = factory.create(config);
                m.client = new McpRemoteClient(name, config, exchange, mapper,
                        properties.getServerName(), properties.getServerVersion());
            }
            if (!m.client.isConnected()) m.client.connect();
            // 自指的上游必须先拒, 再谈拉目录: hub 指向"另一台 hub"而那就是自己(或同配置的复制品)时,
            // 对岸的目录装的是我这台的副本, 撞名分支会给它 invent 一个新前缀名 —— 每同步一轮长一代,
            // 实测十分钟里目录从 150 涨到 228. 判据取握手回来的 serverInfo.name 而不是 endpoint:
            // 换端口、走 localhost 还是走机器名, 自指都是同一个名字.
            String advertised = m.client.serverInfo() == null
                    ? null : m.client.serverInfo().path("name").asText(null);
            if (advertised != null && advertised.equals(properties.getServerName())) {
                detach(m, "self-referential");
                m.lastError = "upstream " + name + " identifies itself as this server (" + advertised
                        + ") — 聚合它等于把自己的目录再抄一遍, 目录会随同步轮数无界增长";
                m.lastSyncMillis = System.currentTimeMillis();
                log.warn("mcp server {} refused: {}", name, m.lastError);
                return;
            }
            List<McpToolDto> tools = m.client.listTools();
            boolean republished = publish(m, config, tools);
            m.lastError = null;
            m.lastSyncMillis = System.currentTimeMillis();
            if (republished) {
                // 收进来的条数, 不是上游广告的条数 —— 两者不等就说明上面那条环检测起作用了
                log.info("mcp server {} aggregated {} of {} upstream tool(s)",
                        name, Integer.valueOf(m.toolNames.size()), Integer.valueOf(tools.size()));
            } else {
                log.debug("mcp server {} directory unchanged ({} tool(s)) — 不重发布",
                        name, Integer.valueOf(tools.size()));
            }
        } catch (Exception e) {
            // 目录拉不到就把它摘掉: 留着上一次的目录等于广告调不动的工具
            detach(m, "unreachable");
            m.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            m.lastSyncMillis = System.currentTimeMillis();
            log.warn("mcp server {} unavailable: {}", name, e.getMessage());
        }
    }

    /**
     * 把上游目录搬进本地注册表.
     *
     * @return 这一次是否真的动了目录(逐字未变时返回 false, 于是也不惊动任何会话)
     */
    private boolean publish(Managed m, McpProperties.ServerConfig config, List<McpToolDto> tools) {
        // 先过滤、再取签名 —— 顺序本身就是这条判据的全部内容.
        //
        // 签名若含"被环检测拒收的那些复制品", hub↔hub 环上对岸每聚合一轮都会让它们换个措辞,
        // 于是这一轮被判成"目录变了"; 而"变了"的动作是 dropTools() 再逐条重登**同一批**收进来的
        // 工具 —— 中间那一下, 一次 Change.TOOLS 里读 tools/list 会看见整批条目不见了
        // (单元层实测: 观察到的目录是 []; 真进程层实测: 12 条里只剩 10 条). 更糟的是这次 fire
        // 恰好通知了所有会话"来重取目录", 而协议要求它们收到就重取 —— 我们主动把客户端领进空洞里.
        final String self = properties.getServerName();
        List<McpToolDto> accepted = new ArrayList<McpToolDto>();
        int echoed = 0;
        for (McpToolDto tool : tools) {
            // 环检测: 上游把**我这台**广告出去的工具抄回来还给我了. 收下它不会报错, 只会每轮多一代
            // 前缀名(撞名分支给它 invent 一个 server__x 的新名字), 于是目录无界增长.
            // 判据是来源链而不是名字: 链由每一跳在广告时追加自己的 server-name, 所以环上一定有自己.
            if (self != null && tool.getOrigins() != null && tool.getOrigins().contains(self)) echoed++;
            else accepted.add(tool);
        }
        String signature = signatureOf(accepted);
        if (signature.equals(m.publishedSignature) && stillRegistered(m)) {
            //  重抄一遍同样的目录也要动一次快照、播一帧变更, 而每条会话都会因此去重取
            //  tools/list —— 250 上实测每轮 8 帧(那时"变了"的动作还是摘一次+登一次, 一帧变两帧).
            return false;
        }
        m.publishedSignature = signature;
        final McpRemoteClient client = m.client;
        // 整批一次换到位 —— 而不是"逐条摘掉再逐条登回": 后者每一趟摘与每一趟登各自播一帧
        // list_changed, 而每一帧都在叫所有会话重取目录, 那些时刻读目录就是缺的。
        List<String> published = registry.replaceServerTools(config.getName(), accepted,
                new McpRegistry.ToolExecutorFactory() {
                    @Override public McpRegistry.ToolExecutor create(final McpToolDto tool) {
                        final String remoteName = tool.getName();
                        return new McpRegistry.ToolExecutor() {
                            @Override public Object execute(Map<String, Object> arguments) throws Exception {
                                return callUpstream(client, remoteName, arguments);
                            }
                        };
                    }
                });
        m.toolNames.clear();
        m.toolNames.addAll(published);
        if (echoed > 0) {
            log.warn("mcp server {} returned {} tool(s) that this server ({}) itself advertised;"
                    + " 聚合已成环, 这些条目不再收 (z-mcp/origins)",
                    config.getName(), Integer.valueOf(echoed), self);
        }
        registry.registerServer(new McpServerDto(config.getName(), displayEndpoint(config),
                config.getTransport(), accepted));
        return true;
    }

    /**
     * 上游目录的内容签名: 只取**收进来的**、会进目录、会被人看见的字段, 并按名字排序 ——
     * 上游把同一批工具换个顺序回来, 不该被当成变更; 被环检测拒收的那部分怎么抖, 也不该.
     */
    private static String signatureOf(List<McpToolDto> tools) {
        Set<String> lines = new TreeSet<String>();
        for (McpToolDto t : tools) {
            lines.add(t.getName() + '|' + t.getTitle() + '|' + t.getDescription() + '|'
                    + t.getInputSchemaJson() + '|' + t.getOutputSchemaJson() + '|'
                    + (t.getAnnotations() == null ? "" : t.getAnnotations().toWire()) + '|'
                    // 来源链也进签名: 链变了(哪怕内容没变)收与不收的判定就变了
                    + (t.getOrigins() == null ? "" : t.getOrigins()));
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        return sb.toString();
    }

    /** 上一轮登记的那些条目还在不在 —— 被人直接 unregister 过就不能跳过重发布. */
    private boolean stillRegistered(Managed m) {
        for (String n : m.toolNames) {
            if (!registry.hasTool(n)) return false;
        }
        return true;
    }

    /** 上游调用: 会话失效由 client 自愈; 网络/协议失败转成执行错误让模型看得见. */
    private static CallToolResult callUpstream(McpRemoteClient client, String toolName,
                                               Map<String, Object> arguments) {
        try {
            if (!client.isConnected()) client.connect();
            return client.callTool(toolName, arguments);
        } catch (Exception e) {
            log.warn("upstream call {}.{} failed: {}", client.serverName(), toolName, e.toString());
            return CallToolResult.executionError("upstream mcp server " + client.serverName()
                    + " could not run " + toolName + ": " + e.getMessage());
        }
    }

    /**
     * 把这台上游留在目录里的那一批一次摘掉 —— 空的那批 {@link McpRegistry#replaceServerTools}
     * 自己会认下来(不换快照、也不播变更), 所以这里不必先判空.
     */
    private void dropTools(Managed m) {
        registry.replaceServerTools(m.serverName, Collections.<McpToolDto>emptyList(), NO_EXECUTOR);
        m.toolNames.clear();
    }

    /** 摘一批工具用不到执行体, 但原语的签名要一个 —— 走到这里就说明有人要给空目录造执行体. */
    private static final McpRegistry.ToolExecutorFactory NO_EXECUTOR =
            new McpRegistry.ToolExecutorFactory() {
                @Override public McpRegistry.ToolExecutor create(McpToolDto tool) {
                    throw new IllegalStateException("dropping tools cannot need an executor: "
                            + tool.getName());
                }
            };

    private void detach(Managed m, String reason) {
        dropTools(m);
        //  条目已经不在目录里了, 签名也就作废: 否则"摘除后上游原样回来"会被当成没变更而不重发布.
        m.publishedSignature = null;
        if (m.client != null) {
            try {
                m.client.disconnect();
            } catch (RuntimeException ignore) {
                // 断开失败不影响摘除
            }
            // disconnect 已经把传输关掉了: 留着这个 client, 下一轮健康检查就会一直
            // 对着一条已死的管道重连, 于是"恢复"这件事永远不会发生.
            m.client = null;
        }
        registry.unregisterServer(m.serverName);
        log.info("mcp server {} detached ({})", m.serverName, reason);
    }

    // ------------------------------------------------------------------ 健康检查

    private void startSchedulerIfNeeded() {
        int interval = properties.getHealthCheckIntervalSeconds();
        if (destroyed || interval <= 0 || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "z-mcp-server-health");
                t.setDaemon(true);
                return t;
            }
        });
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try {
                    syncAll();
                } catch (RuntimeException e) {
                    log.warn("mcp server health sweep failed: {}", e.getMessage());
                }
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    public synchronized void destroy() {
        destroyed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        for (Managed m : managed.values()) detach(m, "shutting down");
    }

    // ------------------------------------------------------------------ 状态视图

    /**
     * stdio server 没有 URL, 但它确实有个"入口" —— 命令行. admin 里回 null 等于
     * 让运维对着一个不知轻重的空字段猜是哪个子进程.
     */
    static String displayEndpoint(McpProperties.ServerConfig config) {
        String transport = config.getTransport() == null
                ? "http" : config.getTransport().trim().toLowerCase();
        if (!"stdio".equals(transport)) return config.getEndpoint();
        if (config.getCommand() == null) return null;
        StringBuilder sb = new StringBuilder(config.getCommand());
        if (config.getArgs() != null) {
            for (String a : config.getArgs()) sb.append(' ').append(a);
        }
        return sb.toString();
    }

    /** 给 admin / 健康检查用的真实状态, 不含任何"假定正常"的字段. */
    public synchronized List<Map<String, Object>> state() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Managed m : managed.values()) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("name", m.serverName);
            s.put("endpoint", m.config == null ? null : displayEndpoint(m.config));
            s.put("transport", m.config == null ? null : m.config.getTransport());
            s.put("connected", Boolean.valueOf(m.client != null && m.client.isConnected()));
            s.put("protocolVersion", m.client == null ? null : m.client.protocolVersion());
            s.put("toolCount", Integer.valueOf(m.toolNames.size()));
            s.put("tools", new ArrayList<String>(m.toolNames));
            s.put("lastError", m.lastError);
            s.put("lastSync", Long.valueOf(m.lastSyncMillis));
            out.add(s);
        }
        return out;
    }

    public synchronized Map<String, Object> health() {
        int up = 0;
        for (Managed m : managed.values()) {
            if (m.client != null && m.client.isConnected()) up++;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("configured", Integer.valueOf(managed.size()));
        out.put("connected", Integer.valueOf(up));
        out.put("failed", Integer.valueOf(managed.size() - up));
        return Collections.unmodifiableMap(out);
    }

    /** 已接入的 client 列表(状态视图与 admin 用). */
    public synchronized List<McpRemoteClient> clients() {
        List<McpRemoteClient> out = new ArrayList<McpRemoteClient>();
        for (Managed m : managed.values()) if (m.client != null) out.add(m.client);
        return out;
    }

    private static final class Managed {
        final String serverName;
        final List<String> toolNames = new ArrayList<String>();
        McpProperties.ServerConfig config;
        McpRemoteClient client;
        volatile String lastError;
        volatile long lastSyncMillis;
        /** 上一次发布出去的上游目录内容, 用来判断这一轮要不要真的动目录. */
        String publishedSignature;

        Managed(String serverName) { this.serverName = serverName; }
    }
}
