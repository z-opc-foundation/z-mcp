package com.zifang.z.mcp.api.exception;

/**
 * MCP 平台异常基类.
 */
public class McpException extends RuntimeException {

    private final int code;

    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }

    public McpException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** 工具不存在 (-32001). */
    public static McpException toolNotFound(String name) {
        return new McpException(-32001, "tool not found: " + name);
    }

    /** 工具执行失败 (-32002). */
    public static McpException toolExecutionFailed(String name, Throwable cause) {
        return new McpException(-32002, "tool execution failed: " + name, cause);
    }

    /** 参数非法 (-32602). */
    public static McpException invalidParams(String detail) {
        return new McpException(-32602, "invalid params: " + detail);
    }
}