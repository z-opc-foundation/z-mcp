package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.ContentBlock;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游 MCP server 客户端 — 协议层, 传输细节在 {@link JsonRpcExchange} 里.
 *
 * <p>为什么手写而不是用官方 Java SDK: SDK 全线要求 Java 17 + Reactor, 而 z-mcp 的编译
 * 目标是 Java 8. 传输细节收在 {@link JsonRpcExchange} 后面, 所以协议逻辑可以离线测.
 *
 * <p>协议要点(对应 spec §Transports / Streamable HTTP):
 * <ul>
 *   <li>initialize 协商版本, 之后每个请求都带 {@code MCP-Protocol-Version}</li>
 *   <li>会话 id 从响应头取并原样带回; 上游回 404 表示会话失效 ⇒ 重新 initialize 一次</li>
 *   <li>POST 的响应可能是 {@code text/event-stream}(SSE 帧里装 JSON-RPC 响应), 两种都要吃</li>
 *   <li>通知期待 202 空体, 不能当错误</li>
 *   <li>tools/list 必须跟 nextCursor 翻页, 否则大 server 的工具会被静默截断</li>
 * </ul>
 */
public class McpRemoteClient {

    private static final Logger log = LoggerFactory.getLogger(McpRemoteClient.class);

    /** 翻页硬上限: 上游 nextCursor 自指时不能把注册流程挂死. */
    static final int MAX_PAGES = 100;

    private final String serverName;
    private final JsonRpcExchange exchange;
    private final McpProperties.ServerConfig config;
    private final ObjectMapper mapper;
    private final String clientName;
    private final String clientVersion;
    private final AtomicLong ids = new AtomicLong(1);

    private volatile String sessionId;
    private volatile String protocolVersion;
    private volatile JsonNode serverInfo;
    private volatile JsonNode capabilities;
    private volatile boolean connected;

    public McpRemoteClient(String serverName, McpProperties.ServerConfig config,
                                   JsonRpcExchange exchange, ObjectMapper mapper,
                                   String clientName, String clientVersion) {
        this.serverName = serverName;
        this.config = config;
        this.exchange = exchange;
        this.mapper = mapper;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 建立会话: initialize → 记录协商结果 → 发 notifications/initialized.
     *
     * <p>不发第二个通知的 client 会被多数真实 server 以 "server not initialized" 拒掉后续请求,
     * 所以这一步是必须的而不是礼貌性的.
     */
    public synchronized void connect() throws IOException {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("protocolVersion", McpSchema.LATEST_SUPPORTED);
        params.put("capabilities", clientCapabilities());
        Map<String, Object> clientInfo = new LinkedHashMap<String, Object>();
        clientInfo.put("name", clientName);
        clientInfo.put("version", clientVersion);
        params.put("clientInfo", clientInfo);

        JsonNode init = rpc(McpSchema.M_INITIALIZE, params, true);
        serverInfo = init.get("serverInfo");
        capabilities = init.get("capabilities");
        protocolVersion = text(init, "protocolVersion", McpSchema.DEFAULT_ASSUMED);
        connected = true;
        notify(McpSchema.M_NOTIFICATION_INITIALIZED, null);
        log.info("mcp server {} connected: protocol {}, serverInfo {}, session {}",
                serverName, protocolVersion, serverInfo, sessionId == null ? "(stateless)" : sessionId);
    }

    public synchronized void disconnect() {
        if (sessionId != null && exchange instanceof DeleteCapable) {
            try {
                ((DeleteCapable) exchange).delete(headers());
            } catch (IOException e) {
                log.debug("mcp server {} session delete failed: {}", serverName, e.getMessage());
            }
        }
        connected = false;
        sessionId = null;
        // 传输的所有权在 client: 不关掉它, stdio 的子进程就会在摘除一个 server 之后
        // 永远留在机器上(HTTP 的 close 是空操作, 所以这一句只对本地 server 有意义).
        try {
            exchange.close();
        } catch (RuntimeException e) {
            log.debug("mcp server {} transport close failed: {}", serverName, e.getMessage());
        }
    }

    /**
     * connected 这个 flag 只在握手时置位, 它自己永远不会变假 —— 本地 server 的子进程退了
     * 就是最好的反例: flag 仍是 true, 但这条链路已经不可能再回答任何东西.
     * 所以"连得上吗"必须由传输自己回答, flag 只是它的一个前提.
     */
    public boolean isConnected() { return connected && exchange.isAlive(); }

    public String serverName() { return serverName; }

    public String protocolVersion() { return protocolVersion; }

    public JsonNode serverInfo() { return serverInfo; }

    public JsonNode capabilities() { return capabilities; }

    public String sessionId() { return sessionId; }

    /** 传输实现支持 DELETE 时, 关连接会真的终止上游会话. */
    public interface DeleteCapable {
        void delete(Map<String, String> headers) throws IOException;
    }

    // ------------------------------------------------------------------ 目录

    /** 拉取上游工具目录(含翻页). 撞名交给 {@link com.zifang.z.mcp.core.registry.McpRegistry}. */
    public List<McpToolDto> listTools() throws IOException {
        List<McpToolDto> out = new ArrayList<McpToolDto>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = rpc(McpSchema.M_TOOLS_LIST, params.isEmpty() ? null : params, true);
            JsonNode tools = result.get("tools");
            if (tools != null && tools.isArray()) {
                for (JsonNode t : tools) out.add(toToolDto(t));
            }
            cursor = text(result, "nextCursor", null);
            if (cursor == null || cursor.isEmpty()) return out;
        }
        throw new IOException("mcp server " + serverName + " still returned nextCursor after "
                + MAX_PAGES + " pages of tools/list — 拒绝聚合一个翻不完的目录");
    }

