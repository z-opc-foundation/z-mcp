package com.zifang.z.mcp.starter.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.util.core.meta.Result;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.core.client.ExternalServerManager;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端别名控制器 (2026-10-03 自 z-opc main-starter 的 E2EAliasController MCP 段平移):
 * 前端 makeApi('mcpApi') 调的是 {@code /api/mcp/server/*}, z-mcp 原生面是
 * {@code ${z.mcp.base-path}/mcp} 的 JSON-RPC + 注册表 API, 这里做只读投影 + 调用桥.
 * <p>
 * z-mcp 的上游是配置项驱动的 (z.mcp.servers[*]), 没有 server 表 ⇒ 别名只有
 * list / tools/list / tools/call 三个读端点; create/update/delete/test 四个写端点随
 * z-agent-mcp 一族退场, 不再提供.
 * <p>
 * 生命周期跟随 ZMcpAutoConfiguration 的 z.mcp.enabled 开关: 关掉时整个 bridge 包
 * (含本控制器) 不装载, /api/mcp/* 变 404 —— 比旧副本的"起容器但回错误信封"更诚实.
 */
@RestController
public class McpAliasController {

    private static final ObjectMapper MCP_ALIAS_MAPPER = new ObjectMapper();

    @Autowired
    private ObjectProvider<ExternalServerManager> externalServerManagerProvider;
    @Autowired
    private ObjectProvider<McpRegistry> mcpRegistryProvider;
    @Autowired
    private ObjectProvider<McpProperties> mcpPropertiesProvider;

    /**
     * MCP 上游列表 (alias，数据来自 z-mcp ExternalServerManager 的状态视图 + 本机注册表一行).
     * <p>{@code GET /api/mcp/server/list}</p>
     */
    @GetMapping("/api/mcp/server/list")
    public Result<List<Map<String, Object>>> mcpServerListAlias() {
        McpRegistry registry = mcpRegistryProvider.getIfAvailable();
        List<Map<String, Object>> rows = new ArrayList<>();
        ExternalServerManager manager = externalServerManagerProvider.getIfAvailable();
        if (manager != null) {
            for (Map<String, Object> state : manager.state()) {
                rows.add(serverRow(asString(state.get("name")), state.get("transport"),
                        state.get("endpoint"), Boolean.TRUE.equals(state.get("connected")), registry));
            }
        }
        if (registry != null) {
            // 本机自己就是一个 MCP server (${z.mcp.base-path}/mcp)，脚本桥接进来的工具挂在这一行上
            rows.add(serverRow(localServerName(), "LOCAL", localBasePath() + "/mcp", true, registry));
        }
        return Result.success(rows);
    }

    /** 行键名跟前端表格的 dataIndex 对齐 (serverName / transportType / url / status / toolCount)。 */
    private Map<String, Object> serverRow(String serverName, Object transport, Object endpoint,
                                          boolean connected, McpRegistry registry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serverName", serverName);
        row.put("transportType", transport);
        row.put("url", endpoint);
        row.put("status", connected ? "active" : "offline");
        row.put("toolCount", registry == null ? 0 : toolsOf(registry, serverName).size());
        return row;
    }

    /**
     * MCP 工具列表 (alias，数据来自 McpRegistry 的目录).
     * <p>{@code POST /api/mcp/server/tools/list}，body {@code {serverName}}；缺省看本机那一行。</p>
     */
    @PostMapping("/api/mcp/server/tools/list")
    public Result<Map<String, Object>> mcpToolListAlias(@RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> tools = new ArrayList<>();
        McpRegistry registry = mcpRegistryProvider.getIfAvailable();
        if (registry != null) {
            String serverName = body == null ? null : asString(body.get("serverName"));
            for (McpToolDto dto : toolsOf(registry, serverName)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", dto.getName());
                row.put("description", dto.getDescription());
                row.put("inputSchema", readSchema(dto.getInputSchemaJson()));
                tools.add(row);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tools", tools);
        return Result.success(payload);
    }

    /**
     * MCP 工具调用 (alias，走 McpRegistry.call，结果按协议线格式交回).
     * <p>{@code POST /api/mcp/server/tools/call}，body {@code {toolName, arguments}}。</p>
     * <p>工具自身失败按协议走 {@code isError=true} + content 说明，不是 HTTP 错误，
     * 所以前端始终拿到同一形状的 {@code {content: [{type, text}]}}。</p>
     */
    @PostMapping("/api/mcp/server/tools/call")
    public Result<Map<String, Object>> mcpToolCallAlias(@RequestBody(required = false) Map<String, Object> body) {
        McpRegistry registry = mcpRegistryProvider.getIfAvailable();
        if (registry == null) {
            return Result.success(CallToolResult.executionError("MCP 注册表不可用 (z.mcp.enabled=false)").toWire());
        }
        String toolName = body == null ? null : asString(body.get("toolName"));
        if (toolName == null || toolName.isEmpty()) {
            return Result.success(CallToolResult.executionError("缺少 toolName").toWire());
        }
        Map<String, Object> arguments = new HashMap<>();
        Object rawArgs = body.get("arguments");
        if (rawArgs instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawArgs).entrySet()) {
                arguments.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        try {
            Object result = registry.call(toolName, arguments);
            if (result instanceof CallToolResult) {
                return Result.success(((CallToolResult) result).toWire());
            }
            return Result.success(CallToolResult.structured(result, toJson(result)).toWire());
        } catch (Exception e) {
            return Result.success(CallToolResult.executionError(String.valueOf(e.getMessage())).toWire());
        }
    }

    /** 某个 server 名下的工具；空名或本机名对应注册表自己的工具 (dto.serverName 为 null)。 */
    private List<McpToolDto> toolsOf(McpRegistry registry, String serverName) {
        List<McpToolDto> out = new ArrayList<>();
        boolean local = serverName == null || serverName.isEmpty() || serverName.equals(localServerName());
        for (McpToolDto dto : registry.listTools()) {
            if (local ? dto.isBuiltin() : serverName.equals(dto.getServerName())) out.add(dto);
        }
        return out;
    }

    private String localServerName() {
        McpProperties properties = mcpPropertiesProvider.getIfAvailable();
        return properties == null ? "z-mcp" : properties.getServerName();
    }

    private String localBasePath() {
        McpProperties properties = mcpPropertiesProvider.getIfAvailable();
        return properties == null || properties.getBasePath() == null ? "" : properties.getBasePath();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** schema 来自外部 server 的 tools/list，单个坏 schema 不能顶掉整张工具表，降级为 null。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isEmpty()) return null;
        try {
            return MCP_ALIAS_MAPPER.readValue(schemaJson, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(Object value) {
        // 工具直接交回字符串时不再 JSON 包一层 —— 那一层引号会原样落到前端的文本块里
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return MCP_ALIAS_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
