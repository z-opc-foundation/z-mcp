package com.zifang.z.mcp.api.dto;

/**
 * MCP 工具注册条目(API 层 DTO).
 *
 * <p>name: 工具唯一标识(对应 LLM tools[].function.name).
 * <p>description: 给 LLM 看的功能说明.
 * <p>inputSchema: OpenAI 兼容 JSON Schema, 描述参数.
 * <p>serverName: 注册来源 server(可选, 内置工具为空).
 */
public final class McpToolDto {

    private final String name;
    private final String description;
    private final String serverName;
    private final String inputSchemaJson;

    public McpToolDto(String name, String description, String serverName, String inputSchemaJson) {
        this.name = name;
        this.description = description;
        this.serverName = serverName;
        this.inputSchemaJson = inputSchemaJson;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getServerName() { return serverName == null ? "(builtin)" : serverName; }
    public String getInputSchemaJson() { return inputSchemaJson == null ? "{}" : inputSchemaJson; }
}