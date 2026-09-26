package com.zifang.z.mcp.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MCP 工具注册条目(API 层 DTO).
 *
 * <p>name: 工具唯一标识, 应满足 {@code [A-Za-z0-9_.-]} 且长度 1..128(协议 SHOULD).
 * <p>description: 给 LLM 看的功能说明.
 * <p>inputSchemaJson: JSON Schema 字符串. 协议要求 inputSchema 必须是对象且**不得为 null**;
 *                   无参工具也应是 {@code {"type":"object"}}.
 * <p>serverName: 注册来源 server(内置工具为 null).
 */
public final class McpToolDto {

    private final String name;
    private final String title;
    private final String description;
    private final String serverName;
    private final String inputSchemaJson;
    private final String outputSchemaJson;
    private final ToolAnnotations annotations;

    /**
     * 聚合来源链: 这个工具依次经过哪些 server (取各家的 serverInfo.name), 由最近一跳标出.
     * 只用于断环 —— 链上有自己就不再导入. 内置工具为 null.
     */
    private final List<String> origins;

    public McpToolDto(String name, String description, String serverName, String inputSchemaJson) {
        this(name, null, description, serverName, inputSchemaJson, null, null);
    }

    public McpToolDto(String name, String title, String description, String serverName,
                      String inputSchemaJson, String outputSchemaJson, ToolAnnotations annotations) {
        this(name, title, description, serverName, inputSchemaJson, outputSchemaJson, annotations, null);
    }

    public McpToolDto(String name, String title, String description, String serverName,
                      String inputSchemaJson, String outputSchemaJson, ToolAnnotations annotations,
                      List<String> origins) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.serverName = serverName;
        this.inputSchemaJson = inputSchemaJson;
        this.outputSchemaJson = outputSchemaJson;
        this.annotations = annotations;
        this.origins = origins == null || origins.isEmpty()
                ? null : Collections.unmodifiableList(new ArrayList<String>(origins));
    }

    public String getName() { return name; }

    public String getTitle() { return title; }

    public String getDescription() { return description; }

    /** 原始来源 server; 内置工具返回 null. */
    public String getServerName() { return serverName; }

    /** 展示用: 内置工具显示为 (builtin). */
    public String getServerDisplayName() { return serverName == null ? "(builtin)" : serverName; }

    public boolean isBuiltin() { return serverName == null; }

    /**
     * JSON Schema 文本. 兜底为 {@code {"type":"object"}} 而不是 {@code {}} —
     * 协议把 inputSchema 定义为 object schema, 缺 type 会让严格客户端校验失败.
     */
    public String getInputSchemaJson() {
        return inputSchemaJson == null || inputSchemaJson.trim().isEmpty()
                ? "{\"type\":\"object\",\"properties\":{}}" : inputSchemaJson;
    }

    public String getOutputSchemaJson() { return outputSchemaJson; }

    public ToolAnnotations getAnnotations() { return annotations; }

    /**
     * 聚合来源链: 把这条工具广告出来的每一跳的 server-name, 按"离自己越近越靠后"排列.
     * 内置工具与不支持该字段的第三方上游返回 null.
     */
    public List<String> getOrigins() { return origins; }
}