    // ------------------------------------------------------------------ 调用

    /** 转发一次工具调用, 原样保留上游的内容块与 isError 语义. */
    public CallToolResult callTool(String toolName, Map<String, Object> arguments) throws IOException {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", toolName);
        params.put("arguments", arguments == null
                ? new LinkedHashMap<String, Object>() : arguments);
        return fromWire(rpc(McpSchema.M_TOOLS_CALL, params, true));
    }

    public JsonNode ping() throws IOException {
        return rpc(McpSchema.M_PING, null, true);
    }

    // ------------------------------------------------------------------ JSON-RPC

    private JsonNode rpc(String method, Map<String, Object> params, boolean allowRetry)
            throws IOException {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Long.valueOf(ids.getAndIncrement()));
        request.put("method", method);
        if (params != null) request.put("params", params);

        JsonRpcExchange.Response res = exchange.post(mapper.writeValueAsString(request), headers());
        if (res.status() == 404 && allowRetry && sessionId != null) {
            log.info("mcp server {} session expired, re-initializing", serverName);
            sessionId = null;
            connected = false;
            connect();
            return rpc(method, params, false);
        }
        if (res.status() == 401 || res.status() == 403) {
            throw new RemoteRejectException(res.status(), null,
                    "server " + serverName + " refused HTTP " + res.status()
                            + " — check z.mcp.servers[" + serverName + "].headers");
        }
        if (!res.is2xx()) {
            throw new RemoteRejectException(res.status(), null,
                    "server " + serverName + " answered HTTP " + res.status() + " to " + method);
        }
        JsonNode envelope = parseEnvelope(res);
        adoptSession(res);
        if (envelope == null) {
            throw new IOException("mcp server " + serverName + " sent no JSON-RPC envelope for "
                    + method + " (HTTP " + res.status() + ")");
        }
        JsonNode error = envelope.get("error");
        if (error != null && !error.isNull()) {
            JsonNode code = error.get("code");
            throw new RemoteRejectException(0,
                    code == null || !code.isNumber() ? null : Integer.valueOf(code.asInt()),
                    method + " on " + serverName + ": "
                            + (error.get("message") == null ? error.toString()
                                    : error.get("message").asText()));
        }
        JsonNode result = envelope.get("result");
        return result == null || result.isNull() ? mapper.createObjectNode() : result;
    }

    private void notify(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (params != null) request.put("params", params);
        JsonRpcExchange.Response res = exchange.post(mapper.writeValueAsString(request), headers());
        // 202 是规范值, 但部分实现回 200 空体; 只有 4xx/5xx 才算失败
        if (res.status() >= 400) {
            connected = false;
            throw new IOException("notification " + method + " to " + serverName
                    + " failed with HTTP " + res.status());
        }
        adoptSession(res);
    }

    private void adoptSession(JsonRpcExchange.Response res) {
        String sid = res.header(McpSchema.H_SESSION);
        if (sid != null && !sid.isEmpty() && !sid.equals(sessionId)) sessionId = sid;
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (config.getHeaders() != null) h.putAll(config.getHeaders());
        if (sessionId != null) h.put(McpSchema.H_SESSION, sessionId);
        if (protocolVersion != null) h.put(McpSchema.H_PROTOCOL_VERSION, protocolVersion);
        return h;
    }

    /**
     * 响应体 → JSON-RPC 信封: 直接 JSON, 或 SSE 帧里带 result/error 的那一帧.
     *
     * <p>202/空体返回 null, 由调用方判断是否可接受.
     */
    private JsonNode parseEnvelope(JsonRpcExchange.Response res) throws IOException {
        String body = res.body() == null ? "" : res.body().trim();
        if (body.isEmpty()) return null;
        String contentType = res.contentType();
        if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
            for (JsonNode candidate : ssePayloads(body, mapper)) {
                if (candidate.has("result") || candidate.has("error")) return candidate;
            }
            return null;
        }
        return mapper.readTree(body);
    }

    /** 把 SSE 文本里的 {@code data:} 字段还原成 JSON 节点(多行 data 按规范拼接, 注释帧忽略). */
    static List<JsonNode> ssePayloads(String text, ObjectMapper mapper) {
        List<JsonNode> out = new ArrayList<JsonNode>();
        StringBuilder data = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            if (line.startsWith(":")) continue;
            if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
                continue;
            }
            if (line.trim().isEmpty() && data.length() > 0) {
                appendJson(out, data.toString(), mapper);
                data.setLength(0);
            }
        }
        if (data.length() > 0) appendJson(out, data.toString(), mapper);
        return out;
    }

    private static void appendJson(List<JsonNode> out, String raw, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (node != null && node.isObject()) out.add(node);
        } catch (Exception ignore) {
            // 非 JSON 的 data 帧(进度文本等)不参与请求/响应配对
        }
    }

    // ------------------------------------------------------------------ 映射

    private McpToolDto toToolDto(JsonNode t) {
        JsonNode inputSchema = t.get("inputSchema");
        JsonNode outputSchema = t.get("outputSchema");
        return new McpToolDto(text(t, "name", null), text(t, "title", null), text(t, "description", null),
                serverName,
                inputSchema == null || inputSchema.isNull() ? null : inputSchema.toString(),
                outputSchema == null || outputSchema.isNull() ? null : outputSchema.toString(),
                annotations(t.get("annotations")));
    }

    static ToolAnnotations annotations(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        return new ToolAnnotations(text(node, "title", null),
                hint(node, "readOnlyHint"), hint(node, "destructiveHint"),
                hint(node, "idempotentHint"), hint(node, "openWorldHint"));
    }

    private static Boolean hint(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : Boolean.valueOf(v.asBoolean());
    }

    /** 上游 CallToolResult → 本地 CallToolResult(内容块按线格式原样转发). */
    static CallToolResult fromWire(JsonNode result) {
        List<ContentBlock> blocks = new ArrayList<ContentBlock>();
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode c : content) {
                ContentBlock block = ContentBlock.fromWire(asMap(c));
                if (block != null) blocks.add(block);
            }
        }
        CallToolResult out = CallToolResult.of(blocks);
        JsonNode isError = result.get("isError");
        if (isError != null && isError.asBoolean()) out.setError(true);
        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) out.setStructuredContent(toPlain(structured));
        JsonNode meta = result.get("_meta");
        if (meta != null && meta.isObject()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Iterator<String> it = meta.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, toPlain(meta.get(k)));
            }
            out.meta(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object toPlain(JsonNode node) {
        try {
            return WIRE_MAPPER.convertValue(node, Object.class);
        } catch (IllegalArgumentException e) {
            return node.toString();
        }
    }

    /** 保序转 Map — 内容块字段顺序要能原样出去. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        return WIRE_MAPPER.convertValue(node, LinkedHashMap.class);
    }

    private static final ObjectMapper WIRE_MAPPER = new ObjectMapper();

    private static Map<String, Object> clientCapabilities() {
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        Map<String, Object> roots = new LinkedHashMap<String, Object>();
        roots.put("listChanged", Boolean.FALSE);
        caps.put("roots", roots);
        caps.put("sampling", new LinkedHashMap<String, Object>());
        caps.put("elicitation", new LinkedHashMap<String, Object>());
        return caps;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    /** 上游拒绝(HTTP 4xx/5xx)或返回 JSON-RPC error. */
    public static final class RemoteRejectException extends IOException {
        private final int httpStatus;
        private final Integer jsonRpcCode;

        public RemoteRejectException(int httpStatus, Integer jsonRpcCode, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.jsonRpcCode = jsonRpcCode;
        }

        public int httpStatus() { return httpStatus; }

        public Integer jsonRpcCode() { return jsonRpcCode; }

        /** -32601: 上游没这个能力, 与"连不上"要区别对待. */
        public boolean isMethodNotFound() {
            return jsonRpcCode != null && jsonRpcCode.intValue() == -32601;
        }
    }
}
