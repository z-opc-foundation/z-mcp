package com.zifang.z.mcp.core.builtin;

import com.zifang.z.mcp.core.registry.McpRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 内置工具集 — 注册到 McpRegistry, 提供 echo / get_time / generate_uuid 等基础工具.
 *
 * <p>对应 z-opc 老 BuiltInToolExecutor 蒸馏到 z-mcp 仓.
 */
public class BuiltinTools {

    private BuiltinTools() {}

    public static void registerAll(McpRegistry registry) {
        registry.registerBuiltin(
                "echo",
                "回显输入文本 (用于调试 LLM tool 调用链路)",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"要回显的文本\"}},\"required\":[\"text\"]}",
                args -> args.get("text")
        );

        registry.registerBuiltin(
                "get_time",
                "获取当前时间(ISO-8601 UTC, 毫秒精度)",
                "{\"type\":\"object\",\"properties\":{\"format\":{\"type\":\"string\",\"enum\":[\"iso\",\"epoch\"],\"description\":\"返回格式\"}}}",
                args -> {
                    String format = (String) args.getOrDefault("format", "iso");
                    if ("epoch".equals(format)) return System.currentTimeMillis();
                    return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new java.util.Date());
                }
        );

        registry.registerBuiltin(
                "generate_uuid",
                "生成 UUID v4 字符串",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> UUID.randomUUID().toString()
        );

        registry.registerBuiltin(
                "system_info",
                "返回 z-mcp 平台基础信息",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> {
                    Map<String, Object> info = new HashMap<String, Object>();
                    info.put("platform", "z-mcp");
                    info.put("version", "0.1.0");
                    info.put("java", System.getProperty("java.version"));
                    info.put("os", System.getProperty("os.name"));
                    return info;
                }
        );
    }
}