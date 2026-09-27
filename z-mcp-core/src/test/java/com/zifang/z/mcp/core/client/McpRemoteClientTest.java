package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.core.properties.McpProperties;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 上游客户端的协议行为 — 全离线, 真传输换成 {@link Script}.
 *
 * <p>这里钉住的都是真实 server 会区别对待的事: 握手必须补第二个通知、会话头必须回带、
 * 404 要重新握手而 401 不能、SSE 帧里取的必须是带 result 的那一帧、翻页不能翻不完。
 */
public class McpRemoteClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 按脚本应答的传输; 脚本耗尽就报错, 免得测试默默多发了请求还以为通过. */
    private class Script implements JsonRpcExchange, McpRemoteClient.DeleteCapable {
        final List<String> bodies = new ArrayList<String>();
        final List<Map<String, String>> headers = new ArrayList<Map<String, String>>();
        final List<Map<String, String>> deletes = new ArrayList<Map<String, String>>();
        private final Deque<JsonRpcExchange.Response> script =
                new ArrayDeque<JsonRpcExchange.Response>();
        private boolean ignoreScript;

        Script answer(String jsonBody) {
            return answer(200, "application/json", jsonBody, null);
        }

        Script answer(int status, String contentType, String body, Map<String, String> hdrs) {
            script.add(new JsonRpcExchange.Response(status, contentType, body, hdrs));
            return this;
        }

        /** 握手两帧: initialize result + 202 notification. */
        Script handshake(String sessionId) {
            Map<String, String> hdrs = new LinkedHashMap<String, String>();
            if (sessionId != null) hdrs.put("Mcp-Session-Id", sessionId);
            script.add(new JsonRpcExchange.Response(200, "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                            + "\"protocolVersion\":\"2025-06-18\","
                            + "\"serverInfo\":{\"name\":\"upstream\",\"version\":\"1\"},"
                            + "\"capabilities\":{\"tools\":{\"listChanged\":true}}}}", hdrs));
            script.add(new JsonRpcExchange.Response(202, null, "", hdrs));
            return this;
        }

        /** 之后所有请求都回同一帧 — 用来演"nextCursor 自指". */
        Script always(String jsonBody) {
            ignoreScript = true;
            script.add(new JsonRpcExchange.Response(200, "application/json", jsonBody, null));
            return this;
        }

        Script toolsResult(String toolsJson, String nextCursor) {
            StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":")
                    .append(toolsJson);
            sb.append(",\"nextCursor\":")
                    .append(nextCursor == null ? "\"\"" : "\"" + nextCursor + "\"")
                    .append("}}");
            return answer(sb.toString());
        }

        @Override public JsonRpcExchange.Response post(String body, Map<String, String> header) {
            bodies.add(body);
            headers.add(header == null ? new LinkedHashMap<String, String>() : header);
            JsonRpcExchange.Response res = ignoreScript ? script.peek() : script.poll();
            if (res == null) throw new AssertionError("unexpected request #" + bodies.size() + ": " + body);
            return res;
        }

        @Override public void delete(Map<String, String> header) {
            deletes.add(header == null ? new LinkedHashMap<String, String>() : header);
        }

        @Override public void close() { }

        JsonNode bodyOf(int index) throws IOException { return mapper.readTree(bodies.get(index)); }
    }

    private McpProperties.ServerConfig config(String... headerPairs) {
        McpProperties.ServerConfig c = new McpProperties.ServerConfig();
        c.setName("upstream");
        c.setEndpoint("http://127.0.0.1:1/mcp");
        Map<String, String> h = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < headerPairs.length; i += 2) h.put(headerPairs[i], headerPairs[i + 1]);
        c.setHeaders(h);
        return c;
    }

    private McpRemoteClient client(Script script, McpProperties.ServerConfig config) {
        return new McpRemoteClient("upstream", config, script, mapper, "z-mcp", "0.2.0");
    }

    // ------------------------------------------------------------------ 握手

    @Test
    public void initialize_records_negotiated_version_and_session() throws Exception {
        Script script = new Script().handshake("sess-9");
        McpRemoteClient c = client(script, config());

        c.connect();

        assertTrue(c.isConnected());
        assertEquals("2025-06-18", c.protocolVersion());
        assertEquals("sess-9", c.sessionId());
        assertNotNull(c.serverInfo());
        assertEquals("upstream", c.serverInfo().get("name").asText());
        assertTrue(c.capabilities().has("tools"));

        JsonNode init = script.bodyOf(0);
        assertEquals("initialize", init.get("method").asText());
        assertEquals("2025-11-25", init.get("params").get("protocolVersion").asText());
        assertEquals("z-mcp", init.get("params").get("clientInfo").get("name").asText());
        assertEquals("0.2.0", init.get("params").get("clientInfo").get("version").asText());
        assertTrue("capabilities 必填且必须是对象", init.get("params").get("capabilities").isObject());
    }

    /**
     * 广告出去的每一件事都是一份承诺: 上游看见 {@code roots} 就会问 {@code roots/list},
     * 看见 {@code sampling} 就会派一次采样 —— 而这一侧一件都没实现, 问了只能回 -32601
     * (#45 才补上那条兜底). 两份官方参照都是"注册了回调才说".
     */
    @Test
    public void the_handshake_advertises_only_what_the_client_can_actually_answer() throws Exception {
        Script script = new Script().handshake("sess-caps");
        client(script, config()).connect();

        JsonNode params = script.bodyOf(0).get("params");
        JsonNode caps = params.get("capabilities");
        assertTrue("capabilities 必填(可以是空对象, 但不能缺): " + script.bodies.get(0),
                caps != null && caps.isObject());
        for (String unimplemented : new String[] {"roots", "sampling", "elicitation"}) {
            assertFalse("广告了 " + unimplemented + " 就得答得出它: " + script.bodies.get(0),
                    caps.has(unimplemented));
        }
        // 阳性对照: clientInfo 是同一处、同一种嵌套 Map 塞进 params 的, 它的内层键读得见
        // 才说明上面那三条"看不见 roots"是真没有, 而不是这套判定本身是瞎的.
        assertTrue("对照: params 里的嵌套对象应当看得见内层键",
                params.get("clientInfo").isObject() && params.get("clientInfo").has("name"));
    }

    @Test
    public void connect_always_follows_initialize_with_the_initialized_notification() throws Exception {
        Script script = new Script().handshake("s1");
        client(script, config()).connect();

        assertEquals(2, script.bodies.size());
        JsonNode note = script.bodyOf(1);
        assertEquals("notifications/initialized", note.get("method").asText());
        assertFalse("通知不得带 id", note.has("id"));
        assertEquals("s1", script.headers.get(1).get("Mcp-Session-Id"));
    }

    @Test
    public void a_rejected_notification_ends_the_connection() throws Exception {
        Script script = new Script()
                .answer(200, "application/json",
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\"}}", null)
                .answer(500, "application/json", "", null);
        McpRemoteClient c = client(script, config());
        try {
            c.connect();
            fail("notifications/initialized 失败不能当成功");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("notifications/initialized"));
            assertFalse(c.isConnected());
        }
    }

    // ------------------------------------------------------------------ 请求头

    @Test
    public void every_request_carries_session_protocol_version_and_auth_headers() throws Exception {
        Script script = new Script().handshake("sess-42")
                .toolsResult("[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]", null);
        McpRemoteClient c = client(script, config("Authorization", "Bearer tok"));
        c.connect();
        c.listTools();

        Map<String, String> sent = script.headers.get(2);
        assertEquals("Bearer tok", sent.get("Authorization"));
        assertEquals("sess-42", sent.get("Mcp-Session-Id"));
        assertEquals("2025-06-18", sent.get("MCP-Protocol-Version"));
    }

    @Test
    public void stateless_upstream_without_session_header_still_works() throws Exception {
        Script script = new Script().handshake(null).toolsResult("[{\"name\":\"echo\"}]", null);
        McpRemoteClient c = client(script, config());
        c.connect();
        assertNull(c.sessionId());
        assertEquals(1, c.listTools().size());
    }

    // ------------------------------------------------------------------ 会话失效 / 拒绝

    @Test
    public void http_404_reinitializes_once_and_replays_the_request() throws Exception {
        Script script = new Script().handshake("old")
                .answer(404, "text/plain", "session gone", null)
                .handshake("new")
                .toolsResult("[{\"name\":\"echo\"}]", null);
        McpRemoteClient c = client(script, config());
        c.connect();

        List<McpToolDto> tools = c.listTools();

        assertEquals(1, tools.size());
        assertEquals("new", c.sessionId());
        assertEquals(6, script.bodies.size());
        assertEquals("initialize", script.bodyOf(3).get("method").asText());
        assertEquals("notifications/initialized", script.bodyOf(4).get("method").asText());
        assertEquals("tools/list", script.bodyOf(5).get("method").asText());
    }

    @Test
    public void http_401_is_a_rejection_not_an_expired_session() throws Exception {
        Script script = new Script().handshake("s").answer(401, "text/plain", "", null);
        McpRemoteClient c = client(script, config());
        c.connect();
        try {
            c.listTools();
            fail("鉴权失败必须抛出");
        } catch (McpRemoteClient.RemoteRejectException e) {
            assertEquals(401, e.httpStatus());
            assertTrue(e.getMessage(), e.getMessage().contains("no credential is configured"));
        }
        // 关键: 401 后再握手只会再拿一个 401, 所以绝不能重握手
        assertEquals(3, script.bodies.size());
    }

    /**
     * 同一个 401 有两种成因, 要改的地方在两台不同的机器上: 我们这边根本没把凭证发出去
     * (改 hub 的 {@code servers[].headers}), 和发了但对方白名单里没有它(改叶子的
     * {@code bearer-tokens}). 消息里只写状态码的话, 运维只能两边来回猜.
     */
    @Test
    public void a_401_names_the_credential_we_actually_sent() throws Exception {
        // 部署最常见的踩法: 配置写的是 "Bearer ${VAR}" 而 VAR 没给值 ⇒ 头里只剩方案名.
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        hdrs.put("WWW-Authenticate", "Bearer realm=\"z-mcp\", error=\"invalid_request\"");
        String empty = messageOf(client(new Script().handshake("s")
                .answer(401, "application/json", "", hdrs), config("Authorization", "Bearer ")));
        assertTrue(empty, empty.contains("has a \"Bearer \" prefix but no token value"));
        assertTrue(empty, empty.contains("error=\"invalid_request\""));

        // 对照: 带了一个像样的令牌被拒 ⇒ 成因换成"对方不认", 且消息里不许出现令牌本身.
        String rejected = messageOf(client(new Script().handshake("s")
                .answer(401, "application/json", "", hdrs),
                config("Authorization", "Bearer s3cr3t-token-value")));
        assertTrue(rejected, rejected.contains("does not accept"));
        assertFalse("凭证值不进异常消息: " + rejected, rejected.contains("s3cr3t-token-value"));
    }

    @Test
    public void a_non_bearer_authorization_header_is_reported_as_the_wrong_shape_it_is()
            throws Exception {
        // 有人会把网关要的 "ApiKey xxx" 填进来: 这时该说的是"写法不对", 而不是"令牌被拒".
        String msg = messageOf(client(new Script().handshake("s")
                        .answer(403, "text/plain", "", null),
                config("Authorization", "ApiKey s3cr3t-token-value")));
        assertTrue(msg, msg.contains("not of the form \"Bearer <token>\""));
        assertTrue(msg, msg.contains("HTTP 403"));
    }

    private static String messageOf(McpRemoteClient c) {
        try {
            c.connect();
            c.listTools();
        } catch (McpRemoteClient.RemoteRejectException e) {
            return e.getMessage();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("期望被拒却没有被拒");
    }

    @Test
    public void other_non_2xx_fails_loudly_with_the_method_that_broke() throws Exception {
        Script script = new Script().handshake("s").answer(503, "text/plain", "no capacity", null);
        McpRemoteClient c = client(script, config());
        c.connect();
        try {
            c.listTools();
            fail();
        } catch (McpRemoteClient.RemoteRejectException e) {
            assertEquals(503, e.httpStatus());
            assertTrue(e.getMessage(), e.getMessage().contains("tools/list"));
        }
    }

    @Test
    public void json_rpc_error_keeps_the_upstream_code_for_capability_probing() throws Exception {
        Script script = new Script().handshake("s")
                .answer("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
        McpRemoteClient c = client(script, config());
        c.connect();
        try {
            c.ping();
            fail();
        } catch (McpRemoteClient.RemoteRejectException e) {
            assertEquals(Integer.valueOf(-32601), e.jsonRpcCode());
            assertTrue(e.isMethodNotFound());
        }
    }

    @Test
    public void upstream_invalid_params_is_reported_as_minus_32602() throws Exception {
        Script script = new Script().handshake("s")
                .answer("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}");
        McpRemoteClient c = client(script, config());
        c.connect();
        try {
            c.callTool("echo", args("text", "hi"));
            fail();
        } catch (McpRemoteClient.RemoteRejectException e) {
            assertEquals(Integer.valueOf(-32602), e.jsonRpcCode());
            assertFalse(e.isMethodNotFound());
        }
    }

    @Test
    public void a_2xx_with_no_envelope_is_not_silently_treated_as_empty() throws Exception {
        Script script = new Script().handshake("s").answer(202, "application/json", "", null);
        McpRemoteClient c = client(script, config());
        c.connect();
        try {
            c.listTools();
            fail("对请求回 202 空体是协议错误, 不能当成\"这个 server 没有工具\"");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no JSON-RPC envelope"));
        }
    }

    // ------------------------------------------------------------------ SSE

    @Test
    public void sse_framed_response_is_matched_to_the_request() throws Exception {
        Script script = new Script().handshake("s").answer(200, "text/event-stream",
                ": ping\n"
                        + "\n"
                        + "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                        + "\"params\":{\"level\":\"info\",\"data\":\"booting\"}}\n"
                        + "\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"tools\":["
                        + "{\"name\":\"big\",\"description\":\"from sse\"}],\"nextCursor\":\"\"}}\n"
                        + "\n", null);
        McpRemoteClient c = client(script, config());
        c.connect();
        List<McpToolDto> tools = c.listTools();

        assertEquals(1, tools.size());
        assertEquals("big", tools.get(0).getName());
        assertEquals("from sse", tools.get(0).getDescription());
    }

    @Test
    public void sse_data_fields_are_joined_and_comment_frames_ignored() throws Exception {
        List<JsonNode> frames = McpRemoteClient.ssePayloads(
                ": keepalive\n"
                        + "id: 1\n"
                        + "event: message\n"
                        + "data: {\"a\":\n"
                        + "data: 1}\n"
                        + "\n"
                        + "data: not json at all\n"
                        + "\n"
                        + "data: {\"b\":2}\n", mapper);

        assertEquals(2, frames.size());
        assertEquals(1, frames.get(0).get("a").asInt());
        assertEquals(2, frames.get(1).get("b").asInt());
    }

    // ------------------------------------------------------------------ 翻页

    @Test
    public void tools_list_follows_next_cursor_until_it_stops() throws Exception {
        Script script = new Script().handshake("s")
                .toolsResult("[{\"name\":\"t1\"}]", "c2")
                .toolsResult("[{\"name\":\"t2\"},{\"name\":\"t3\"}]", null);
        McpRemoteClient c = client(script, config());
        c.connect();

        List<McpToolDto> tools = c.listTools();

        assertEquals(3, tools.size());
        assertEquals("t1", tools.get(0).getName());
        assertEquals("t3", tools.get(2).getName());
        assertEquals("c2", script.bodyOf(3).get("params").get("cursor").asText());
        assertNull("首页不该带 cursor", script.bodyOf(2).get("params"));
    }

    @Test
    public void a_cursor_that_never_ends_is_refused_instead_of_hanging_startup() throws Exception {
        Script script = new Script()
                .always("{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"t\"}],"
                        + "\"nextCursor\":\"loop\"}}");
        McpRemoteClient c = client(script, config());
        c.connect();
        try {
            c.listTools();
            fail("自指 nextCursor 必须被硬上限挡住");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("pages of tools/list"));
        }
        assertEquals(McpRemoteClient.MAX_PAGES, script.bodies.size() - 2);
    }

    // ------------------------------------------------------------------ tools/call 映射

    @Test
    public void upstream_result_keeps_content_blocks_isError_and_structured_content() throws Exception {
        Script script = new Script().handshake("s")
                .answer("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"line one\"},"
                        + "{\"type\":\"resource_link\",\"uri\":\"file:///a.txt\",\"name\":\"a\"}"
                        + "],\"isError\":true,\"structuredContent\":{\"count\":2,\"tags\":[\"x\",\"y\"]},"
                        + "\"_meta\":{\"progressToken\":5}}}");
        McpRemoteClient c = client(script, config());
        c.connect();

        CallToolResult res = c.callTool("pick", args("n", 2));

        assertTrue(res.isError());
        assertEquals(2, res.getContent().size());
        // 认不出的块类型必须原样转发: 代理不能把上游能力裁掉
        Map<String, Object> link = res.getContent().get(1).toWire();
        assertEquals("resource_link", link.get("type"));
        assertEquals("file:///a.txt", link.get("uri"));
        assertEquals("a", link.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) res.getStructuredContent();
        assertEquals(2, ((Number) structured.get("count")).intValue());
        assertEquals("x", ((List<?>) structured.get("tags")).get(0));
        JsonNode wire = mapper.valueToTree(res.toWire());
        assertEquals(5, wire.get("_meta").get("progressToken").asInt());
        assertTrue(wire.get("isError").asBoolean());
        assertEquals("line one", wire.get("content").get(0).get("text").asText());
    }

    @Test
    public void arguments_are_forwarded_verbatim_under_the_spec_keys() throws Exception {
        Script script = new Script().handshake("s")
                .answer("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[]}}");
        McpRemoteClient c = client(script, config());
        c.connect();
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("deep", Boolean.TRUE);
        c.callTool("run", args("path", "/tmp", "opts", nested));

        JsonNode call = script.bodyOf(2);
        assertEquals("tools/call", call.get("method").asText());
        assertEquals("run", call.get("params").get("name").asText());
        assertEquals("/tmp", call.get("params").get("arguments").get("path").asText());
        assertTrue(call.get("params").get("arguments").get("opts").get("deep").asBoolean());
    }

    @Test
    public void null_arguments_still_serialize_as_an_object() throws Exception {
        Script script = new Script().handshake("s")
                .answer("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[]}}");
        McpRemoteClient c = client(script, config());
        c.connect();
        c.callTool("noop", null);

        assertTrue(script.bodyOf(2).get("params").get("arguments").isObject());
    }

    @Test
    public void tool_schema_annotations_survive_the_trip_through_the_dto() throws Exception {
        Script script = new Script().handshake("s")
                .toolsResult("[{\"name\":\"del\",\"title\":\"Delete\","
                        + "\"inputSchema\":{\"type\":\"object\",\"required\":[\"id\"]},"
                        + "\"outputSchema\":{\"type\":\"object\"},"
                        + "\"annotations\":{\"readOnlyHint\":false,\"destructiveHint\":true,"
                        + "\"openWorldHint\":false}}]", null);
        McpRemoteClient c = client(script, config());
        c.connect();
        McpToolDto dto = c.listTools().get(0);

        assertEquals("Delete", dto.getTitle());
        assertTrue(dto.getInputSchemaJson().contains("\"required\""));
        assertNotNull(dto.getOutputSchemaJson());
        assertEquals(Boolean.TRUE, dto.getAnnotations().getDestructiveHint());
        assertEquals(Boolean.FALSE, dto.getAnnotations().getOpenWorldHint());
        assertNull(dto.getAnnotations().getIdempotentHint());
    }

    // ------------------------------------------------------------------ 收尾

    @Test
    public void disconnect_terminates_the_upstream_session_when_transport_can_delete() throws Exception {
        Script script = new Script().handshake("s-del");
        McpRemoteClient c = client(script, config());
        c.connect();
        c.disconnect();

        assertFalse(c.isConnected());
        assertNull(c.sessionId());
        assertEquals(1, script.deletes.size());
        assertEquals("s-del", script.deletes.get(0).get("Mcp-Session-Id"));
    }

    @Test
    public void a_stateless_client_disconnects_without_trying_to_delete() throws Exception {
        Script script = new Script().handshake(null);
        McpRemoteClient c = client(script, config());
        c.connect();
        c.disconnect();

        assertTrue(script.deletes.isEmpty());
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
