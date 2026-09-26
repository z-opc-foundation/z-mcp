package com.zifang.z.mcp.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.exception.McpException;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpProtocolHandler;
import com.zifang.z.mcp.core.protocol.McpSchema;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.security.TransportSecurityGuard;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Streamable HTTP 入口.
 *
 * <p>POST   /mcp — 请求与通知. 通知回 202 空体(不是 JSON-RPC 响应);
 *                  请求回 application/json 单对象.
 * <p>GET    /mcp  — 服务端→客户端的 SSE 流(list_changed / messages 推送). 可选, 关掉时按协议回 405.
 * <p>DELETE /mcp  — 终止会话.
 *
 * <p>传输层职责(与 {@link McpProtocolHandler} 的方法分发严格分开):
 * Accept 校验(406)、Content-Type 校验(415)、Origin/Host 校验(403)、bearer(401)、限流(429)、
 * Mcp-Session-Id 生命周期(缺失 400 / 不认识 404)、MCP-Protocol-Version(不支持 400)、
 * JSON 解析失败(-32700)、批量数组体(2025-06-18 起已删除, 回 -32600)、请求体上限(413).
 *
 * <p>类名与 {@code @PostMapping("/mcp")} 被 z-opc 的 L3 反射用例钉住
 * (ZAgentAiL3StarterCentralPullIT), 改名前要先改那里.
 */
