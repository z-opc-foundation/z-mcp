package com.zifang.z.mcp.api.dto;

/**
 * JSON-RPC 2.0 响应体. result/error 二选一.
 */
public final class JsonRpcResponse {

    private final String jsonrpc; // "2.0"
    private final Object result;
    private final Object error;
    private final Object id;

    private JsonRpcResponse(String jsonrpc, Object result, Object error, Object id) {
        this.jsonrpc = jsonrpc;
        this.result = result;
        this.error = error;
        this.id = id;
    }

    public static JsonRpcResponse ok(Object result, Object id) {
        return new JsonRpcResponse("2.0", result, null, id);
    }

    public static JsonRpcResponse error(int code, String message, Object id) {
        return new JsonRpcResponse("2.0", null, new ErrorObj(code, message), id);
    }

    public String getJsonrpc() { return jsonrpc; }
    public Object getResult() { return result; }
    public Object getError() { return error; }
    public Object getId() { return id; }

    public static final class ErrorObj {
        public final int code;
        public final String message;

        public ErrorObj(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() { return code; }
        public String getMessage() { return message; }
    }
}