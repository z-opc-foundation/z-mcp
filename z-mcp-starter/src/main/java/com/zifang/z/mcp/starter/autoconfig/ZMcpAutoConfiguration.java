package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.core.builtin.BuiltinTools;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * z-mcp 自动装配 — 业务系统引入 z-mcp-starter 后, 自动注册 McpRegistry + 内置工具 + JSON-RPC controller.
 *
 * <p>JsonRpcController 由 @ComponentScan 通过 @RestController 自动发现, 不要重复 @Bean
 * <p>(否则在用户业务模块加 scanBasePackages="com.zifang.z.mcp.core" 时会爆 BeanDefinitionOverrideException).
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ComponentScan(basePackages = {
        "com.zifang.z.mcp.core.controller",
        "com.zifang.z.mcp.core.registry",
        "com.zifang.z.mcp.core.builtin",
        "com.zifang.z.mcp.core.properties"
})
public class ZMcpAutoConfiguration {

    @Bean
    public McpRegistry mcpRegistry() {
        McpRegistry registry = new McpRegistry();
        BuiltinTools.registerAll(registry);
        return registry;
    }
}