package com.zifang.z.mcp.core.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.ContentBlock;
import com.zifang.z.mcp.api.dto.McpResourceDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置工具集 + 内置资源 — 注册到 McpRegistry.
 *
 * <p>对应 z-opc 老 BuiltInToolExecutor 蒸馏到 z-mcp 仓.
 */
public final class BuiltinTools {

    private BuiltinTools() {}

    public static void registerAll(McpRegistry registry) {
        registerAll(registry, new McpProperties(), new ObjectMapper());
    }

    public static void registerAll(McpRegistry registry, McpProperties properties, final ObjectMapper mapper) {
        final String version = properties.getServerVersion();

        registry.tool("echo")
                .title("Echo Tool")
                .description("回显输入文本 (用于调试 LLM tool 调用链路)")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                        + "\"description\":\"要回显的文本\"}},\"required\":[\"text\"],"
                        + "\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("回显文本"))
                .register(args -> args.get("text"));

        // 0.1.x 用 SimpleDateFormat 打 'Z' 后缀, 实际是 JVM 默认时区 — 声明与行为不符.
        // 现在走 java.time 且显式 UTC.
        registry.tool("get_time")
                .title("Current Time Tool")
                .description("获取当前时间(默认 ISO-8601 UTC, 毫秒精度)")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"format\":{\"type\":\"string\","
                        + "\"enum\":[\"iso\",\"epoch\",\"iso_local\"],\"description\":\"返回格式\"}},"
                        + "\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("当前时间"))
                .register(args -> {
                    Object format = args.get("format");
                    String f = format == null ? "iso" : String.valueOf(format);
                    if ("epoch".equals(f)) return Long.valueOf(System.currentTimeMillis());
                    if ("iso_local".equals(f)) {
                        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                                .format(java.time.ZonedDateTime.now());
                    }
                    return DateTimeFormatter.ISO_INSTANT.format(
                            Instant.now().atZone(ZoneOffset.UTC));
                });

        registry.tool("generate_uuid")
                .title("UUID Tool")
                .description("生成 UUID v4 字符串")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("生成 UUID"))
                .register(args -> java.util.UUID.randomUUID().toString());

        registry.tool("system_info")
                .title("System Info Tool")
                .description("返回 z-mcp 平台基础信息")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("平台信息"))
                .register(args -> {
                    Map<String, Object> info = new LinkedHashMap<String, Object>();
                    info.put("platform", "z-mcp");
                    info.put("version", version);
                    info.put("java", System.getProperty("java.version"));
                    info.put("os", System.getProperty("os.name"));
                    return info;
                });

        registerIntrospectionResources(registry, properties, mapper);
    }

    /**
     * 把注册中心自身暴露为 MCP 资源 — 客户端可以 resources/read 直接看目录,
     * 这也是 capabilities.resources 不成为谎报的前提.
     */
    private static void registerIntrospectionResources(final McpRegistry registry,
                                                       McpProperties properties,
                                                       final ObjectMapper mapper) {
        final String version = properties.getServerVersion();
        registry.registerResource(new McpResourceDto(
                "z-mcp://tools", "MCP tools", "当前注册的全部工具及其 JSON Schema",
                "application/json", args -> ContentBlock.text(mapper.writeValueAsString(snapshotTools(registry)))));

        registry.registerResource(new McpResourceDto(
                "z-mcp://servers", "MCP servers", "已接入的外部 MCP server 及其工具数",
                "application/json", args -> ContentBlock.text(mapper.writeValueAsString(
                        registry.listServers()))));

        registry.registerResource(new McpResourceDto(
                "z-mcp://self", "Server info", "本 MCP server 的身份与协议支持",
                "application/json", args -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", properties.getServerName());
                    m.put("version", version);
                    m.put("protocolVersions",
                            com.zifang.z.mcp.core.protocol.McpSchema.SUPPORTED_VERSIONS);
                    m.put("toolCount", Integer.valueOf(registry.toolCount()));
                    return ContentBlock.resource("z-mcp://self", "application/json",
                            mapper.writeValueAsString(m));
                }));
    }

    private static Object snapshotTools(McpRegistry registry) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<Map<String, Object>>();
        for (McpToolDto t : registry.listTools()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", t.getName());
            m.put("server", t.getServerDisplayName());
            m.put("description", t.getDescription());
            out.add(m);
        }
        return out;
    }
}
