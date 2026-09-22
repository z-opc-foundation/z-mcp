package com.zifang.z.mcp.starter.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.core.builtin.BuiltinTools;
import com.zifang.z.mcp.core.controller.JsonRpcController;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * z-mcp 自动装配 — 业务系统引入 z-mcp-starter 后, 自动注册 McpRegistry + 内置工具 + JSON-RPC controller.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class ZMcpAutoConfiguration {

    @Bean
    public McpRegistry mcpRegistry() {
        McpRegistry registry = new McpRegistry();
        BuiltinTools.registerAll(registry);
        return registry;
    }

    @Bean
    public JsonRpcController jsonRpcController(McpRegistry registry, ObjectMapper mapper) {
        return new JsonRpcController(registry, mapper);
    }

    @Bean
    public WebMvcConfigurer zMcpWebMvcConfigurer() {
        return new WebMvcConfigurer() {};
    }
}