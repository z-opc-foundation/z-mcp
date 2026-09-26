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
            List<McpToolDto> tools = m.client.listTools();
            publish(m, config, tools);
            m.lastError = null;
            m.lastSyncMillis = System.currentTimeMillis();
            log.info("mcp server {} aggregated {} tool(s)", name, Integer.valueOf(tools.size()));
        } catch (Exception e) {
            // 目录拉不到就把它摘掉: 留着上一次的目录等于广告调不动的工具
            detach(m, "unreachable");
            m.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            m.lastSyncMillis = System.currentTimeMillis();
            log.warn("mcp server {} unavailable: {}", name, e.getMessage());
        }
    }

    private void publish(Managed m, McpProperties.ServerConfig config, List<McpToolDto> tools) {
        dropTools(m);
        final McpRemoteClient client = m.client;
        for (final McpToolDto tool : tools) {
            String remoteName = tool.getName();
            String effective = registry.tool(remoteName)
                    .title(tool.getTitle())
                    .description(tool.getDescription())
                    .server(config.getName())
                    .inputSchema(tool.getInputSchemaJson())
                    .outputSchema(tool.getOutputSchemaJson())
                    .annotations(tool.getAnnotations())
                    .register(new McpRegistry.ToolExecutor() {
                        @Override
                        public Object execute(Map<String, Object> arguments) throws Exception {
                            return callUpstream(client, tool.getName(), arguments);
                        }
                    });
            m.toolNames.add(effective);
        }
        registry.registerServer(new McpServerDto(config.getName(), displayEndpoint(config),
                config.getTransport(), tools));
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

    private void dropTools(Managed m) {
        for (String n : m.toolNames) registry.unregister(n);
        m.toolNames.clear();
    }

    private void detach(Managed m, String reason) {
        dropTools(m);
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
        if (interval <= 0 || scheduler != null) return;
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

        Managed(String serverName) { this.serverName = serverName; }
    }
}
