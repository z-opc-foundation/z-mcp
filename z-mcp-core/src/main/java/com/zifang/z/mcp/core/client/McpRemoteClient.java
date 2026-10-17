package com.zifang.z.mcp.core.client;

import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.ContentBlock;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.json.Wire;
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
    private final String clientName;
    private final String clientVersion;
    private final AtomicLong ids = new AtomicLong(1);

    private volatile String sessionId;
    private volatile String protocolVersion;
    private volatile Object serverInfo;
    private volatile Object capabilities;
    private volatile boolean connected;

    public McpRemoteClient(String serverName, McpProperties.ServerConfig config,
                                   JsonRpcExchange exchange,
                                   String clientName, String clientVersion) {
        this.serverName = serverName;
        this.config = config;
        this.exchange = exchange;
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

        Object init = rpc(McpSchema.M_INITIALIZE, params, true);
        serverInfo = Wire.get(init, "serverInfo");
        capabilities = Wire.get(init, "capabilities");
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

    public Object serverInfo() { return serverInfo; }

    public Object capabilities() { return capabilities; }

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
            Object result = rpc(McpSchema.M_TOOLS_LIST, params.isEmpty() ? null : params, true);
            Object tools = Wire.get(result, "tools");
            if (Wire.isArray(tools)) {
                for (Object t : Wire.elements(tools)) out.add(toToolDto(t));
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

    public Object ping() throws IOException {
        return rpc(McpSchema.M_PING, null, true);
    }

    // ------------------------------------------------------------------ JSON-RPC

    private Object rpc(String method, Map<String, Object> params, boolean allowRetry)
            throws IOException {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Long.valueOf(ids.getAndIncrement()));
        request.put("method", method);
        if (params != null) request.put("params", params);

        JsonRpcExchange.Response res = exchange.post(Wire.write(request), headers());
        if (res.status() == 404 && allowRetry && sessionId != null) {
            log.info("mcp server {} session expired, re-initializing", serverName);
            sessionId = null;
            connected = false;
            connect();
            return rpc(method, params, false);
        }
        if (res.status() == 401 || res.status() == 403) {
            throw new RemoteRejectException(res.status(), null,
                    "server " + serverName + " refused HTTP " + res.status() + " to " + method
                            + ": we sent " + credentialProblem() + challengeOf(res)
                            + " — check the headers of z.mcp.servers[] entry name=" + serverName);
        }
        if (!res.is2xx()) {
            throw new RemoteRejectException(res.status(), null,
                    "server " + serverName + " answered HTTP " + res.status() + " to " + method);
        }
        Object envelope = parseEnvelope(res);
        adoptSession(res);
        if (envelope == null) {
            throw new IOException("mcp server " + serverName + " sent no JSON-RPC envelope for "
                    + method + " (HTTP " + res.status() + ")");
        }
        Object error = Wire.get(envelope, "error");
        if (error != null) {
            Object code = Wire.get(error, "code");
            Object message = Wire.get(error, "message");
            throw new RemoteRejectException(0,
                    code == null || !Wire.isNumber(code) ? null : Integer.valueOf(((Number) code).intValue()),
                    method + " on " + serverName + ": "
                            + (message == null ? Wire.write(error) : Wire.asText(message)));
        }
        Object result = Wire.get(envelope, "result");
        return result == null ? Wire.object() : result;
    }

    private void notify(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (params != null) request.put("params", params);
        JsonRpcExchange.Response res = exchange.post(Wire.write(request), headers());
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

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 被上游以凭证问题拒掉时, 报的不是"401"这个数字, 而是<em>我们这边到底带出去了什么</em>.
     *
     * <p>401 有两种成因, 要改的地方在两台机器上: 这条上游压根没配凭证(典型形状是配置里写了
     * {@code Authorization: "Bearer ${SOME_TOKEN}"} 而环境变量没给, 头展开成只有方案名的空壳,
     * 对方只能判"请求不成形"), 和配了但不在对方的白名单里. 只报状态码等于让运维在 hub 的
     * {@code servers[].headers} 和叶子的 {@code bearer-tokens} 之间来回猜.
     *
     * <p>只报形状不报值 —— 凭证不进异常消息, 也就不会跟着日志和状态视图跑到别处.
     */
    private String credentialProblem() {
        Map<String, String> configured = config.getHeaders();
        if (configured == null || configured.isEmpty()) {
            return "no credential is configured for this upstream (z.mcp.servers[] headers is empty)";
        }
        String sent = null;
        for (Map.Entry<String, String> e : configured.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Authorization")) {
                sent = e.getValue();
                break;
            }
        }
        if (sent == null) return "no Authorization header among the configured headers";
        // 只 trim 两头: "Bearer " 后面那个空格是"有方案名、没有令牌值"这一形状的全部证据,
        // 连着 trim 掉就只剩一个 "Bearer", 会被误报成"写法不对"而不是"变量没给值".
        String value = sent.trim();
        boolean bearerShaped = value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                || value.equalsIgnoreCase("Bearer");
        if (!bearerShaped) return "Authorization is not of the form \"Bearer <token>\"";
        if (value.length() <= BEARER_PREFIX.length()) {
            return "Authorization has a \"Bearer \" prefix but no token value"
                    + " (the variable behind it is unset)";
        }
        return "a bearer token that the upstream does not accept";
    }

    /** 上游的 {@code WWW-Authenticate}: 三种挑战形状分别对应"去取凭证/换令牌再来/别重试". */
    private static String challengeOf(JsonRpcExchange.Response res) {
        String challenge = res.header("WWW-Authenticate");
        return challenge == null || challenge.trim().isEmpty() ? "" : " (server challenge: "
                + challenge.trim() + ")";
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
    private Object parseEnvelope(JsonRpcExchange.Response res) throws IOException {
        String body = res.body() == null ? "" : res.body().trim();
        if (body.isEmpty()) return null;
        String contentType = res.contentType();
        if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
            for (Object candidate : ssePayloads(body)) {
                if (Wire.has(candidate, "result") || Wire.has(candidate, "error")) return candidate;
            }
            return null;
        }
        return Wire.read(body);
    }

    /** 把 SSE 文本里的 {@code data:} 字段还原成 JSON 节点(多行 data 按规范拼接, 注释帧忽略). */
    static List<Object> ssePayloads(String text) {
        List<Object> out = new ArrayList<Object>();
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
                appendJson(out, data.toString());
                data.setLength(0);
            }
        }
        if (data.length() > 0) appendJson(out, data.toString());
        return out;
    }

    private static void appendJson(List<Object> out, String raw) {
        Object node = Wire.read(raw);
        // 非 JSON 的 data 帧(进度文本等)不参与请求/响应配对
        if (Wire.isObject(node)) out.add(node);
    }

    // ------------------------------------------------------------------ 映射

    private McpToolDto toToolDto(Object t) {
        Object inputSchema = Wire.get(t, "inputSchema");
        Object outputSchema = Wire.get(t, "outputSchema");
        return new McpToolDto(text(t, "name", null), text(t, "title", null), text(t, "description", null),
                serverName,
                inputSchema == null ? null : Wire.write(inputSchema),
                outputSchema == null ? null : Wire.write(outputSchema),
                annotations(Wire.get(t, "annotations")),
                origins(t));
    }

    /**
     * 上游 {@code _meta["z-mcp/origins"]} 里的来源链(逗号分隔). 没有该键 —— 例如对方不是 z-mcp,
     * 或它是本机自己的内置工具 —— 返回 null, 由广告侧从空链起算.
     */
    private static List<String> origins(Object t) {
        Object meta = Wire.get(t, "_meta");
        if (!Wire.isObject(meta)) return null;
        Object node = Wire.get(meta, "z-mcp/origins");
        if (node == null) return null;
        List<String> out = new ArrayList<String>();
        for (String hop : String.valueOf(Wire.asText(node)).split(",")) {
            String trimmed = hop.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    static ToolAnnotations annotations(Object node) {
        if (!Wire.isObject(node)) return null;
        return new ToolAnnotations(text(node, "title", null),
                hint(node, "readOnlyHint"), hint(node, "destructiveHint"),
                hint(node, "idempotentHint"), hint(node, "openWorldHint"));
    }

    private static Boolean hint(Object node, String field) {
        Object v = Wire.get(node, field);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    /** 上游 CallToolResult → 本地 CallToolResult(内容块按线格式原样转发). */
    static CallToolResult fromWire(Object result) {
        List<ContentBlock> blocks = new ArrayList<ContentBlock>();
        Object content = Wire.get(result, "content");
        if (Wire.isArray(content)) {
            for (Object c : Wire.elements(content)) {
                ContentBlock block = ContentBlock.fromWire(Wire.toMap(c));
                if (block != null) blocks.add(block);
            }
        }
        CallToolResult out = CallToolResult.of(blocks);
        if (Boolean.TRUE.equals(Wire.get(result, "isError"))) out.setError(true);
        Object structured = Wire.get(result, "structuredContent");
        if (structured != null) out.setStructuredContent(Wire.toPlain(structured));
        Object meta = Wire.get(result, "_meta");
        if (Wire.isObject(meta)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (String k : Wire.fieldNames(meta)) {
                m.put(k, Wire.toPlain(Wire.get(meta, k)));
            }
            out.meta(m);
        }
        return out;
    }

    /**
     * 握手时广告出去的客户端能力 —— 只列这一侧真兑现得了的.
     *
     * <p>这里一度写着 {@code roots}/{@code sampling}/{@code elicitation} 三件, 而全仓没有一处会答
     * {@code roots/list} 或 {@code sampling/createMessage}: 广告就是给对端发"你可以问我"的邀请函,
     * 问了答不上来只能回 -32601(#45 补的那道兜底). 两份官方参照都是**注册了回调才说** ——
     * python 的 {@code ClientSession.initialize()} 逐件按 callback 是否仍是默认值来派生, TS 的
     * {@code Client} 直接传 {@code options?.capabilities ?? {}}.
     *
     * <p>协议要求 {@code capabilities} 必填, 但没说不能是空对象 —— 所以这一格是"空", 不是"缺".
     * 将来谁真实现了 roots 列表, 加在这里, 并同时加一条"上游问上门来答得出"的判据.
     */
    private static Map<String, Object> clientCapabilities() {
        return new LinkedHashMap<String, Object>();
    }

    private static String text(Object node, String field, String fallback) {
        Object v = Wire.get(node, field);
        return v == null ? fallback : Wire.asText(v);
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
