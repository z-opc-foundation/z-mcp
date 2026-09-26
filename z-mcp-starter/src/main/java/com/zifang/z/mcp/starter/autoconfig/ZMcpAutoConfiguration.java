package com.zifang.z.mcp.starter.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.core.builtin.BuiltinTools;
import com.zifang.z.mcp.core.client.ExternalServerManager;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpProtocolHandler;
import com.zifang.z.mcp.core.protocol.McpSchema;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.security.TransportSecurityGuard;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * z-mcp 自动装配 — 业务系统引入 z-mcp-starter 后得到:
 * McpRegistry(+内置工具/资源) → 协议分发器 → Streamable HTTP 端点 → 可选控制面.
 *
 * <p>JsonRpcController 由 @ComponentScan 通过 @RestController 自动发现, 不要重复 @Bean
 * (否则在用户业务模块加 scanBasePackages="com.zifang.z.mcp.core" 时会爆 BeanDefinitionOverrideException).
 *
 * <p>FEATURE066: @ConditionalOnProperty(z.mcp.enabled) 防止与 z-opc 内部遗留的
 * z-agent-mcp-* 抢 /mcp 路径. 完全替换后可去掉.
 */
@Configuration
@ConditionalOnProperty(name = "z.mcp.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(McpProperties.class)
@ComponentScan(basePackages = "com.zifang.z.mcp.core.controller")
public class ZMcpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZMcpAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public McpRegistry mcpRegistry(McpProperties properties, ObjectMapper mapper) {
        McpRegistry registry = new McpRegistry();
        registry.setStrictToolNames(properties.isStrictToolNames());
        // 版本以打包进 MANIFEST 的 Implementation-Version 为准, 配置值只作兜底
        String manifestVersion = ZMcpAutoConfiguration.class.getPackage().getImplementationVersion();
        if (manifestVersion != null && manifestVersion.trim().length() > 0) {
            properties.setServerVersion(manifestVersion);
        }
        if (properties.isBuiltinToolsEnabled()) {
            BuiltinTools.registerAll(registry, properties, mapper);
        } else {
            // 关掉之后目录空掉是一种"看起来像装配没生效"的状态, 留一行字给排障的人.
            log.info("z-mcp builtin tools disabled by z.mcp.builtin-tools-enabled=false;"
                    + " only tools registered by this application will be advertised");
        }
        return registry;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public McpSessionStore mcpSessionStore(McpProperties properties) {
        return new McpSessionStore(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransportSecurityGuard transportSecurityGuard(McpProperties properties) {
        return new TransportSecurityGuard(properties);
    }

    @Bean(destroyMethod = "destroy")
    @ConditionalOnMissingBean
    public McpProtocolHandler mcpProtocolHandler(McpRegistry registry, McpProperties properties,
                                                 ObjectMapper mapper, final McpSessionStore sessions) {
        final McpProtocolHandler handler = new McpProtocolHandler(registry, properties, mapper);
        // 注册表变更 → 向所有在线会话推对应类目的 list_changed.
        // 三类必须分开: initialize 时广告的是 tools/resources/prompts 各自的 listChanged=true,
        // 把 prompt 注册播报成 tools/list_changed 等于叫客户端去重刷一份没动的工具表.
        registry.addChangeListener(McpRegistry.Change.TOOLS, listChanged(handler, sessions, McpSchema.N_TOOLS_LIST_CHANGED));
        registry.addChangeListener(McpRegistry.Change.RESOURCES, listChanged(handler, sessions, McpSchema.N_RESOURCES_LIST_CHANGED));
        registry.addChangeListener(McpRegistry.Change.PROMPTS, listChanged(handler, sessions, McpSchema.N_PROMPTS_LIST_CHANGED));
        return handler;
    }

    private static Runnable listChanged(final McpProtocolHandler handler,
                                        final McpSessionStore sessions, final String method) {
        return new Runnable() {
            @Override public void run() {
                handler.broadcast(sessions.snapshot(), method);
            }
        };
    }

    /**
     * 外部 MCP server 接入 —— {@code z.mcp.servers[]} 与 {@code healthCheckIntervalSeconds}
     * 从 0.1.x 的"没人读的配置"变成真的行为.
     *
     * <p>首轮同步放在守护线程里: 上游挂了/慢了不能把自己的启动卡住(配了 N 个 server 就是
     * N 倍连接超时), 拉不到目录的 server 由 manager 自己摘除并在健康检查里重试.
     */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnMissingBean
    public ExternalServerManager externalServerManager(McpRegistry registry, McpProperties properties,
                                                       ObjectMapper mapper) {
        final ExternalServerManager manager = new ExternalServerManager(registry, properties, mapper);
        if (!properties.getServers().isEmpty()) {
            Thread bootstrap = new Thread(new Runnable() {
                @Override public void run() {
                    manager.syncAll();
                }
            }, "z-mcp-server-bootstrap");
            bootstrap.setDaemon(true);
            bootstrap.start();
        }
        return manager;
    }

    /**
     * 控制面按需挂载. 0.1.x 里 z-mcp-admin 既没被任何装配引用、也没被任何 pom 依赖,
     * 所以光靠这个 @ComponentScan 仍会扫一个不在 classpath 上的包而静默空转 ——
     * starter 现在真的依赖 z-mcp-admin, 开关才确实能开关东西.
     */
    @Configuration
    @ConditionalOnProperty(name = "z.mcp.expose-admin", havingValue = "true")
    @ComponentScan(basePackages = "com.zifang.z.mcp.admin")
    static class AdminConfiguration {
    }
}
