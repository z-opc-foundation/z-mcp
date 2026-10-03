package com.zifang.z.mcp.starter.bridge;

import com.zifang.z.agent.center.core.agent.tool.ToolRegistry;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 把 z-mcp 的工具目录绑进 z-agent-center 的 ToolRegistry。
 * <p>
 * 遗留的 com.zifang:z-agent-mcp-starter 里 ToolRegistryIntegrationConfig 干的就是这件事；
 * MCP 独立成 z-mcp 之后没有对应物，绑定这一刀留在平台侧。
 * <p>
 * z.mcp.enabled=false 时容器里没有 McpRegistry，这里空转 —— 不让整个应用起不来。
 */
@Component
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
