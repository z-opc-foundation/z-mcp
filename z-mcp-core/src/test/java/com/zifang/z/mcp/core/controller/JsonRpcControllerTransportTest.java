package com.zifang.z.mcp.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.exception.McpException;
import com.zifang.z.mcp.core.builtin.BuiltinTools;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpProtocolHandler;
import com.zifang.z.mcp.core.protocol.McpSchema;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.security.TransportSecurityGuard;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Streamable HTTP 传输层测试: 状态码、头、会话生命周期、跨源与限流.
 *
 * <p>方法级语义在 {@code McpProtocolHandlerTest} 里覆盖, 这里只管传输层,
 * 两边共用一个真实客户端时序: initialize → notifications/initialized → tools/*.
 */
public class JsonRpcControllerTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpProperties properties;
    private McpRegistry registry;
    private McpSessionStore sessions;
    private TransportSecurityGuard guard;
    private McpProtocolHandler handler;
    private JsonRpcController controller;

    @Before
    public void setUp() {
        properties = new McpProperties();
        registry = new McpRegistry();
        BuiltinTools.registerAll(registry, properties, mapper);
        sessions = new McpSessionStore(properties);
        guard = new TransportSecurityGuard(properties);
        handler = new McpProtocolHandler(registry, properties, mapper);
        controller = new JsonRpcController(registry, properties, handler, sessions, guard, mapper);
    }

    @After
    public void tearDown() {
        handler.destroy();
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequest post(String body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/mcp");
        r.addHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        r.addHeader(HttpHeaders.HOST, "localhost:8080");
        r.setContentType(MediaType.APPLICATION_JSON_VALUE);
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    /**
     * {@code contentType == null} 表示"这个头干脆不带".
     *
     * <p>不带时是**重新造**请求而不是把 {@link MockHttpServletRequest#removeHeader} 后的那条拿来用:
     * 后者在 Spring 5.3 上对 Content-Type 是空操作(它只对普通头生效, Content-Type 有专门的字段),
     * 于是"去掉头"这一维会被静默跳过、用例读到的还是 application/json ⇒ 假绿.
     */
    private MockHttpServletRequest postWithContentType(String contentType, String body) {
        if (contentType == null) {
            MockHttpServletRequest r = new MockHttpServletRequest("POST", "/mcp");
            r.addHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream");
            r.addHeader(HttpHeaders.HOST, "localhost:8080");
            r.setContent(body.getBytes(StandardCharsets.UTF_8));
            return r;
        }
        MockHttpServletRequest r = post(body);
        r.setContentType(contentType);
        return r;
    }

    private static String initBody(String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"" + version + "\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0\"}}}";
    }

    private String initializeAndTakeSession() {
        ResponseEntity<String> res = controller.post(post(initBody("2025-06-18")));
        assertEquals(HttpStatus.OK.value(), res.getStatusCodeValue());
        String sid = res.getHeaders().getFirst(McpSchema.H_SESSION);
        assertNotNull("initialize 成功必须签发 Mcp-Session-Id", sid);
        return sid;
    }

    private ResponseEntity<String> withSession(String sid, String body) {
        MockHttpServletRequest r = post(body);
        r.addHeader(McpSchema.H_SESSION, sid);
        r.addHeader(McpSchema.H_PROTOCOL_VERSION, "2025-06-18");
        return controller.post(r);
    }

    private JsonNode bodyOf(ResponseEntity<String> res) throws Exception {
        assertNotNull("响应体不应为空", res.getBody());
        return mapper.readTree(res.getBody());
    }

    // ------------------------------------------------------- 会话生命周期

    @Test
    public void initialize_mints_session_and_negotiates_version() throws Exception {
        ResponseEntity<String> res = controller.post(post(initBody("2025-06-18")));
        assertEquals(200, res.getStatusCodeValue());
        assertEquals("2025-06-18",
                bodyOf(res).path("result").path("protocolVersion").asText());
        String sid = res.getHeaders().getFirst(McpSchema.H_SESSION);
        assertNotNull(sid);
        assertTrue("会话 id 必须是不可猜测的长随机串", sid.length() >= 16);
        assertEquals(1, sessions.count());
    }

    @Test
    public void initialize_must_not_carry_an_existing_session() {
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        r.addHeader(McpSchema.H_SESSION, "someone-elses-session");
        ResponseEntity<String> res = controller.post(r);
        assertEquals(HttpStatus.BAD_REQUEST.value(), res.getStatusCodeValue());
    }

    @Test
    public void requests_without_session_are_rejected_with_400() {
        ResponseEntity<String> res = controller.post(post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), res.getStatusCodeValue());
    }

    @Test
    public void unknown_or_expired_session_is_404_so_client_reinitializes() throws Exception {
        String sid = initializeAndTakeSession();
        assertEquals("通知必须是 202 空体",
                HttpStatus.ACCEPTED.value(),
                withSession(sid, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                        .getStatusCodeValue());
        ResponseEntity<String> res = withSession("totally-made-up-id",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
        assertEquals("协议规定: 不认识的会话 id 用 HTTP 404 而不是 JSON-RPC 码",
                HttpStatus.NOT_FOUND.value(), res.getStatusCodeValue());
    }

    @Test
    public void delete_terminates_the_session() {
        String sid = initializeAndTakeSession();
        MockHttpServletRequest del = new MockHttpServletRequest("DELETE", "/mcp");
        del.addHeader(McpSchema.H_SESSION, sid);
        del.addHeader(HttpHeaders.HOST, "localhost:8080");
        assertEquals(HttpStatus.OK.value(), controller.terminate(del).getStatusCodeValue());
        assertEquals(0, sessions.count());

        MockHttpServletRequest again = new MockHttpServletRequest("DELETE", "/mcp");
        again.addHeader(McpSchema.H_SESSION, sid);
        assertEquals(HttpStatus.NOT_FOUND.value(), controller.terminate(again).getStatusCodeValue());
    }

    @Test
    public void protocol_version_header_must_match_the_negotiated_one() {
        String sid = initializeAndTakeSession();
        MockHttpServletRequest r = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}");
        r.addHeader(McpSchema.H_SESSION, sid);
        r.addHeader(McpSchema.H_PROTOCOL_VERSION, "2024-11-05");
        assertEquals(HttpStatus.BAD_REQUEST.value(), controller.post(r).getStatusCodeValue());
    }

    /**
     * 版本声明里"我们接不住"这一维, 与"跟会话里协商好的不一样"是两件事, 得分开报:
     * 前者客户端除了换版本号没有任何别的可做, 后者才需要知道协商好的是哪个.
     *
     * <p>猎物用真实存在的 {@code 2026-07-28}(官方仓库已打 tag 的下一版协议), 而不是编一个
     * 假串 —— 本仓迟早要面对的就是这一类客户端。
     */
    @Test
    public void an_unsupported_version_header_is_rejected_even_without_a_session() throws Exception {
        properties.getSession().setEnabled(false);
        properties.setRequireInitialized(false);

        ResponseEntity<String> res = controller.post(withVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}", "2026-07-28"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), res.getStatusCodeValue());
        JsonNode err = bodyOf(res).path("error");
        assertEquals("不支持的版本要用它自己的码, 不能和'请求不成形'混成一格",
                McpException.UNSUPPORTED_PROTOCOL_VERSION, err.path("code").asInt());
        assertTrue("消息里必须端出支持范围, 否则客户端只能猜要改成什么",
                err.path("message").asText().contains("2025-11-25"));

        // 正对照: 换个受支持的版本头, 同一份请求必须照常 200 ——
        // 没有这一条, 上面那三行断言靠"无状态下一律 400"也能全绿。
        assertEquals(HttpStatus.OK.value(), controller.post(withVersion(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}", "2025-06-18"))
                .getStatusCodeValue());
    }

    /** 校验得长在每一个请求入口上, 而不是只长在 POST 上: 流这一路同样是握手之后的请求. */
    @Test
    public void the_stream_rejects_an_unsupported_version_without_opening_or_killing() {
        String sid = initializeAndTakeSession();
        properties.getSse().setEnabled(true);

        MockHttpServletRequest bad = new MockHttpServletRequest("GET", "/mcp");
        bad.addHeader(McpSchema.H_SESSION, sid);
        bad.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        bad.addHeader(McpSchema.H_PROTOCOL_VERSION, "2026-07-28");
        ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> res =
                controller.stream(bad);
        assertEquals(HttpStatus.BAD_REQUEST.value(), res.getStatusCodeValue());
        assertEquals("被拒的开流不许真的挂上一条 emitter", 0, sessions.find(sid).emitterCount());
        assertEquals("也不许顺手把会话关掉", 1, sessions.count());

        MockHttpServletRequest good = new MockHttpServletRequest("GET", "/mcp");
        good.addHeader(McpSchema.H_SESSION, sid);
        good.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        good.addHeader(McpSchema.H_PROTOCOL_VERSION, "2025-06-18");
        assertEquals("同一份请求换个受支持的版本头就该开出流(前面那条不是无条件 400)",
                HttpStatus.OK.value(), controller.stream(good).getStatusCodeValue());
        assertEquals(1, sessions.find(sid).emitterCount());
    }

    /** DELETE 同上一式: 版本不对要在"关掉会话"之前停下. */
    @Test
    public void terminate_rejects_an_unsupported_version_without_killing_the_session() {
        String sid = initializeAndTakeSession();

        MockHttpServletRequest bad = new MockHttpServletRequest("DELETE", "/mcp");
        bad.addHeader(McpSchema.H_SESSION, sid);
        bad.addHeader(McpSchema.H_PROTOCOL_VERSION, "2026-07-28");
        assertEquals(HttpStatus.BAD_REQUEST.value(), controller.terminate(bad).getStatusCodeValue());
        assertEquals("被拒的 DELETE 不许已经把会话关掉了", 1, sessions.count());

        MockHttpServletRequest good = new MockHttpServletRequest("DELETE", "/mcp");
        good.addHeader(McpSchema.H_SESSION, sid);
        good.addHeader(McpSchema.H_PROTOCOL_VERSION, "2025-06-18");
        assertEquals(HttpStatus.OK.value(), controller.terminate(good).getStatusCodeValue());
        assertEquals(0, sessions.count());
    }

    /**
     * 握手那一趟由 body 里的 {@code protocolVersion} 说了算: 协议要求带版本头的是
     * "initialize 之后"的请求, 握手时头与 body 矛盾不该把握手打回去(那只会让客户端永远建不起会话).
     */
    @Test
    public void the_version_header_does_not_govern_the_initialize_handshake() throws Exception {
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        r.addHeader(McpSchema.H_PROTOCOL_VERSION, "2026-07-28");
        ResponseEntity<String> res = controller.post(r);
        assertEquals(HttpStatus.OK.value(), res.getStatusCodeValue());
        assertEquals("2025-06-18", bodyOf(res).path("result").path("protocolVersion").asText());
    }

    /**
     * 请求一个更新的版本时协议要的是"回一个你自己支持的版本", 不是报错 —— 走不走由客户端判断。
     * 这条钉住的正是被删掉的那个运行时分支: 兜底值永远在支持表里, 所以那种 400 是发不出去的。
     */
    @Test
    public void a_newer_requested_version_is_downgraded_and_announced_not_rejected() throws Exception {
        ResponseEntity<String> res = controller.post(post(initBody("2026-07-28")));
        assertEquals(HttpStatus.OK.value(), res.getStatusCodeValue());
        assertEquals("2025-11-25", bodyOf(res).path("result").path("protocolVersion").asText());
    }

    private MockHttpServletRequest withVersion(String body, String version) {
        MockHttpServletRequest r = post(body);
        r.addHeader(McpSchema.H_PROTOCOL_VERSION, version);
        return r;
    }

    @Test
    public void stateless_mode_serves_requests_without_any_session() throws Exception {
        properties.getSession().setEnabled(false);
        ResponseEntity<String> res = controller.post(post(initBody("2025-06-18")));
        assertEquals(200, res.getStatusCodeValue());
        assertNull("关掉会话后不应再发 Mcp-Session-Id",
                res.getHeaders().getFirst(McpSchema.H_SESSION));

        properties.setRequireInitialized(false);
        ResponseEntity<String> list = controller.post(post(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}"));
        assertEquals(200, list.getStatusCodeValue());
        assertTrue(bodyOf(list).path("result").path("tools").isArray());
    }

    // ------------------------------------------------------------- 通知 / 批量

    @Test
    public void notification_is_accepted_with_202_and_no_body() {
        String sid = initializeAndTakeSession();
        ResponseEntity<String> res = withSession(sid,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(HttpStatus.ACCEPTED.value(), res.getStatusCodeValue());
        assertNull("notification 不得带响应体", res.getBody());
    }

    @Test
    public void an_explicit_null_id_is_an_invalid_request_not_a_silent_notification() throws Exception {
        String sid = initializeAndTakeSession();
        ResponseEntity<String> res = withSession(sid,
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}");
        // 请求里的 id 是 MUST NOT 为 null 的: null 是 JSON-RPC 留给"响应方连 id 都取不到"那个场合。
        // 把它当成"没有 id"静默 202, 客户端就是在为一条永远不会来的答案白等到读超时。
        assertEquals("显式 id:null 不是通知, 必须给回执", HttpStatus.OK.value(), res.getStatusCodeValue());
        JsonNode body = bodyOf(res);
        assertEquals(-32600, body.path("error").path("code").asInt());
        assertTrue("回执里 id 必须原样是 null", body.has("id"));
        assertTrue(body.path("id").isNull());
        assertFalse("error 与 result 不得同时出现", body.has("result"));
        // 正对照: 真的没带 id 仍然是 202 空体。少了这两句, "把带 method 的一律判非法"也能蒙过这条。
        ResponseEntity<String> note = withSession(sid, "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}");
        assertEquals(HttpStatus.ACCEPTED.value(), note.getStatusCodeValue());
        assertNull(note.getBody());
    }

    @Test
    public void batch_body_is_rejected_because_2025_06_18_removed_it() throws Exception {
        String sid = initializeAndTakeSession();
        ResponseEntity<String> res = withSession(sid, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertEquals(HttpStatus.BAD_REQUEST.value(), res.getStatusCodeValue());
        assertEquals(-32600, bodyOf(res).path("error").path("code").asInt());
    }

    @Test
    public void malformed_json_is_parse_error_32700() throws Exception {
        ResponseEntity<String> res = controller.post(post("{ not json"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), res.getStatusCodeValue());
        assertEquals(-32700, bodyOf(res).path("error").path("code").asInt());
    }

    @Test
    public void oversized_body_is_413() {
        MockHttpServletRequest r = post("{}");
        r.addHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(8 * 1024 * 1024));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), controller.post(r).getStatusCodeValue());
    }

    // ------------------------------------------------------------------ 安全

    @Test
    public void cross_origin_is_blocked_by_default_dns_rebinding_guard() {
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        r.removeHeader(HttpHeaders.HOST);
        r.addHeader(HttpHeaders.ORIGIN, "http://attacker.example");
        r.addHeader(HttpHeaders.HOST, "localhost:8080");
        ResponseEntity<String> res = controller.post(r);
        assertEquals(HttpStatus.FORBIDDEN.value(), res.getStatusCodeValue());
    }

    @Test
    public void same_origin_passes_the_default_policy() {
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        r.addHeader(HttpHeaders.ORIGIN, "http://localhost:8080");
        assertEquals(200, controller.post(r).getStatusCodeValue());
    }

    @Test
    public void explicit_origin_allowlist_replaces_same_origin_rule() {
        properties.getSecurity().setAllowedOrigins(Collections.singletonList("https://console.z.test"));
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        r.addHeader(HttpHeaders.ORIGIN, "https://console.z.test");
        r.removeHeader(HttpHeaders.HOST);
        r.addHeader(HttpHeaders.HOST, "mcp.internal:8080");
        assertEquals(200, controller.post(r).getStatusCodeValue());

        MockHttpServletRequest bad = post(initBody("2025-06-18"));
        bad.addHeader(HttpHeaders.ORIGIN, "https://evil.z.test");
        assertEquals(HttpStatus.FORBIDDEN.value(), controller.post(bad).getStatusCodeValue());
    }

    @Test
    public void host_allowlist_blocks_rebinding_even_with_matching_origin() {
        properties.getSecurity().setAllowedHosts(Collections.singletonList("mcp.internal"));
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        r.addHeader(HttpHeaders.ORIGIN, "http://evil-dns.example");
        r.removeHeader(HttpHeaders.HOST);
        r.addHeader(HttpHeaders.HOST, "evil-dns.example");
        assertEquals(HttpStatus.BAD_REQUEST.value(), controller.post(r).getStatusCodeValue());
    }

    @Test
    public void bearer_token_gate_emits_www_authenticate() throws Exception {
        properties.getSecurity().setBearerTokens(Collections.singletonList("s3cret"));
        MockHttpServletRequest anon = post(initBody("2025-06-18"));
        ResponseEntity<String> res = controller.post(anon);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), res.getStatusCodeValue());
        assertEquals("没带凭证应是裸挑战(RFC 6750), 让客户端去取凭证而不是以为令牌坏了",
                "Bearer realm=\"z-mcp\"", res.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        MockHttpServletRequest authed = post(initBody("2025-06-18"));
        authed.addHeader(HttpHeaders.AUTHORIZATION, "Bearer s3cret");
        assertEquals(200, controller.post(authed).getStatusCodeValue());

        MockHttpServletRequest wrong = post(initBody("2025-06-18"));
        wrong.addHeader(HttpHeaders.AUTHORIZATION, "Bearer nope");
        ResponseEntity<String> wrongRes = controller.post(wrong);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), wrongRes.getStatusCodeValue());
        assertEquals("Bearer realm=\"z-mcp\", error=\"invalid_token\"",
                wrongRes.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        MockHttpServletRequest malformed = post(initBody("2025-06-18"));
        malformed.addHeader(HttpHeaders.AUTHORIZATION, "Basic ZGVmYXVsdA==");
        ResponseEntity<String> malformedRes = controller.post(malformed);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), malformedRes.getStatusCodeValue());
        assertEquals("Bearer realm=\"z-mcp\", error=\"invalid_request\"",
                malformedRes.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    public void every_data_plane_method_carries_the_same_challenge() {
        // 一道闸、三个入口(POST 数据面 / GET 流 / DELETE 终止), 挑战必须同形。
        // 修之前只有 POST 带头: 从 GET 那一路进来的客户端拿到"裸 401", 学不到该怎么带凭证,
        // 而控制面 /mcp/admin/* 是带头的 —— 于是"两扇门同一个策略"这条断言只比状态码时看不出来。
        String sid = initializeAndTakeSession();
        properties.getSecurity().setBearerTokens(Collections.singletonList("s3cret"));
        String bare = "Bearer realm=\"z-mcp\"";

        assertEquals(bare, controller.post(post(initBody("2025-06-18")))
                .getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(bare, controller.stream(new MockHttpServletRequest("GET", "/mcp"))
                .getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(bare, controller.terminate(new MockHttpServletRequest("DELETE", "/mcp"))
                .getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        // 无体那两路同样保留三种形状的区分能力(POST 那路撞 401 时部分 HTTP client 读不到响应,
        // 所以真 socket 探针走 DELETE, 这里也在同一层把另两种值钉住)
        MockHttpServletRequest wrong = new MockHttpServletRequest("DELETE", "/mcp");
        wrong.addHeader(HttpHeaders.AUTHORIZATION, "Bearer nope");
        assertEquals("Bearer realm=\"z-mcp\", error=\"invalid_token\"",
                controller.terminate(wrong).getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        MockHttpServletRequest malformed = new MockHttpServletRequest("GET", "/mcp");
        malformed.addHeader(HttpHeaders.AUTHORIZATION, "Basic ZGVmYXVsdA==");
        assertEquals("Bearer realm=\"z-mcp\", error=\"invalid_request\"",
                controller.stream(malformed).getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        // 对照: 好凭证仍然打得开, 否则上面三条会被"永远 401"糊弄过去
        MockHttpServletRequest good = new MockHttpServletRequest("DELETE", "/mcp");
        good.addHeader(HttpHeaders.AUTHORIZATION, "Bearer s3cret");
        good.addHeader(McpSchema.H_SESSION, sid);
        assertEquals(HttpStatus.OK.value(), controller.terminate(good).getStatusCodeValue());
    }

    @Test
    public void rate_limit_returns_429_with_retry_after() {
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setRequestsPerMinute(1);
        properties.getRateLimit().setBurst(0);
        ResponseEntity<String> first = controller.post(post(initBody("2025-06-18")));
        assertEquals(200, first.getStatusCodeValue());
        ResponseEntity<String> second = controller.post(post(initBody("2025-06-18")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), second.getStatusCodeValue());
        assertNotNull(second.getHeaders().getFirst("Retry-After"));
    }

    @Test
    public void accept_header_is_enforced() throws Exception {
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        r.removeHeader(HttpHeaders.ACCEPT);
        r.addHeader(HttpHeaders.ACCEPT, "text/plain");
        assertEquals("协议要求客户端同时可收 json 与 event-stream",
                HttpStatus.NOT_ACCEPTABLE.value(), controller.post(r).getStatusCodeValue());

        MockHttpServletRequest wildcard = post(initBody("2025-06-18"));
        wildcard.removeHeader(HttpHeaders.ACCEPT);
        wildcard.addHeader(HttpHeaders.ACCEPT, "*/*");
        assertEquals(200, controller.post(wildcard).getStatusCodeValue());
    }

    @Test
    public void accept_ok_requires_both_types_because_the_server_picks_either() {
        // 不带 Accept 的是非 HTTP 客户端(探针/负载均衡), 放行
        assertTrue(JsonRpcController.acceptOk(null));
        assertTrue(JsonRpcController.acceptOk(""));
        assertTrue(JsonRpcController.acceptOk("*/*"));
        assertTrue(JsonRpcController.acceptOk("application/json;q=0.9, text/event-stream"));
        // 只声明其一: 服务端一旦选另一种形态, 该客户端就无法解析响应 ⇒ 2025-06-18 明文 MUST
        assertFalse(JsonRpcController.acceptOk("application/json"));
        assertFalse(JsonRpcController.acceptOk("text/event-stream"));
        assertFalse(JsonRpcController.acceptOk("text/html"));
    }

    /**
     * 2025-06-18 "Sending Messages" 第 3 条: 体 MUST 是 application/json, 否则 415.
     *
     * <p>判 415 而不是 400: 400 说的是"体不成形", 而这里的体完全成形, 是客户端没声称过
     * 自己送的是什么 —— 修的方向因此不同(前者改客户端的构造, 后者改它的头).
     */
    @Test
    public void post_rejects_non_json_content_type_with_415() throws Exception {
        for (String bad : new String[]{"text/plain", "application/x-www-form-urlencoded",
                "text/plain;charset=UTF-8", "application/octet-stream"}) {
            ResponseEntity<String> res = controller.post(
                    postWithContentType(bad, initBody("2025-06-18")));
            assertEquals("Content-Type: " + bad,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), res.getStatusCodeValue());
            JsonNode body = bodyOf(res);
            assertEquals(McpException.INVALID_REQUEST, body.path("error").path("code").asInt());
            assertTrue("415 要说清楚该带什么, 只回状态码的客户端会原样重试. 实际: " + res.getBody(),
                    body.path("error").path("message").asText().contains("application/json"));
        }
    }

    /** 不带 Content-Type 也拒: 缺省意味着发送方从没声称过送的是什么, 服务端没理由替他猜. */
    @Test
    public void post_without_content_type_is_415() {
        MockHttpServletRequest r = postWithContentType(null, initBody("2025-06-18"));
        assertNull(r.getContentType());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                controller.post(r).getStatusCodeValue());
    }

    /**
     * 带参数的 {@code application/json;charset=UTF-8} 必须照收 —— 这是这道闸唯一可能伤到真实
     * 客户端的地方(很多 HTTP 库会自动补 charset), 所以它是断言而不是"看一眼实现觉得没问题".
     */
    @Test
    public void json_content_type_with_parameters_is_still_accepted() {
        for (String ok : new String[]{"application/json;charset=UTF-8",
                "application/json; charset=utf-8", "APPLICATION/JSON", "application/json"}) {
            ResponseEntity<String> res = controller.post(
                    postWithContentType(ok, initBody("2025-06-18")));
            assertEquals(ok + " 不能被 415 挡在门外", 200, res.getStatusCodeValue());
            assertTrue(res.getBody().contains("\"protocolVersion\""));
        }
    }

    /**
     * 闸要长在"要不要理这条请求"之前, 而不是长在会话解析之后: 否则一条带畸形 Content-Type 的
     * 请求会先被 400(缺会话)/404(不认识会话)挡掉, 415 这条路只在握手之后才通,
     * 而 initialize 之前恰恰是最容易被中间件改写的这一段.
     */
    @Test
    public void content_type_gate_runs_before_the_session_gate() {
        // 这条请求既没有 Mcp-Session-Id 又带错 Content-Type: 读出 415 才说明 Content-Type 在前
        ResponseEntity<String> res = controller.post(postWithContentType(
                "text/plain", "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}"));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), res.getStatusCodeValue());
    }

    @Test
    public void content_type_ok_strips_parameters_and_is_case_insensitive() {
        assertTrue(JsonRpcController.contentTypeOk("application/json"));
        assertTrue(JsonRpcController.contentTypeOk("application/json;charset=UTF-8"));
        assertTrue(JsonRpcController.contentTypeOk("  Application/Json ;  q=1 "));
        // RFC 2045: 媒体类型不区分大小写. 参照实现按逐字比, 于是大写形式会被它自己拒掉
        assertFalse(JsonRpcController.contentTypeOk(null));
        assertFalse(JsonRpcController.contentTypeOk(""));
        assertFalse(JsonRpcController.contentTypeOk("application/jsonp"));
        assertFalse(JsonRpcController.contentTypeOk("text/plain"));
        assertFalse(JsonRpcController.contentTypeOk("application/xml"));
        // 逗号串多个类型本来就违反 HTTP(请求的 Content-Type 只能有一个媒体类型), 但只要其中
        // 点名了 application/json 就放行 —— 与参照实现同一口径(streamable_http.py 的
        // _check_content_type 同样 split(";")[0].split(",")), 这道闸要挡的是中间件把体改写成
        // form/text 那类事故, 不是给"写法 sloppy 但意图明确"的客户端再加一道拒绝理由
        assertTrue(JsonRpcController.contentTypeOk("text/plain, application/json"));
    }

    // ----------------------------------------------------------- SSE 只读流

    @Test
    public void get_stream_is_405_when_disabled_and_200_when_enabled() {
        String sid = initializeAndTakeSession();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/mcp");
        get.addHeader(McpSchema.H_SESSION, sid);

        properties.getSse().setEnabled(false);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), controller.stream(get).getStatusCodeValue());

        properties.getSse().setEnabled(true);
        ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> ok =
                controller.stream(get);
        assertEquals(HttpStatus.OK.value(), ok.getStatusCodeValue());
        assertEquals(MediaType.TEXT_EVENT_STREAM, ok.getHeaders().getContentType());
        assertEquals("反代默认攒流, 不关掉 buffering 就永远等不到一帧",
                "no", ok.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals(1, sessions.find(sid).emitterCount());
    }

    @Test
    public void get_stream_requires_a_live_session() {
        properties.getSse().setEnabled(true);
        MockHttpServletRequest noSession = new MockHttpServletRequest("GET", "/mcp");
        assertEquals(HttpStatus.BAD_REQUEST.value(), controller.stream(noSession).getStatusCodeValue());

        String sid = initializeAndTakeSession();
        MockHttpServletRequest dead = new MockHttpServletRequest("GET", "/mcp");
        dead.addHeader(McpSchema.H_SESSION, "gone");
        assertEquals(HttpStatus.NOT_FOUND.value(), controller.stream(dead).getStatusCodeValue());
    }

    /**
     * 续传的位置必须是本会话发过的十进制事件 id. 认不出的形态(非数字、负数)按 400 拒,
     * 因为"接着发"在这种请求下只能靠猜 —— 而猜错的流看着完全正常。
     */
    @Test
    public void a_resume_position_the_server_cannot_place_is_400() {
        properties.getSse().setEnabled(true);
        String sid = initializeAndTakeSession();

        assertEquals(HttpStatus.BAD_REQUEST.value(),
                controller.stream(resumeWith(sid, "not-a-number")).getStatusCodeValue());
        assertEquals(HttpStatus.BAD_REQUEST.value(),
                controller.stream(resumeWith(sid, "-7")).getStatusCodeValue());
        assertEquals("客户端报的号比服务端发过的大: 没什么可补, 但也用得着这条流",
                HttpStatus.OK.value(), controller.stream(resumeWith(sid, "9999")).getStatusCodeValue());
    }

    /** 缓冲已经淘汰了客户端要的位置: 宁可 400 让它退回不续传, 也不给一条注定少事件的流. */
    @Test
    public void a_resume_position_already_dropped_from_the_buffer_is_400() {
        properties.getSse().setEnabled(true);
        properties.getSse().setBufferSize(1);
        String sid = initializeAndTakeSession();
        McpSessionStore.McpSession session = sessions.find(sid);
        for (int i = 0; i < 3; i++) {
            session.emit("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }
        assertEquals("缓冲停在配置值上", 1, session.bufferedCount());

        // 发过 1、2、3, 只剩 3: 从 2 续是补得上的, 从 1 续就得凭空跳过 2
        assertEquals(HttpStatus.BAD_REQUEST.value(),
                controller.stream(resumeWith(sid, "1")).getStatusCodeValue());
        assertEquals(HttpStatus.OK.value(),
                controller.stream(resumeWith(sid, "2")).getStatusCodeValue());
        assertEquals("0 号从来不存在, 但它要的是'从头补', 而头已经被淘汰",
                HttpStatus.BAD_REQUEST.value(), controller.stream(resumeWith(sid, "0")).getStatusCodeValue());
    }

    private static MockHttpServletRequest resumeWith(String sessionId, String lastEventId) {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/mcp");
        get.addHeader(McpSchema.H_SESSION, sessionId);
        get.addHeader(McpSchema.H_LAST_EVENT_ID, lastEventId);
        return get;
    }

    // -------------------------------------------------- 端到端真实客户端时序

    @Test
    public void full_handshake_then_list_then_call() throws Exception {
        String sid = initializeAndTakeSession();
        assertEquals(HttpStatus.ACCEPTED.value(), withSession(sid,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").getStatusCodeValue());

        ResponseEntity<String> list = withSession(sid, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}");
        assertEquals(200, list.getStatusCodeValue());
        JsonNode tools = bodyOf(list).path("result").path("tools");
        assertTrue(tools.isArray());
        assertEquals(4, tools.size());

        ResponseEntity<String> call = withSession(sid, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"ping-through-http\"}}}");
        JsonNode result = bodyOf(call).path("result");
        assertTrue("content 必须是数组", result.path("content").isArray());
        assertEquals("ping-through-http", result.path("content").get(0).path("text").asText());
        assertFalse(result.has("isError"));
    }

    @Test
    public void unknown_tool_over_http_keeps_the_json_rpc_code() throws Exception {
        String sid = initializeAndTakeSession();
        withSession(sid, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        ResponseEntity<String> res = withSession(sid, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ghost\",\"arguments\":{}}}");
        // 关键回归点: 0.1.x 里 McpException 被 catch(RuntimeException) 一律压成 -32000
        assertEquals(-32602, bodyOf(res).path("error").path("code").asInt());
        assertNot(-32000, bodyOf(res).path("error").path("code").asInt());
    }

    private static void assertNot(int unexpected, int actual) {
        if (unexpected == actual) fail("值不应等于 " + unexpected);
    }

    @Test
    public void method_not_found_and_internal_sanity() throws Exception {
        String sid = initializeAndTakeSession();
        withSession(sid, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(-32601, bodyOf(withSession(sid,
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"does/not/exist\"}"))
                .path("error").path("code").asInt());
    }

    @Test
    public void base_path_prefix_is_resolved_from_property() {
        MockHttpServletRequest r = post(initBody("2025-06-18"));
        // 映射值由 ${z.mcp.base-path} 与 "/mcp" 拼成, 这里只断言 controller 不依赖固定全路径
        assertEquals(200, controller.post(r).getStatusCodeValue());
    }
}
