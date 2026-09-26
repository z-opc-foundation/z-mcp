package com.zifang.z.mcp.api.exception;

/**
 * MCP 平台异常基类 — 表示**协议错误**(JSON-RPC error 包络).
 *
 * <p>与"工具执行失败"严格区分: 后者不是异常, 而是
 * {@link com.zifang.z.mcp.api.dto.CallToolResult#executionError(String)}(isError=true),
 * 客户端会把失败内容回灌给 LLM 让它自我纠正.
 *
 * <p>只有下列情况才是协议错误:
 * <ul>
 *   <li>请求结构非法 / JSON 解析失败</li>
 *   <li>方法不存在</li>
 *   <li>参数缺失或形状不对(含未知工具名 — 协议示例用 -32602)</li>
 *   <li>服务端内部故障</li>
 * </ul>
 *
 * <p>历史注记: 0.1.x 用过 -32001/-32002 作为 toolNotFound/toolExecutionFailed,
 * 这两个码不在任何 MCP 修订版里, 0.2.0 起改用规范码.
 */
public class McpException extends RuntimeException {

    // ---- JSON-RPC 2.0 / MCP 规范码 ----
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /** 2025-xx 系列保留段: 资源不存在(2026-07-28 修订已改归 -32602). */
    public static final int RESOURCE_NOT_FOUND = -32002;

    /** 协商出的协议版本不被支持(2026-07-28 段). */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /**
     * 请求已被 {@code notifications/cancelled} 取消. MCP 全文**没有**给这个场景规定错误码
     * (取消一节只说接收方 SHOULD 停止处理、并且"不发响应"), 而 Streamable HTTP 下调用方正同步
     * 阻塞在这个 POST 上 —— 不响应就是让客户端白等一个传输层超时, 比一个明确的错误更糟。
     * 因此本服务用 JSON-RPC 生态里既有的 -3280(LSP 的 RequestCancelled), 并把它当成"这条请求
     * 没有产生结果"来回答。
     */
    public static final int REQUEST_CANCELLED = -3280;

    private final int code;
    private final Object data;

    public McpException(int code, String message) {
        this(code, message, null, null);
    }

    public McpException(int code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public McpException(int code, String message, Object data) {
        this(code, message, data, null);
    }

    public McpException(int code, String message, Object data, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    /** JSON-RPC error.data, 可为 null. */
    public Object getData() {
        return data;
    }

    // ---- 工厂 ----

    public static McpException parseError(String detail) {
        return new McpException(PARSE_ERROR, "parse error: " + detail);
    }

    public static McpException invalidRequest(String detail) {
        return new McpException(INVALID_REQUEST, "invalid request: " + detail);
    }

    public static McpException methodNotFound(String method) {
        return new McpException(METHOD_NOT_FOUND, "method not found: " + method);
    }

    /** 参数非法 / 未知工具名 — 协议示例把 "Unknown tool: x" 归为 -32602. */
    public static McpException invalidParams(String detail) {
        return new McpException(INVALID_PARAMS, "invalid params: " + detail);
    }

    public static McpException toolNotFound(String name) {
        return new McpException(INVALID_PARAMS, "unknown tool: " + name);
    }

    public static McpException resourceNotFound(String uri) {
        return new McpException(RESOURCE_NOT_FOUND, "resource not found: " + uri);
    }

    public static McpException promptNotFound(String name) {
        return new McpException(INVALID_PARAMS, "unknown prompt: " + name);
    }

    public static McpException internal(String detail, Throwable cause) {
        return new McpException(INTERNAL_ERROR, "internal error: " + detail, cause);
    }

    /** 客户端已经用 {@code notifications/cancelled} 取消掉的那条请求 —— 它没有产生结果. */
    public static McpException requestCancelled(String reason) {
        return new McpException(REQUEST_CANCELLED,
                "request cancelled" + (reason == null || reason.isEmpty() ? "" : ": " + reason));
    }

    /**
     * @deprecated 0.2.0 起工具执行失败**不再是协议错误**; 请返回
     *             {@link com.zifang.z.mcp.api.dto.CallToolResult#executionError(String)}.
     *             保留此工厂仅用于服务端内部故障的分类归档.
     */
    @Deprecated
    public static McpException toolExecutionFailed(String name, Throwable cause) {
        return new McpException(INTERNAL_ERROR, "tool execution failed: " + name, cause);
    }
}
