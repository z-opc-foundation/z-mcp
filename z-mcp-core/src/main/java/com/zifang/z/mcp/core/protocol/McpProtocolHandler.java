package com.zifang.z.mcp.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.ContentBlock;
import com.zifang.z.mcp.api.dto.JsonRpcResponse;
import com.zifang.z.mcp.api.dto.McpPromptDto;
import com.zifang.z.mcp.api.dto.McpResourceDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.api.exception.McpException;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.session.McpSessionStore.McpSession;
import com.zifang.z.mcp.core.validation.JsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP JSON-RPC 方法分发 — 与传输层解耦, 因此可直接被单测覆盖.
 *
 * <p>两条贯穿全类的语义分界:
 * <ul>
 *   <li><b>协议错误</b>(JSON-RPC error 包络): 请求本身不合法 — 结构错、方法不存在、
 *       参数缺失/形状错、未知工具名、服务端内部故障.</li>
 *   <li><b>执行错误</b>(result.isError=true + content): 请求合法但工具没干成 —
 *       抛异常、超时、入参不满足 schema(2025-11-25 起校验失败明确归此类).
 *       这类内容会被客户端回灌给 LLM 自我纠正, 所以不能塞进 error 包络.</li>
 * </ul>
 */
public class McpProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolHandler.class);

    private final McpRegistry registry;
    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final JsonSchemaValidator validator = new JsonSchemaValidator();
    private final ExecutorService toolPool;

    public McpProtocolHandler(McpRegistry registry, McpProperties properties, ObjectMapper mapper) {
        this.registry = registry;
        this.properties = properties;
        this.mapper = mapper;
        this.toolPool = Executors.newCachedThreadPool(new NamedThreadFactory("z-mcp-tool-"));
    }

    /** 一次请求的执行上下文(由传输层填充). */
    public static final class RequestContext {
        public McpSession session;
        public String protocolVersion = McpSchema.DEFAULT_ASSUMED;
        public String remoteAddress;
        /** initialize 成功后由 handler 写入, 传输层据此决定签发/绑定会话. */
        public String negotiatedVersion;
        public boolean initialized;
        /** 规范化后的本次请求 id —— 取消通知按它寻址; notification 自己永远是 null. */
        public String requestKey;

        public RequestContext() {}
    }

    /** 分发结果: envelope 为 null 表示这是一个已被接受的 notification(202 空体). */
    public static final class Reply {
        public final JsonRpcResponse envelope;
        public final boolean notification;

        Reply(JsonRpcResponse envelope, boolean notification) {
            this.envelope = envelope;
            this.notification = notification;
        }
    }

    public void destroy() {
        toolPool.shutdownNow();
    }

    /**
     * @param request 已解析的 JSON-RPC 请求节点
     * @param ctx     传输层上下文
     */
    public Reply handle(JsonNode request, RequestContext ctx) {
        if (request == null || !request.isObject()) {
            return new Reply(JsonRpcResponse.error(McpException.INVALID_REQUEST,
                    "request must be a JSON-RPC 2.0 object", null), false);
        }
        JsonNode idNode = request.get("id");
        Object id = idNode == null || idNode.isNull() ? null : mapper.convertValue(idNode, Object.class);
        JsonNode methodNode = request.get("method");
        if (methodNode == null || !methodNode.isTextual() || methodNode.asText().isEmpty()) {
            return new Reply(JsonRpcResponse.error(McpException.INVALID_REQUEST,
                    "method is required", id), false);
        }
        String method = methodNode.asText();
        if (idNode != null && idNode.isNull()) {
            // JSON-RPC 2.0 把 null 这个 id 留给"响应方连 id 都取不到"的情形, 并规定请求里的 id MUST NOT 为
            // null; 官方 SDK 的 schema 同样只收 string|number。当"没有 id"静默收下, 客户端就会为一条永远
            // 不会来的答案白等到读超时; 报错至少让它立刻知道是这条消息写错了。
            return new Reply(JsonRpcResponse.error(McpException.INVALID_REQUEST,
                    "request id must not be null", null), false);
        }
        boolean isNotification = idNode == null;
        ctx.requestKey = isNotification ? null : requestKey(idNode);
        JsonNode params = request.get("params");
        if (params != null && !params.isNull() && !params.isObject()) {
            return new Reply(JsonRpcResponse.error(McpException.INVALID_PARAMS,
                    "params must be an object when present", id), false);
        }

        try {
            if (McpSchema.M_INITIALIZE.equals(method)) {
                return new Reply(initialize(params, id, ctx), false);
            }
            if (isNotification) {
                acceptNotification(method, params, ctx);
                return new Reply(null, true);
            }
            if (!guardInitialized(method, ctx)) {
                throw McpException.invalidRequest(
                        "server is not initialized; send initialize + notifications/initialized first");
            }
            Object result = dispatch(method, params, ctx);
            if (result == null) return new Reply(JsonRpcResponse.okEmpty(id), false);
            return new Reply(JsonRpcResponse.ok(result, id), false);
        } catch (McpException e) {
            if (isNotification) {
                // notification 不得产生任何响应, 协议错误只落日志
                log.warn("mcp notification {} failed: {}", method, e.getMessage());
                return new Reply(null, true);
            }
            return new Reply(JsonRpcResponse.error(e.getCode(), e.getMessage(), e.getData(), id), false);
        } catch (RuntimeException e) {
            log.error("mcp method {} raised unhandled error", method, e);
            if (isNotification) return new Reply(null, true);
            return new Reply(JsonRpcResponse.error(McpException.INTERNAL_ERROR,
                    "internal error", null, id), false);
        }
    }

    /**
     * JSON-RPC 的 id 可以是字符串也可以是数字, 而 {@code 123} 与 {@code "123"} 是**两个不同**的
     * 请求 —— 官方 TypeScript SDK 的在飞表是 {@code Map<RequestId, AbortController>}
     * (protocol.ts:562), 而 JS 的 Map 按 SameValueZero 比键, 数字与字符串天然落在两个槽位。
     * 这里因此把类型编进键里, 否则一个发 {@code requestId: 1} 的客户端就能取消掉同会话里另一个
     * 发 {@code requestId: "1"} 的请求(而这两条在客户端眼里毫不相干)。
     */
    static String requestKey(JsonNode id) {
        if (id == null || id.isNull()) return null;
        if (id.isTextual()) return "s:" + id.asText();
        if (id.isNumber()) return "n:" + id.numberValue().toString();
        if (id.isBoolean()) return "b:" + id.asBoolean();
        return "j:" + id.toString();
    }

    // ------------------------------------------------------------ 生命周期

    private JsonRpcResponse initialize(JsonNode params, Object id, RequestContext ctx) {
        if (params == null) throw McpException.invalidParams("params are required for initialize");
        JsonNode requested = params.get("protocolVersion");
        if (requested == null || !requested.isTextual()) {
            throw McpException.invalidParams("params.protocolVersion is required");
        }
        JsonNode clientInfo = params.get("clientInfo");
        if (clientInfo == null || !clientInfo.isObject()) {
            throw McpException.invalidParams("params.clientInfo is required");
        }
        String version = McpSchema.negotiate(requested.asText());
        boolean downgrade = !version.equals(requested.asText());
        ctx.negotiatedVersion = version;
        ctx.protocolVersion = version;
        ctx.initialized = true;

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", version);
        result.put("capabilities", capabilities());
        result.put("serverInfo", McpSchema.implementation(properties.getServerName(),
                properties.getServerTitle(), properties.getServerVersion()));
        if (properties.getInstructions() != null && !properties.getInstructions().isEmpty()) {
            result.put("instructions", properties.getInstructions());
        }
        if (downgrade) {
            log.info("client requested unsupported protocolVersion {}, serving {}",
                    requested.asText(), version);
        }
        if (ctx.session != null && clientInfo.has("name")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> info = mapper.convertValue(clientInfo, Map.class);
            ctx.session.setClientInfo(info);
        }
        return JsonRpcResponse.ok(result, id);
    }

    /** 只声明真正实现了的能力 — 谎报能力会让客户端发出我们接不住的请求. */
    Map<String, Object> capabilities() {
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        caps.put("tools", McpSchema.toolsCapability(true));
        caps.put("resources", McpSchema.resourcesCapability(false, true));
        caps.put("prompts", McpSchema.promptsCapability(true));
        caps.put("logging", McpSchema.loggingCapability());
        return caps;
    }

    private void acceptNotification(String method, JsonNode params, RequestContext ctx) {
        if (McpSchema.M_NOTIFICATION_INITIALIZED.equals(method)) {
            if (ctx.session != null) ctx.session.markInitialized();
            return;
        }
        if (McpSchema.M_NOTIFICATION_CANCELLED.equals(method)) {
            JsonNode target = params == null ? null : params.get("requestId");
            if (target == null || target.isNull() || ctx.session == null) {
                // 协议明确要求这种 race 要被"优雅地"处理: 通知不得产生任何响应, 因此这里既不能
                // 报错也不能回包 —— 只能什么都不做。
                log.debug("cancel notification with nothing to cancel (session {})",
                        ctx.session == null ? "-" : ctx.session.getId());
                return;
            }
            JsonNode why = params.get("reason");
            ctx.session.cancelRequest(requestKey(target),
                    why == null || !why.isTextual() ? null : why.asText());
            return;
        }
        // 其余 notification(如客户端的 initialized 完成信号变体 / 未知根变更)按协议静默接受
        log.debug("ignoring notification {}", method);
    }

    private boolean guardInitialized(String method, RequestContext ctx) {
        if (McpSchema.M_PING.equals(method)) return true;
        if (!properties.isRequireInitialized()) return true;
        if (ctx.session == null) return false;
        return ctx.session.isInitialized();
    }

    // ---------------------------------------------------------------- 分发

    Object dispatch(String method, JsonNode params, RequestContext ctx) {
        switch (method) {
            case McpSchema.M_PING:
                // 协议: ping 的结果是空对象. {"pong":true} 是 0.1.x 的自造形状.
                return new LinkedHashMap<String, Object>();
            case McpSchema.M_TOOLS_LIST:
                return toolsList(params);
            case McpSchema.M_TOOLS_CALL:
                return toolsCall(params, ctx);
            case McpSchema.M_RESOURCES_LIST:
                return resourcesList(params);
            case McpSchema.M_RESOURCES_TEMPLATES_LIST:
                return resourceTemplatesList(params);
            case McpSchema.M_RESOURCES_READ:
                return resourcesRead(params);
            case McpSchema.M_PROMPTS_LIST:
                return promptsList(params);
            case McpSchema.M_PROMPTS_GET:
                return promptsGet(params);
            case McpSchema.M_LOGGING_SET_LEVEL:
                return loggingSetLevel(params, ctx);
            case McpSchema.M_RESOURCES_SUBSCRIBE:
            case McpSchema.M_RESOURCES_UNSUBSCRIBE:
                // 未声明 subscribe 能力; 明确回 method-not-found 而不是假成功
                throw McpException.methodNotFound(method);
            default:
                throw McpException.methodNotFound(method);
        }
    }

    // ---------------------------------------------------------------- tools

    private Map<String, Object> toolsList(JsonNode params) {
        String cursor = textParam(params, "cursor");
        McpRegistry.Page<McpToolDto> page;
        try {
            page = registry.pageTools(cursor, properties.getPageSize());
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams(e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (McpToolDto t : page.getItems()) out.add(toolWire(t));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tools", out);
        if (page.getNextCursor() != null) result.put("nextCursor", page.getNextCursor());
        return result;
    }

    /**
     * Tool 的线格式.
     *
     * <p>0.1.x 在这里放了一个自造的 {@code server} 顶层字段; 严格客户端(zod .strict())
     * 会因未知键直接校验失败. 出处改放 {@code _meta}, 且 inputSchema 缺失时补
     * {@code {"type":"object","properties":{}}} 而不是 null/{}.
     */
    Map<String, Object> toolWire(McpToolDto t) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("name", t.getName());
        if (t.getTitle() != null) entry.put("title", t.getTitle());
        if (t.getDescription() != null) entry.put("description", t.getDescription());
        entry.put("inputSchema", parseSchema(t.getInputSchemaJson(), t.getName(), "inputSchema"));
        if (t.getOutputSchemaJson() != null && !t.getOutputSchemaJson().trim().isEmpty()) {
            entry.put("outputSchema", parseSchema(t.getOutputSchemaJson(), t.getName(), "outputSchema"));
        }
        ToolAnnotations ann = t.getAnnotations();
        if (ann != null) {
            Map<String, Object> aw = ann.toWire();
            if (!aw.isEmpty()) entry.put("annotations", aw);
        }
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        if (!t.isBuiltin()) meta.put("z-mcp/server", t.getServerName());
        // 广告出去时把本机追加在链尾: 下一跳若把这条工具再抄回来, 链上就带着它自己, 于是能被拒.
        List<String> trail = new ArrayList<String>();
        if (t.getOrigins() != null) trail.addAll(t.getOrigins());
        String self = properties.getServerName();
        if (self != null && !self.isEmpty() && !trail.contains(self)) trail.add(self);
        if (!trail.isEmpty()) meta.put("z-mcp/origins", joinOrigins(trail));
        entry.put("_meta", meta);
        return entry;
    }

    /** 来源链的线格式: 逗号分隔的 server-name, 越近的一跳越靠后. */
    static String joinOrigins(List<String> trail) {
        StringBuilder sb = new StringBuilder();
        for (String s : trail) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    private JsonNode parseSchema(String json, String toolName, String which) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
                ObjectNode fallback = mapper.createObjectNode();
                fallback.put("type", "object");
                fallback.set("properties", mapper.createObjectNode());
                return fallback;
            }
            return node;
        } catch (Exception e) {
            log.warn("tool {} has unparsable {}: {} — emitting permissive object schema",
                    toolName, which, e.getMessage());
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("type", "object");
            fallback.set("properties", mapper.createObjectNode());
            return fallback;
        }
    }

    private Map<String, Object> toolsCall(JsonNode params, RequestContext ctx) {
        if (params == null) throw McpException.invalidParams("params.name is required");
        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isEmpty()) {
            throw McpException.invalidParams("params.name must be a non-empty string");
        }
        String name = nameNode.asText();
        McpRegistry.ToolEntry entry = registry.lookup(name);
        if (entry == null) throw McpException.toolNotFound(name); // 协议: -32602

        JsonNode args = params.get("arguments");
        if (args == null || args.isNull()) {
            args = mapper.createObjectNode();
        } else if (!args.isObject()) {
            throw McpException.invalidParams("params.arguments must be an object");
        }

        if (properties.isValidateArguments()) {
            JsonNode schema = parseSchema(entry.schemaJson, name, "inputSchema");
            List<String> violations = validator.validate(schema, args);
            if (!violations.isEmpty()) {
                // 2025-11-25(SEP-1303): 入参校验失败属于**执行错误**, 让 LLM 有机会改参数重试
                return CallToolResult.executionError(
                        "invalid arguments for tool " + name + ": " + join(violations, "; ")).toWire();
            }
        }

        Map<String, Object> arguments = mapper.convertValue(args, Map.class);
        Object raw;
        try {
            raw = invokeWithTimeout(entry, arguments, ctx);
        } catch (ToolTimeout te) {
            String why = "tool " + name + " timed out after "
                    + properties.getToolTimeoutSeconds() + "s";
            log.warn(why);
            emitLog(ctx, "error", why);
            return CallToolResult.executionError(why).toWire();
        } catch (McpException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("tool {} execution failed: {}", name, cause.toString());
            emitLog(ctx, "warning", "tool " + name + " failed: " + cause);
            return CallToolResult.executionError(
                    "tool " + name + " failed: " + describe(cause)).toWire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw McpException.internal("interrupted while calling " + name, ie);
        } catch (Exception e) {
            // tool-timeout-seconds<=0 时工具是在调用线程上直接跑的, 异常因此落到这里而不是
            // ExecutionException. 客户端不该因为服务端配了哪个超时值就看到两种形状: 同一条
            // isError 回执, 同一条 notifications/message.
            log.warn("tool {} execution failed: {}", name, e.toString());
            emitLog(ctx, "warning", "tool " + name + " failed: " + e);
            return CallToolResult.executionError(
                    "tool " + name + " failed: " + describe(e)).toWire();
        }

        return normalize(entry, raw).toWire();
    }

    private Object invokeWithTimeout(final McpRegistry.ToolEntry entry,
                                     final Map<String, Object> arguments,
                                     RequestContext ctx) throws Exception {
        final int timeout = properties.getToolTimeoutSeconds();
        final McpSession s = ctx == null ? null : ctx.session;
        final String key = ctx == null ? null : ctx.requestKey;
        final McpSession.InFlight in = (s != null && key != null) ? s.beginRequest(key) : null;
        try {
            if (timeout <= 0) {
                // 没有线程池就没人能中断这条调用: 中断一次跑在自己线程上的活, 等于去掐调用方
                // 自己的 HTTP 线程。所以这里只在活跑完之后承认"它被取消过", 不再装作产出了一份结果。
                Object raw = registry.call(entry.name, arguments);
                if (in != null && in.isCancelled()) throw McpException.requestCancelled(in.reason());
                return raw;
            }
            Future<Object> future = toolPool.submit(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    return registry.call(entry.name, arguments);
                }
            });
            if (in != null) in.attach(future);
            try {
                return future.get(timeout, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                future.cancel(true);
                throw new ToolTimeout(entry.name);
            } catch (CancellationException ce) {
                // 只有 notifications/cancelled 会走到这里(超时那条分支自己 cancel 并抛 ToolTimeout)
                throw McpException.requestCancelled(in == null ? null : in.reason());
            } catch (ExecutionException ee) {
                throw ee;
            }
        } finally {
            if (s != null && key != null && in != null) s.endRequest(key, in);
        }
    }

    /**
     * 工具返回值 → 协议要求的类型化内容块数组.
     *
     * <p>形状随返回值怎么变都行, 兑现广告的那一步只有一个出口: 折出来的结果一律过
     * {@link #honorDeclaredOutputSchema}. #61 之前只有"工具体自己交回 CallToolResult"与
     * Map/JsonNode 两支接了这台校验器, 剩下<b>六条</b>出口(null / 文本与字符 / 数字与布尔 /
     * 单个内容块 / 内容块列表 / "不像对象"的兜底)都不经它。修法前的探针把这些形状逐个量过
     * (九种绕过形状 + 两份 Map 对照 + 两份未广告对照): 广告了 schema 的九种形状一律
     * {@code isError=null} 且 {@code structuredContent} 缺席, 而同一次运行里 Map 那一支的违例格
     * 会说 "violating its outputSchema" —— 所以那九种的静默不是"没广告所以不兑", 是它们绕开了兑现口.
     *
     * <p>两家官方参照都没有"按返回形状分流"的通道: TS sdk 1.30.1
     * {@code dist/esm/server/mcp.js:132} 对任何 handler 的产物只调一个
     * {@code validateToolOutput()}, 它在缺 structuredContent 时抛
     * "Tool ... has an output schema but no structured content was provided"
     * ({@code :185-197}); python mcp 1.27.1 {@code server/lowlevel/server.py:543-563}
     * 先按形状折成 structured/unstructured, 折完仍进同一道闸, 缺字段就
     * "Output validation error: outputSchema defined but no structured output returned",
     * 连 scalar 兜底那一支也是错误(同文件 {@code :557}).
     */
    CallToolResult normalize(McpRegistry.ToolEntry entry, Object raw) {
        return honorDeclaredOutputSchema(entry, shapeToResult(entry, raw));
    }

    /** 只把返回值折成内容块与 structuredContent, 一条也不判定 —— 判定集中在 honor 那一处. */
    private CallToolResult shapeToResult(McpRegistry.ToolEntry entry, Object raw) {
        if (raw instanceof CallToolResult) return (CallToolResult) raw;
        if (raw == null) return CallToolResult.of(new ArrayList<ContentBlock>());
        if (raw instanceof CharSequence || raw instanceof Character) {
            return CallToolResult.text(raw.toString());
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return CallToolResult.text(String.valueOf(raw));
        }
        if (raw instanceof ContentBlock) {
            List<ContentBlock> one = new ArrayList<ContentBlock>();
            one.add((ContentBlock) raw);
            return CallToolResult.of(one);
        }
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            boolean allBlocks = true;
            for (Object o : list) if (!(o instanceof ContentBlock)) { allBlocks = false; break; }
            if (allBlocks && !list.isEmpty()) {
                List<ContentBlock> blocks = new ArrayList<ContentBlock>();
                for (Object o : list) blocks.add((ContentBlock) o);
                return CallToolResult.of(blocks);
            }
        }
        boolean objectLike = raw instanceof Map || raw instanceof JsonNode;
        if (!objectLike) {
            return CallToolResult.text(String.valueOf(raw));
        }
        try {
            JsonNode node = raw instanceof JsonNode ? (JsonNode) raw : mapper.valueToTree(raw);
            String json = mapper.writeValueAsString(node);
            if (entry.outputSchemaJson != null && !entry.outputSchemaJson.trim().isEmpty()) {
                CallToolResult structured = CallToolResult.structured(node, json);
                structured.setStructuredContent(node);
                return structured;
            }
            return CallToolResult.text(json);
        } catch (Exception e) {
            return CallToolResult.executionError("tool " + entry.name
                    + " returned a value that cannot be serialized: " + describe(e));
        }
    }

    /**
     * 广告过的 outputSchema 在这里兑现 —— {@link #normalize} 的每一条出口都只经这一处(#61 先接住
     * "工具体自己交回 CallToolResult"那一支, #62 把其余六条出口也并进来).
     *
     * <p>两家官方参照都把违例与缺字段折成 isError(不是 JSON-RPC error): python mcp 1.27.1
     * {@code fastmcp/utilities/func_metadata.py:115-118} 对 CallToolResult 走
     * {@code output_model.model_validate(result.structuredContent)}; TS sdk 1.30.1
     * {@code server/mcp.js} 的 {@code validateToolOutput()} 在缺 structuredContent 时抛
     * "has an output schema but no structured content was provided". 缺字段与内容违例两家同判.
     *
     * <p>isError 不校验: 那是给模型自我纠正的生产者原话, 改判成校验失败会把它盖掉(TS 同顺序).
     */
    private CallToolResult honorDeclaredOutputSchema(McpRegistry.ToolEntry entry, CallToolResult result) {
        String declared = entry.outputSchemaJson;
        if (declared == null || declared.trim().isEmpty() || result.isError()) return result;
        Object structured = result.getStructuredContent();
        if (structured == null) {
            return CallToolResult.executionError("tool " + entry.name
                    + " declares an outputSchema but returned no structuredContent");
        }
        try {
            JsonNode node = structured instanceof JsonNode
                    ? (JsonNode) structured : mapper.valueToTree(structured);
            List<String> violations =
                    validator.validate(parseSchema(declared, entry.name, "outputSchema"), node);
            if (!violations.isEmpty()) {
                return CallToolResult.executionError("tool " + entry.name
                        + " produced output violating its outputSchema: " + join(violations, "; "));
            }
        } catch (Exception e) {
            return CallToolResult.executionError("tool " + entry.name
                    + " returned structuredContent that cannot be serialized: " + describe(e));
        }
        return result;
    }

    private static final class ToolTimeout extends Exception {
        ToolTimeout(String tool) { super(tool); }
    }

    // ------------------------------------------------------------ resources

    private Map<String, Object> resourcesList(JsonNode params) {
        String cursor = textParam(params, "cursor");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        McpRegistry.Page<McpResourceDto> page;
        try {
            page = registry.pageResources(cursor, properties.getPageSize());
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams(e.getMessage());
        }
        for (McpResourceDto r : page.getItems()) out.add(r.toWire());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resources", out);
        if (page.getNextCursor() != null) result.put("nextCursor", page.getNextCursor());
        return result;
    }

    private Map<String, Object> resourceTemplatesList(JsonNode params) {
        String cursor = textParam(params, "cursor");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        McpRegistry.Page<McpResourceDto> page;
        try {
            page = registry.pageResourceTemplates(cursor, properties.getPageSize());
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams(e.getMessage());
        }
        for (McpResourceDto r : page.getItems()) {
            Map<String, Object> w = r.toWire();
            Map<String, Object> tpl = new LinkedHashMap<String, Object>();
            tpl.put("uriTemplate", w.remove("uri"));
            tpl.putAll(w);
            out.add(tpl);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resourceTemplates", out);
        if (page.getNextCursor() != null) result.put("nextCursor", page.getNextCursor());
        return result;
    }

    private Map<String, Object> resourcesRead(JsonNode params) {
        String uri = params == null ? null : textParam(params, "uri");
        if (uri == null || uri.isEmpty()) throw McpException.invalidParams("params.uri is required");
        McpResourceDto resource = registry.lookupResource(uri);
        if (resource == null) throw McpException.resourceNotFound(uri);
        if (resource.getReader() == null) {
            throw McpException.invalidParams("resource " + uri + " is directory-only (no reader)");
        }
        ContentBlock block;
        try {
            block = resource.getReader().read(mapper.convertValue(
                    params.get("arguments") == null ? mapper.createObjectNode() : params.get("arguments"),
                    Map.class));
        } catch (Exception e) {
            throw McpException.invalidParams("resource " + uri + " is not readable: " + describe(e));
        }
        List<Map<String, Object>> contents = new ArrayList<Map<String, Object>>();
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("uri", uri);
        if (resource.getMimeType() != null) c.put("mimeType", resource.getMimeType());
        if (block instanceof ContentBlock.Embedded) {
            Map<String, Object> wire = block.toWire();
            Object inner = wire.get("resource");
            if (inner instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> im = (Map<String, Object>) inner;
                c.putAll(im);
            }
        } else {
            c.put("text", String.valueOf(block.toWire().get("text")));
        }
        contents.add(c);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("contents", contents);
        return result;
    }

    // -------------------------------------------------------------- prompts

    private Map<String, Object> promptsList(JsonNode params) {
        String cursor = textParam(params, "cursor");
        McpRegistry.Page<McpPromptDto> page;
        try {
            page = registry.pagePrompts(cursor, properties.getPageSize());
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams(e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (McpPromptDto p : page.getItems()) out.add(p.toWire());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("prompts", out);
        if (page.getNextCursor() != null) result.put("nextCursor", page.getNextCursor());
        return result;
    }

    private Map<String, Object> promptsGet(JsonNode params) {
        String name = params == null ? null : textParam(params, "name");
        if (name == null || name.isEmpty()) throw McpException.invalidParams("params.name is required");
        McpPromptDto prompt = registry.lookupPrompt(name);
        if (prompt == null) throw McpException.promptNotFound(name);
        JsonNode argNode = params.get("arguments");
        if (argNode == null || !argNode.isObject()) {
            argNode = mapper.createObjectNode();
        }
        for (McpPromptDto.Arg a : prompt.getArguments()) {
            if (a.isRequired() && !argNode.has(a.getName())) {
                throw McpException.invalidParams("missing required prompt argument: " + a.getName());
            }
        }
        List<Map<String, Object>> messages;
        try {
            messages = prompt.getComposer() == null
                    ? new ArrayList<Map<String, Object>>()
                    : prompt.getComposer().compose(mapper.convertValue(argNode, Map.class));
        } catch (Exception e) {
            throw McpException.invalidParams("prompt " + name + " could not be composed: " + describe(e));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (prompt.getDescription() != null) result.put("description", prompt.getDescription());
        result.put("messages", messages);
        return result;
    }

    // -------------------------------------------------------------- logging

    private Map<String, Object> loggingSetLevel(JsonNode params, RequestContext ctx) {
        String level = params == null ? null : textParam(params, "level");
        if (level == null || !isKnownLevel(level)) {
            throw McpException.invalidParams("params.level must be one of debug/info/notice/"
                    + "warning/error/critical/alert/emergency");
        }
        if (ctx.session != null) ctx.session.setLogLevel(level);
        return new LinkedHashMap<String, Object>();
    }

    private static boolean isKnownLevel(String l) {
        return McpSchema.logLevelRank(l) >= 0;
    }

    /** 通过 SSE 流推一条 notifications/message(仅在客户端已建立流时). */
    void emitLog(RequestContext ctx, String level, String data) {
        McpSession s = ctx.session;
        if (s == null || s.emitterCount() == 0) return;
        // setLevel 传的是**门槛**: 客户端要 warning, 服务端就不该再往下喂 info/debug.
        // 这一层以前只判断"有没有流", 于是 logging/setLevel 又是一个存进会话却没人读的值 ——
        // 而 capabilities 里明明广告了 logging.
        int threshold = McpSchema.logLevelRank(s.getLogLevel());
        if (threshold < 0) threshold = McpSchema.logLevelRank(McpSession.DEFAULT_LOG_LEVEL);
        if (McpSchema.logLevelRank(level) < threshold) return;
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("level", level);
        params.put("logger", properties.getServerName());
        params.put("data", data);
        Map<String, Object> n = new LinkedHashMap<String, Object>();
        n.put("jsonrpc", "2.0");
        n.put("method", McpSchema.N_MESSAGE);
        n.put("params", params);
        try {
            s.emit(mapper.writeValueAsString(n));
        } catch (Exception e) {
            log.debug("failed to emit log notification: {}", e.toString());
        }
    }

    /** 广播一条方法级通知到所有在线会话(tools/prompts/resources list_changed 用). */
    public void broadcast(List<McpSession> sessions, String method) {
        Map<String, Object> n = new LinkedHashMap<String, Object>();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        String json;
        try {
            json = mapper.writeValueAsString(n);
        } catch (Exception e) {
            return;
        }
        for (McpSession s : sessions) s.emit(json);
    }

    // ---------------------------------------------------------------- 杂项

    static String textParam(JsonNode params, String key) {
        if (params == null) return null;
        JsonNode v = params.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedThreadFactory(String prefix) { this.prefix = prefix; }

        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