@RestController
@RequestMapping("${z.mcp.base-path:}")
public class JsonRpcController {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcController.class);

    private final McpRegistry registry;
    private final McpProperties properties;
    private final McpProtocolHandler handler;
    private final McpSessionStore sessions;
    private final TransportSecurityGuard guard;
    private final ObjectMapper mapper;

    public JsonRpcController(McpRegistry registry, McpProperties properties,
                             McpProtocolHandler handler, McpSessionStore sessions,
                             TransportSecurityGuard guard, ObjectMapper mapper) {
        this.registry = registry;
        this.properties = properties;
        this.handler = handler;
        this.sessions = sessions;
        this.guard = guard;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ POST

    @PostMapping("/mcp")
    public ResponseEntity<String> post(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        String protocolHeader = request.getHeader(McpSchema.H_PROTOCOL_VERSION);
        String sessionId = header(request, McpSchema.H_SESSION);

        // 1) 传输安全: Origin / Host / bearer
        TransportSecurityGuard.Violation v = guard.checkRequest(request);
        if (v != null) {
            return denied(v).contentType(MediaType.APPLICATION_JSON).body(errorBody(
                    McpException.INVALID_REQUEST, v.message));
        }

        // 2) Accept: 客户端 MUST 同时可收 json 与 event-stream; 缺省按 */* 处理
        if (!acceptOk(accept)) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.INVALID_REQUEST,
                            "Accept must include both application/json and text/event-stream"));
        }

        // 2b) Content-Type: 体是 JSON, 所以只有 application/json 说得通.
        //     不判它的话 text/plain 的体照样被当 JSON 解析并执行 —— 一个"什么都收"的入口,
        //     中间件配置错了(典型是网关把请求重写成 form)会在服务端表现为"成功", 没人去查那一层.
        if (!contentTypeOk(request.getContentType())) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.INVALID_REQUEST,
                            "Content-Type must be application/json"));
        }

        // 3) 限流(按会话, 无会话时按来源)
        String bucketKey = sessionId != null ? sessionId : remote(request);
        if (!guard.tryAcquire(bucketKey)) {
            guard.sweepBuckets();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.INVALID_REQUEST, "rate limit exceeded"));
        }

        // 4) 请求体
        byte[] raw;
        try {
            raw = readBounded(request);
        } catch (BodyTooLarge e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.INVALID_REQUEST, e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.PARSE_ERROR, "cannot read request body"));
        }

        JsonNode body;
        try {
            body = mapper.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.PARSE_ERROR, "malformed JSON: " + e.getMessage()));
        }
        if (body == null || body.isNull()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.PARSE_ERROR, "empty request body"));
        }
        if (body.isArray()) {
            // 批量请求在 2025-03-26 加入、2025-06-18 删除; 明确拒绝而不是静默处理第一个
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.INVALID_REQUEST,
                            "batch requests are not supported since protocol revision 2025-06-18"));
        }
        if (!body.isObject()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(McpException.INVALID_REQUEST, "request must be a JSON object"));
        }

        boolean isInitialize = McpSchema.M_INITIALIZE.equals(
                body.path("method").asText());

        // 5) 会话解析
        McpSessionStore.McpSession session = null;
        if (properties.getSession().isEnabled()) {
            if (isInitialize) {
                if (sessionId != null) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(errorBody(McpException.INVALID_REQUEST,
                                    "initialize must not carry an Mcp-Session-Id"));
                }
            } else {
                if (sessionId == null) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(errorBody(McpException.INVALID_REQUEST,
                                    "missing " + McpSchema.H_SESSION + " header"));
                }
                session = sessions.find(sessionId);
                if (session == null) {
                    // 协议: 服务端不再认识该 id ⇒ 404, 客户端据此重新 initialize
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(errorBody(McpException.INVALID_REQUEST,
                                    "unknown or expired session"));
                }
            }
        }

        // 6) 协议版本头: initialize 之后每个请求都算, 缺失按 2025-03-26 假定
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        ctx.session = session;
        ctx.remoteAddress = remote(request);
        if (!isInitialize) {
            if (isUnsupportedVersion(protocolHeader)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorBody(McpException.UNSUPPORTED_PROTOCOL_VERSION,
                                "unsupported MCP-Protocol-Version " + protocolHeader
                                        + ", this server supports " + McpSchema.SUPPORTED_VERSIONS));
            }
            String declared = protocolHeader != null ? protocolHeader : McpSchema.DEFAULT_ASSUMED;
            if (session != null && session.getProtocolVersion() != null
                    && protocolHeader != null && !protocolHeader.equals(session.getProtocolVersion())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorBody(McpException.INVALID_REQUEST,
                                "MCP-Protocol-Version " + protocolHeader
                                        + " does not match negotiated " + session.getProtocolVersion()));
            }
            ctx.protocolVersion = session != null ? session.getProtocolVersion() : declared;
        }

        // 7) 交给方法层分发
        McpProtocolHandler.Reply reply = handler.handle(body, ctx);

        if (isInitialize && reply.envelope != null && !reply.envelope.isError()) {
            if (properties.getSession().isEnabled()) {
                String minted = sessions.newSessionId();
                McpSessionStore.McpSession created = sessions.create(minted, ctx.negotiatedVersion);
                if (created == null) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(errorBody(McpException.INTERNAL_ERROR, "session capacity reached"));
                }
                return okJson().header(McpSchema.H_SESSION, minted).body(replyBody(reply));
            }
        }
        if (session != null && ctx.negotiatedVersion != null) {
            session.setProtocolVersion(ctx.negotiatedVersion);
        }
        if (reply.notification) {
            return ResponseEntity.accepted().build();
        }
        return okJson().body(replyBody(reply));
    }

    // ------------------------------------------------------------------- GET

    /**
     * 服务端主动流. 客户端可用它接收 notifications/*.
     *
     * <p>带 {@code Last-Event-ID} 时按协议续传: 会话缓冲里还留着的补发, 已经被淘汰的明确回 400
     * (而不是假装能续、给一条少了几条事件的流)。协议只说客户端 SHOULD 用 GET + Last-Event-ID
     * 重来, 没有规定补不上时怎么办; 这里选 400, 因为它能让客户端立刻退回"不续传"的用法,
     * 而静默少发是查不出来的那种错。
     */
    @GetMapping("/mcp")
    public ResponseEntity<SseEmitter> stream(HttpServletRequest request) {
        TransportSecurityGuard.Violation v = guard.checkRequest(request);
        if (v != null) {
            return denied(v).build();
        }
        if (isUnsupportedVersion(header(request, McpSchema.H_PROTOCOL_VERSION))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!acceptsEventStream(header(request, "Accept"))) {
            // 405, 不是 406: 协议在 GET 这一路只点了两个出口 —— 要么 text/event-stream,
            // 要么 405 Method Not Allowed(2025-06-18 与 2025-11-25 皆如此, 全文没有 406).
            // 而 406 在真客户端那里是"这条 MCP 连接坏了"级别的信号(Gemini CLI / LibreChat 都
            // 有报告), 405 才是它们认得的"这里没有推送流, 继续用 POST".
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        if (!properties.getSse().isEnabled()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        if (!properties.getSession().isEnabled()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        String sessionId = header(request, McpSchema.H_SESSION);
        if (sessionId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Long resumeFrom = null;
        String lastEventId = header(request, McpSchema.H_LAST_EVENT_ID);
        if (lastEventId != null) {
            // 事件 id 是我们自己发的十进制数; 别的一律按"这客户端不可信"处理.
            try {
                resumeFrom = Long.valueOf(lastEventId);
            } catch (NumberFormatException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            if (resumeFrom.longValue() < 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
        }
        McpSessionStore.McpSession session = sessions.find(sessionId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        final McpSessionStore.McpSession s = session;
        // 有人开流了才需要心跳线程 —— 只用 POST 的客户端一个都不该多占
        sessions.startKeepAliveIfNeeded();
        SseEmitter emitter = new SseEmitter(properties.getSse().getTimeoutMs());
        emitter.onCompletion(new Runnable() {
            @Override public void run() { s.removeEmitter(emitter); }
        });
        emitter.onTimeout(new Runnable() {
            @Override public void run() { s.removeEmitter(emitter); emitter.complete(); }
        });
        int replayed;
        try {
            replayed = s.attach(emitter, resumeFrom, McpSessionStore.STREAM_OPEN_COMMENT,
                    properties.getSse().getRetryMs());
        } catch (Exception e) {
            s.removeEmitter(emitter);
            // 一个 500 不留一行字, 是这条路径上最难查的部分: 客户端只看到流被结束, 而服务端
            // 从没说过为什么。attach 里的写出与补发判定都可能抛, 所以这一行是唯一的归因点。
            log.warn("session {} failed to open an SSE stream: {}", sessionId, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (replayed < 0) {
            // 缓冲已经放不下客户端要的那个位置: 与其给一条注定少事件的流, 不如让它重新来
            log.info("session {} cannot resume from Last-Event-ID {}: events already dropped",
                    sessionId, lastEventId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (log.isDebugEnabled()) {
            log.debug("session {} stream opened, replayed {} buffered event(s)", sessionId,
                    Integer.valueOf(replayed));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                // nginx 默认会把整条流攒起来(攒够 proxy_buffer_size 才吐一次), 推送于是"配好了却永远到不了";
                // 这个头是让反代逐条透传的开关, 而 SSE 前面挂着反代才是生产常态.
                .header("X-Accel-Buffering", "no")
                .header(McpSchema.H_SESSION, s.getId())
                .body(emitter);
    }

    // ---------------------------------------------------------------- DELETE

    @DeleteMapping("/mcp")
    public ResponseEntity<String> terminate(HttpServletRequest request) {
        TransportSecurityGuard.Violation v = guard.checkRequest(request);
        if (v != null) return denied(v).build();
        if (isUnsupportedVersion(header(request, McpSchema.H_PROTOCOL_VERSION))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!properties.getSession().isEnabled()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }
        String sessionId = header(request, McpSchema.H_SESSION);
        if (sessionId == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        if (sessions.find(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        guard.forget(sessionId);
        sessions.terminate(sessionId);
        return ResponseEntity.ok().build();
    }

    // ---------------------------------------------------------------- 内部件

    /**
     * {@code GET /mcp/info} 的只读自检(不经 JSON-RPC): 便于探针/负载均衡器识别端点存活.
     *
     * <p>它报的是工具/资源/会话计数与服务端版本 —— 拓扑指纹. 0.2.0 收口控制面时闸只装在
     * {@code z-mcp-admin} 那组映射上, 这条同前缀的路径连请求对象都不接, 于是配了
     * {@code security.bearer-tokens} 之后它仍对任意来源匿名开放. 现在与另外两扇门共用
     * {@link TransportSecurityGuard}: 一把闸要么三面都关得一样, 要么等于没关.
     */
    @GetMapping("/mcp/info")
    public ResponseEntity<?> info(HttpServletRequest request) {
        TransportSecurityGuard.Violation v = guard.checkRequest(request);
        if (v != null) return denied(v).build();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", properties.getServerName());
        m.put("version", properties.getServerVersion());
        m.put("protocolVersions", McpSchema.SUPPORTED_VERSIONS);
        m.put("transport", "streamable-http");
        m.put("tools", registry.toolCount());
        m.put("resources", registry.resourceCount());
        m.put("prompts", registry.promptCount());
        m.put("sessions", sessions.count());
        return ResponseEntity.ok((Object) Collections.unmodifiableMap(m));
    }

    private ResponseEntity.BodyBuilder okJson() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * 客户端在 {@code MCP-Protocol-Version} 里声明的版本是不是我们接不住的.
     *
     * <p>不带头是允许的(协议明文要求按 {@link McpSchema#DEFAULT_ASSUMED} 假定, 老客户端就不带),
     * 而**带了却不支持**必须回 400 —— 静默按自己最高的版本服务, 客户端会以为自己那一路走通了。
     * 判据放在请求入口而不是握手成功之后: 无状态模式({@code session.enabled=false})根本没有会话,
     * 挂在"跟协商好的值比对"那一支上就永远轮不到它。POST / GET(流) / DELETE 三个入口共用。
     */
    private static boolean isUnsupportedVersion(String protocolHeader) {
        return protocolHeader != null && !McpSchema.isSupported(protocolHeader);
    }

    /**
     * 违规响应. 401 一律带上 {@code WWW-Authenticate}(RFC 6750 要求 401 附挑战,
     * 而挑战里的 {@code error} 值是给客户端的指令: 去取凭证 / 换令牌再来 / 别重试).
     *
     * <p>POST、GET(流)、DELETE 三条路共用这一个出口. 以前只有 POST 带头, 于是同一道闸
     * 在 {@code GET /mcp} 上回"裸 401" —— 从流那一路进来的客户端拿不到"该怎么带凭证",
     * 而控制面 {@code /mcp/admin/*} 是带头的, 两扇门形状不一致.
     */
    private static ResponseEntity.BodyBuilder denied(TransportSecurityGuard.Violation v) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(v.httpStatus);
        if (v.wwwAuthenticate != null) b.header(HttpHeaders.WWW_AUTHENTICATE, v.wwwAuthenticate);
        return b;
    }

    private String replyBody(McpProtocolHandler.Reply reply) {
        if (reply.envelope == null) return "";
        try {
            return mapper.writeValueAsString(reply.envelope.toWire());
        } catch (Exception e) {
            log.error("failed to serialize JSON-RPC response", e);
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                    + "\"message\":\"internal error\"}}";
        }
    }

    private String errorBody(int code, String message) {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("code", Integer.valueOf(code));
        err.put("message", message);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("jsonrpc", "2.0");
        body.put("id", null);
        body.put("error", err);
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600}}";
        }
    }

    /**
     * 2025-06-18 "Sending Messages" 第 2 条是 MUST: Accept 要同时列出 json 与 event-stream.
     * 只声明其一的客户端处理不了服务端选用的另一种形态, 所以这里不放宽成"任一即可";
     * 不带 Accept(非 HTTP 客户端)与 *&#47;* 仍然放行.
     */
    static boolean acceptOk(String accept) {
        if (accept == null || accept.trim().isEmpty()) return true;
        String a = accept.toLowerCase();
        if (a.contains("*/*")) return true;
        return a.contains("application/json") && a.contains("text/event-stream");
    }

    /**
     * GET 这一路服务端只能回流, 所以门槛比 POST 那条宽得多的窄: 表明收得了 text/event-stream
     * (或干脆不带 Accept / 带 *&#47;*)就放行; 只列 application/json 的客户端是在说"我不收流",
     * 那就别回一个它根本不会去读的 200 —— 静默开一条没人看的流, 配置者只会以为推送生效了.
     *
     * <p>注意这条**不是**协议的 MUST: 协议对 GET 只说了"要么 text/event-stream 要么 405",
     * 对 Accept 列两种类型的 MUST 属于 POST 那一路(见 {@link #acceptOk}). 用 405 承接它是
     * 协议给的出口, 不是我给它找的.
     */
    static boolean acceptsEventStream(String accept) {
        if (accept == null || accept.trim().isEmpty()) return true;
        String a = accept.toLowerCase();
        return a.contains("*/*") || a.contains("text/event-stream");
    }

    /**
     * 2025-06-18 "Sending Messages" 第 3 条: POST 体 MUST 是 application/json,
     * 不支持的 Content-Type MUST 以 415 拒.
     *
     * <p>只取第一个媒体类型、剥掉 {@code ;charset=...} 这类参数、大小写不敏感 —— 最后这点比
     * 参照实现宽松: 官方 python SDK 用 {@code part == "application/json"} 逐字比,
     * 于是 {@code APPLICATION/JSON} 会被它自己拒掉, 而 RFC 2045 说媒体类型不区分大小写.
     * 不带 Content-Type 一律拒(HTTP 里它缺省意味着"发送方没声称过任何东西", 这里没有理由替他猜).
     */
    static boolean contentTypeOk(String contentType) {
        if (contentType == null) return false;
        String first = contentType.split(";", 2)[0].trim().toLowerCase();
        // 客户端偶尔把多个类型用逗号串成一个头, 参照实现也按逗号再切一次
        for (String part : first.split(",")) {
            if (part.trim().equals("application/json")) return true;
        }
        return false;
    }

    private static String header(HttpServletRequest r, String name) {
        String v = r.getHeader(name);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static String remote(HttpServletRequest r) {
        String fwd = r.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.trim().isEmpty()) {
            int comma = fwd.indexOf(',');
            return comma > 0 ? fwd.substring(0, comma).trim() : fwd.trim();
        }
        return r.getRemoteAddr();
    }

    static byte[] readBounded(HttpServletRequest r) throws java.io.IOException, BodyTooLarge {
        int cap = 4 * 1024 * 1024;
        String declared = r.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > cap) throw new BodyTooLarge(
                        "request body exceeds " + cap + " bytes");
            } catch (NumberFormatException ignore) {
                // 非法 Content-Length 交给容器处理
            }
        }
        InputStream in = r.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > cap) throw new BodyTooLarge("request body exceeds " + cap + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static final class BodyTooLarge extends Exception {
        BodyTooLarge(String m) { super(m); }
    }
}
