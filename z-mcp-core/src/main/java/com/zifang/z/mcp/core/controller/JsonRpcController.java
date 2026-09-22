package com.zifang.z.mcp.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.JsonRpcRequest;
import com.zifang.z.mcp.api.dto.JsonRpcResponse;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP JSON-RPC over HTTP 入口 — 默认实现.
 *
 * <p>POST /mcp 接收 JSON-RPC 2.0 请求, 支持方法:
 * <ul>
 *   <li>{@code tools/list} — 返回全部已注册工具 + 它们的 JSON Schema</li>
 *   <li>{@code tools/call} — 调一个工具</li>
 *   <li>{@code servers/list} — 返回已注册 server 列表</li>
 *   <li>{@code ping} — 健康检查</li>
 * </ul>
 *
 * <p>对应 z-opc 老 z-agent-mcp-center/z-agent-mcp-server1/McpServerController 蒸馏.
 */
@RestController
public class JsonRpcController {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcController.class);

    private final McpRegistry registry;
    private final ObjectMapper mapper;

    public JsonRpcController(McpRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @PostMapping(value = "/mcp", consumes = "application/json", produces = "application/json")
    public JsonRpcResponse handle(@RequestBody JsonNode body) {
        if (body == null || !body.has("method")) {
            return JsonRpcResponse.error(-32600, "invalid request", null);
        }
        String method = body.get("method").asText();
        Object id = body.has("id") ? mapper.convertValue(body.get("id"), Object.class) : null;
        JsonNode paramsNode = body.get("params");
        Map<String, Object> params = paramsNode == null || paramsNode.isNull()
                ? null
                : mapper.convertValue(paramsNode, Map.class);
        JsonRpcRequest req = new JsonRpcRequest("2.0", method, params, id);
        try {
            switch (req.getMethod()) {
                case "tools/list":
                    return JsonRpcResponse.ok(listToolsResult(), req.getId());
                case "tools/call":
                    return JsonRpcResponse.ok(callTool(req.getParams()), req.getId());
                case "servers/list":
                    return JsonRpcResponse.ok(serversListResult(), req.getId());
                case "ping":
                    return JsonRpcResponse.ok(Collections.singletonMap("pong", true), req.getId());
                default:
                    return JsonRpcResponse.error(-32601, "method not found: " + req.getMethod(), req.getId());
            }
        } catch (RuntimeException e) {
            log.warn("MCP JSON-RPC error: {}", e.getMessage());
            return JsonRpcResponse.error(-32000, e.getMessage(), req.getId());
        }
    }

    private Map<String, Object> listToolsResult() {
        Map<String, Object> result = new HashMap<String, Object>();
        java.util.List<Map<String, Object>> toolList = new java.util.ArrayList<Map<String, Object>>();
        for (com.zifang.z.mcp.api.dto.McpToolDto t : registry.listTools()) {
            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("name", t.getName());
            entry.put("description", t.getDescription());
            entry.put("server", t.getServerName());
            try {
                entry.put("inputSchema", mapper.readTree(t.getInputSchemaJson()));
            } catch (Exception e) {
                entry.put("inputSchema", mapper.createObjectNode());
            }
            toolList.add(entry);
        }
        result.put("tools", toolList);
        return result;
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        if (params == null || !params.containsKey("name")) {
            throw new com.zifang.z.mcp.api.exception.McpException(-32602, "params.name required");
        }
        String name = String.valueOf(params.get("name"));
        Object argsObj = params.get("arguments");
        Map<String, Object> args = new HashMap<String, Object>();
        if (argsObj instanceof Map) {
            args = (Map<String, Object>) argsObj;
        }
        Object content = registry.call(name, args);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("content", content);
        return result;
    }

    private Map<String, Object> serversListResult() {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("servers", registry.listServers());
        return result;
    }
}