package com.zifang.z.mcp.starter.bridge;

import com.zifang.z.agent.center.core.agent.tool.ToolRegistry;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 把 z-mcp 的工具目录绑进 z-agent-center 的 ToolRegistry。
 * <p>
 * 遗留的 com.zifang:z-agent-mcp-starter 里 ToolRegistryIntegrationConfig 干的就是这件事；
 * MCP 独立成 z-mcp 之后没有对应物，绑定这一刀留在平台侧。
 * <p>
 * 两种"不在"都必须空转，不让整个应用起不来（2026-10-04 补上第二种）：
 * <ul>
 *   <li>{@code z.mcp.enabled=false} ⇒ 容器里没有 {@link McpRegistry}（下面那个 null 判断）。</li>
 *   <li>宿主没装 z-agent-center ⇒ {@code z-agent-center-core} 在本模块是 provided 作用域，
 *       {@link ToolRegistry} 不在类路径上。<b>这一条以前是崩的</b>：本类是 {@code @Component}，
 *       无条件实例化，{@link #bind()} 里静态调 {@code ToolRegistry.getInstance()}，
 *       于是 {@code NoClassDefFoundError: com/zifang/z/agent/center/core/agent/tool/ToolRegistry}
 *       把整个上下文掀翻。pom 注释里"无 agent-center 时空转"那句话当时并不成立 ——
 *       那个 {@link ObjectProvider} 守的是 {@code McpRegistry}，不是 {@code ToolRegistry}。
 *       由 z-mcp-server（正是"没装 agent-center 的宿主"）的 22 条用例撞出来。</li>
 * </ul>
 * ⇒ 加 {@link ConditionalOnClass}：条件按类元数据（ASM）判定，不加载类，
 * 类不在时整个 bean 定义都不注册，方法体里那处直接引用也就永远执行不到。
 */
@Component
@ConditionalOnClass(name = "com.zifang.z.agent.center.core.agent.tool.ToolRegistry")
public class McpToolRegistryBindConfig {

    private static final Logger log = LogManager.getLogger(McpToolRegistryBindConfig.class);

    @Autowired
    private ObjectProvider<McpRegistry> mcpRegistryProvider;

    @PostConstruct
    public void bind() {
        McpRegistry registry = mcpRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.info("McpRegistry 不可用 (z.mcp.enabled=false?)，跳过 agent 工具表绑定");
            return;
        }

        ToolRegistry toolRegistry = ToolRegistry.getInstance();
        // 上游 server 是后台定时同步进来的，只在启动时绑一次的话，之后换目录 agent 就看不见了
        registry.addChangeListener(McpRegistry.Change.TOOLS, toolRegistry::syncFromMcp);
        toolRegistry.bindMcpRegistry(registry);
        log.info("ToolRegistry bound to z-mcp McpRegistry ({} tools)", Integer.valueOf(registry.toolCount()));
    }
}
