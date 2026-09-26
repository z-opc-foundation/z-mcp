package com.zifang.z.mcp.api.dto;

import java.util.List;

/**
 * MCP server 注册条目(API 层 DTO).
 *
 * <p>name: server 唯一名(如 "filesystem" / "github").
 * <p>endpoint: JSON-RPC 入口(stdio 传输下是命令行的可读表示).
 * <p>tools: server 提供的工具列表.
 */
public final class McpServerDto {

    private final String name;
    private final String endpoint;
    /** 配置里写的原值: http / streamable-http / http+json / stdio. websocket 与已废弃的 http+sse 不会被接进来. */
    private final String transport;
    private final List<McpToolDto> tools;

    public McpServerDto(String name, String endpoint, String transport, List<McpToolDto> tools) {
        this.name = name;
        this.endpoint = endpoint;
        this.transport = transport;
        this.tools = tools == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(tools);
    }

    public String getName() { return name; }
    public String getEndpoint() { return endpoint; }
    public String getTransport() { return transport; }
    public List<McpToolDto> getTools() { return tools; }
}