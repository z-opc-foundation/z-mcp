package com.zifang.z.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 响应体. result / error 二选一.
 *
 * <p>JSON-RPC 明确要求: 响应对象**不得同时**含 result 与 error.
 * 因此线格式由 {@link #toWire()} 生成(只写非 null 的一侧), 而不是把两个字段都交给
 * 序列化器输出 null.
 */
public final class JsonRpcResponse {

    private final String jsonrpc; // "2.0"
    private final Object result;
    private final ErrorObj error;
    private final Object id;

    private JsonRpcResponse(Object result, ErrorObj error, Object id) {
        this.jsonrpc = "2.0";
        this.result = result;
        this.error = error;
        this.id = id;
    }

    public static JsonRpcResponse ok(Object result, Object id) {
        return new JsonRpcResponse(result, null, id);
    }

    /** 空结果 — 用于 ping / logging/setLevel 等"成功但无内容"的方法. */
    public static JsonRpcResponse okEmpty(Object id) {
        return new JsonRpcResponse(new LinkedHashMap<String, Object>(), null, id);
    }

    public static JsonRpcResponse error(int code, String message, Object id) {
        return new JsonRpcResponse(null, new ErrorObj(code, message, null), id);
    }

    public static JsonRpcResponse error(int code, String message, Object data, Object id) {
        return new JsonRpcResponse(null, new ErrorObj(code, message, data), id);
    }

    public Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("jsonrpc", jsonrpc);
        m.put("id", id);
        if (error != null) m.put("error", error.toWire());
        else m.put("result", result == null ? new LinkedHashMap<String, Object>() : result);
        return m;
    }

    public String getJsonrpc() { return jsonrpc; }

    public Object getResult() { return result; }

    public Object getError() { return error; }

    public Object getId() { return id; }

    public boolean isError() { return error != null; }

    public static final class ErrorObj {
        public final int code;
        public final String message;
        public final Object data;

        public ErrorObj(int code, String message, Object data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        Map<String, Object> toWire() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("code", Integer.valueOf(code));
            m.put("message", message);
            if (data != null) m.put("data", data);
            return m;
        }

        public int getCode() { return code; }

        public String getMessage() { return message; }

        public Object getData() { return data; }
    }
}
