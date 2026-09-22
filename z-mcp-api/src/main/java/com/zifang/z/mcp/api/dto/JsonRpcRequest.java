package com.zifang.z.mcp.api.dto;

import java.util.Map;

/**
 * JSON-RPC 2.0 请求体. params 用 Map (任意可 JSON 序列化结构), 避免依赖 Jackson.
 */
public final class JsonRpcRequest {

    private final String jsonrpc; // "2.0"
    private final String method;
    private final Map<String, Object> params;
    private final Object id;

    public JsonRpcRequest(String jsonrpc, String method, Map<String, Object> params, Object id) {
        this.jsonrpc = jsonrpc;
        this.method = method;
        this.params = params;
        this.id = id;
    }

    public String getJsonrpc() { return jsonrpc; }
    public String getMethod() { return method; }
    public Map<String, Object> getParams() { return params; }
    public Object getId() { return id; }
}