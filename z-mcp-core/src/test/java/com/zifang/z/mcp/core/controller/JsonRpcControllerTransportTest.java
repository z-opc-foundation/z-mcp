package com.zifang.z.mcp.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertNotNull(res.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        MockHttpServletRequest authed = post(initBody("2025-06-18"));
        authed.addHeader(HttpHeaders.AUTHORIZATION, "Bearer s3cret");
        assertEquals(200, controller.post(authed).getStatusCodeValue());

        MockHttpServletRequest wrong = post(initBody("2025-06-18"));
        wrong.addHeader(HttpHeaders.AUTHORIZATION, "Bearer nope");
        assertEquals(HttpStatus.UNAUTHORIZED.value(), controller.post(wrong).getStatusCodeValue());
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
