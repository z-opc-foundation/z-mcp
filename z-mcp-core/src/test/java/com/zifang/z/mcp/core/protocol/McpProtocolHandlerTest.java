package com.zifang.z.mcp.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.ContentBlock;
import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.api.exception.McpException;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.junit.Before;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MCP 协议合规测试 — 按真实客户端(Claude / 官方 SDK)的请求时序驱动, 不打 HTTP 层.
 *
 * <p>这些用例存在的理由: 0.1.x 的 JsonRpcController 一个测试都没有, 于是
 * "支持 MCP" 这个说法从来没有被证伪过 —— 直到任何真客户端发来 initialize.
 */
public class McpProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpProperties properties;
    private McpRegistry registry;
    private McpProtocolHandler handler;
    private McpSessionStore sessions;

    @Before
    public void setUp() {
        properties = new McpProperties();
        properties.setServerVersion("9.9.9-test");
        registry = new McpRegistry();
        com.zifang.z.mcp.core.builtin.BuiltinTools.registerAll(registry, properties, mapper);
        registerFixtureTools();
        sessions = new McpSessionStore(properties);
        handler = new McpProtocolHandler(registry, properties, mapper);
    }

    private void registerFixtureTools() {
        registry.registerBuiltin("boom", "always fails",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> { throw new IllegalStateException("kaboom"); });
        registry.registerBuiltin("slow", "sleeps forever",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> { Thread.sleep(30_000L); return "late"; });
        registry.tool("numbers")
                .description("returns a map")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .outputSchema("{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"count\"]}")
                .annotations(ToolAnnotations.readOnly("数字"))
                .register(args -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
                    m.put("count", Integer.valueOf(7));
                    return m;
                });
    }

    // ------------------------------------------------------------- helpers

    private McpProtocolHandler.RequestContext liveSession() {
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        ctx.session = sessions.create(sessions.newSessionId(), McpSchema.LATEST_SUPPORTED);
        ctx.session.markInitialized();
        ctx.protocolVersion = McpSchema.LATEST_SUPPORTED;
        return ctx;
    }

    private JsonNode call(String method, String paramsJson, String idJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        return mapper.readTree(body);
    }

    private JsonNode notify(String method, String paramsJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        return mapper.readTree(body);
    }

    private Map<String, Object> wire(McpProtocolHandler.Reply reply) {
        assertNotNull("expected a response envelope", reply.envelope);
        return reply.envelope.toWire();
    }

    /**
     * 线格式内部 Map 与 JsonNode 并存, 但客户端看到的是 JSON.
     * 断言一律先过一遍序列化, 免得把"实现的表示形式"误当成"协议的表示形式".
     */
    private JsonNode wireNode(Object value) {
        return mapper.valueToTree(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wireMap(Object value) {
        return value == null ? null : mapper.convertValue(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultOf(McpProtocolHandler.Reply reply) {
        Map<String, Object> w = wire(reply);
        assertNull("unexpected JSON-RPC error: " + w.get("error"), w.get("error"));
        return (Map<String, Object>) w.get("result");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorOf(McpProtocolHandler.Reply reply) {
        Map<String, Object> w = wire(reply);
        assertNull("unexpected result: " + w.get("result"), w.get("result"));
        return (Map<String, Object>) w.get("error");
    }

    // ------------------------------------------------------- 生命周期 §initialize

    @Test
    public void initialize_returns_negotiated_version_and_capabilities() throws Exception {
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        McpProtocolHandler.Reply reply = handler.handle(call("initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"roots\":{}},"
                        + "\"clientInfo\":{\"name\":\"claude-desktop\",\"version\":\"1.2\"}}", "1"), ctx);

        Map<String, Object> r = resultOf(reply);
        assertEquals("2025-06-18", r.get("protocolVersion"));
        assertEquals("2025-06-18", ctx.negotiatedVersion);
        Map<?, ?> caps = (Map<?, ?>) r.get("capabilities");
        assertTrue("must advertise tools capability", caps.containsKey("tools"));
        Map<?, ?> info = (Map<?, ?>) r.get("serverInfo");
        assertEquals("z-mcp", info.get("name"));
        assertEquals("9.9.9-test", info.get("version"));
        assertFalse("serverInfo must not omit version", info.containsKey("title") && info.get("title") == null);
    }

    /**
     * capabilities 里每一个 listChanged 都是一张承诺："这一类清单变了我会喊你"。
     * 上面那条 initialize 用例只钉了 tools 键在不在, 于是"广告 true 而没人播报"这种谎
     * 曾在这里静悄悄成立过。兑现侧由 starter 的 ZMcpListChangedNotificationTest 钉,
     * 这里钉的是**广告侧**：想把这些 true 加回来, 必须先有对应的通知接线。
     */
    @Test
    public void advertised_list_changed_flags_are_the_ones_the_server_can_actually_honour() {
        Map<String, Object> caps = handler.capabilities();
        Map<?, ?> tools = (Map<?, ?>) caps.get("tools");
        Map<?, ?> resources = (Map<?, ?>) caps.get("resources");
        Map<?, ?> prompts = (Map<?, ?>) caps.get("prompts");
        assertEquals(Boolean.TRUE, tools.get("listChanged"));
        assertEquals(Boolean.TRUE, resources.get("listChanged"));
        assertEquals(Boolean.TRUE, prompts.get("listChanged"));
        assertEquals("没实现订阅就不许广告", Boolean.FALSE, resources.get("subscribe"));
    }

    @Test
    public void initialize_downgrades_unsupported_version_instead_of_failing() throws Exception {
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        Map<String, Object> r = resultOf(handler.handle(call("initialize",
                "{\"protocolVersion\":\"1999-01-01\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"old\",\"version\":\"0\"}}", "2"), ctx));
        assertEquals(McpSchema.LATEST_SUPPORTED, r.get("protocolVersion"));
    }

    /**
     * 兜底值本身必须在支持表里。传输层原先在握手成功后还检查一遍"协商出来的版本支持吗",
     * 而按 {@link McpSchema#negotiate} 的定义那一支永远为真 —— 于是那条 400 是发不出去的死码,
     * 谁都碰不到、也没有测试能碰到。把这条不变量挪到能被红的地方钉住: 有人往表里加版本、
     * 或者把 {@code LATEST_SUPPORTED} 改成表外的值时, 这里红, 而不是线上冒出一个不可能的分支。
     */
    @Test
    public void the_downgrade_target_is_itself_supported() {
        assertTrue("兜底值必须在自己支持的范围里",
                McpSchema.SUPPORTED_VERSIONS.contains(McpSchema.LATEST_SUPPORTED));
        assertTrue(McpSchema.isSupported(McpSchema.negotiate("1999-01-01")));
        assertTrue(McpSchema.isSupported(McpSchema.negotiate(null)));
        assertTrue(McpSchema.isSupported(McpSchema.negotiate("2025-11-25")));
        assertFalse("受支持与否只认表, 不能按字符串比较糊过去",
                McpSchema.isSupported("2026-07-28"));
    }

    @Test
    public void initialize_without_clientInfo_is_invalid_params() throws Exception {
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        Map<String, Object> err = errorOf(handler.handle(call("initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}", "3"), ctx));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    @Test
    public void requests_are_gated_until_initialized() throws Exception {
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        ctx.session = sessions.create(sessions.newSessionId(), McpSchema.LATEST_SUPPORTED);
        Map<String, Object> err = errorOf(handler.handle(call("tools/list", null, "4"), ctx));
        assertEquals(Integer.valueOf(McpException.INVALID_REQUEST), err.get("code"));

        // ping 是协议明确允许在 initialized 之前发的例外
        assertTrue(resultOf(handler.handle(call("ping", null, "5"), ctx)).isEmpty());
    }

    // ------------------------------------------------------------- ping §utilities

    @Test
    public void ping_returns_empty_object_not_pong() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("ping", null, "6"), liveSession()));
        assertTrue("协议规定 ping 结果是 {}", r.isEmpty());
        assertFalse(r.containsKey("pong"));
    }

    @Test
    public void unknown_method_is_method_not_found() throws Exception {
        Map<String, Object> err = errorOf(handler.handle(call("tools/launch", null, "7"), liveSession()));
        assertEquals(Integer.valueOf(McpException.METHOD_NOT_FOUND), err.get("code"));
    }

    // ------------------------------------------------------------ tools/list

    @Test
    @SuppressWarnings("unchecked")
    public void tools_list_has_spec_shape() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("tools/list", null, "8"), liveSession()));
        java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) r.get("tools");
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
        for (Map<String, Object> t : tools) {
            assertNotNull(t.get("name"));
            JsonNode schema = wireNode(t.get("inputSchema"));
            assertTrue("inputSchema 必须是 JSON 对象而不是字符串/null: " + schema, schema.isObject());
            assertEquals("object", schema.get("type").asText());
            assertFalse("自造的顶层 server 字段会击穿严格客户端", t.containsKey("server"));
            assertNull("未知键不得出现在 result 里", t.get("nextCursor"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void builtin_tools_omit_meta_but_server_tools_carry_it() throws Exception {
        registry.registerServerTool("github", "create_issue", "d",
                "{\"type\":\"object\",\"properties\":{}}", args -> "ok");
        Map<String, Object> r = resultOf(handler.handle(call("tools/list", null, "9"), liveSession()));
        java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) r.get("tools");
        Map<String, Object> gh = null;
        Map<String, Object> echo = null;
        for (Map<String, Object> t : tools) {
            if ("create_issue".equals(t.get("name"))) gh = t;
            if ("echo".equals(t.get("name"))) echo = t;
        }
        assertNotNull(gh);
        assertEquals("github", ((Map<String, Object>) gh.get("_meta")).get("z-mcp/server"));
        //  内置工具不再有"整个 _meta 缺席"这个形状: 来源链必须每跳都盖章, 否则对岸抄回来的
        //  内置工具会没有链可判(见 ExternalServerManagerTest 的断环那条). 但它仍不该被安上出处.
        Map<String, Object> echoMeta = (Map<String, Object>) echo.get("_meta");
        assertNotNull(echoMeta);
        assertNull("内置工具不许被记成某个外部 server 提供的", echoMeta.get("z-mcp/server"));
        assertEquals("内置工具广告时要带上自己这一跳", "z-mcp", echoMeta.get("z-mcp/origins"));
    }

    /**
     * 来源链的广告规则: 注册表里存的是"上游给我的那条链", 广告时把本机追加在末尾;
     * 链上已经有本机就不许再追加一次(否则每一跳都会把自己抄一遍).
     */
    @Test
    @SuppressWarnings("unchecked")
    public void the_provenance_chain_grows_by_exactly_one_hop_per_server() throws Exception {
        registry.tool("from_b").description("d").server("b")
                .origins(java.util.Arrays.asList("hub-b"))
                .register(args -> "ok");
        registry.tool("already_mine").description("d").server("b")
                .origins(java.util.Arrays.asList("hub-b", "z-mcp"))
                .register(args -> "ok");
        Map<String, Object> r = resultOf(handler.handle(call("tools/list", null, "31"), liveSession()));
        java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) r.get("tools");
        Map<String, String> chains = new java.util.LinkedHashMap<String, String>();
        for (Map<String, Object> t : tools) {
            Map<String, Object> meta = (Map<String, Object>) t.get("_meta");
            if (meta != null) chains.put((String) t.get("name"), (String) meta.get("z-mcp/origins"));
        }
        assertEquals("对岸的链后面必须接上本机", "hub-b,z-mcp", chains.get("from_b"));
        assertEquals("链上已有本机时不许重复追加", "hub-b,z-mcp", chains.get("already_mine"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tools_are_ordered_and_paginable() throws Exception {
        // 全量: 4 内置 + 3 fixture = 7, 且必须字典序(ConcurrentHashMap 的随机序会破坏分页)
        java.util.List<Map<String, Object>> full =
                (java.util.List<Map<String, Object>>) resultOf(
                        handler.handle(call("tools/list", null, "12"), liveSession())).get("tools");
        assertEquals(7, full.size());
        for (int i = 1; i < full.size(); i++) {
            assertTrue(((String) full.get(i - 1).get("name")).compareTo((String) full.get(i).get("name")) < 0);
        }

        properties.setPageSize(2);
        java.util.List<String> seen = new java.util.ArrayList<String>();
        String cursor = null;
        int pages = 0;
        Map<String, Object> page;
        do {
            page = resultOf(handler.handle(call("tools/list",
                    cursor == null ? null : "{\"cursor\":\"" + cursor + "\"}", "1" + (++pages)),
                    liveSession()));
            java.util.List<Map<String, Object>> items =
                    (java.util.List<Map<String, Object>>) page.get("tools");
            assertTrue("每页至多 pageSize 项, 末页可少", !items.isEmpty() && items.size() <= 2);
            for (Map<String, Object> t : items) {
                assertFalse("分页不得重复项: " + t.get("name"),
                        seen.contains((String) t.get("name")));
                seen.add((String) t.get("name"));
            }
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);
        assertEquals("遍历完必须覆盖全部工具", 7, seen.size());
    }

    @Test
    public void bad_cursor_is_invalid_params() throws Exception {
        properties.setPageSize(1);
        Map<String, Object> err = errorOf(handler.handle(call("tools/list",
                "{\"cursor\":\"no-such-tool\"}", "14"), liveSession()));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    // ------------------------------------------------------------ tools/call

    @Test
    @SuppressWarnings("unchecked")
    public void tools_call_returns_typed_content_array() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}", "15"), liveSession()));
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) r.get("content");
        assertNotNull("content 必须是数组 —— 0.1.x 直接塞了个裸对象", content);
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type"));
        assertEquals("hello", content.get(0).get("text"));
        assertFalse("成功时不得带 isError", r.containsKey("isError"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void schema_violation_is_execution_error_not_protocol_error() throws Exception {
        // 2025-11-25 SEP-1303: 入参校验失败属于工具执行结果的一部分, 要能回灌给 LLM
        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"echo\",\"arguments\":{}}", "16"), liveSession()));
        assertEquals(Boolean.TRUE, r.get("isError"));
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) r.get("content");
        assertTrue(((String) content.get(0).get("text")).contains("missing required property"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void wrong_argument_type_is_execution_error() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"echo\",\"arguments\":{\"text\":42}}", "17"), liveSession()));
        assertEquals(Boolean.TRUE, r.get("isError"));
        assertTrue(((String) ((java.util.List<Map<String, Object>>) r.get("content")).get(0).get("text"))
                .contains("expected type string"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void throwing_tool_is_execution_error() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"boom\",\"arguments\":{}}", "18"), liveSession()));
        assertEquals(Boolean.TRUE, r.get("isError"));
        assertTrue(((String) ((java.util.List<Map<String, Object>>) r.get("content")).get(0).get("text"))
                .contains("kaboom"));
    }

    @Test
    public void unknown_tool_is_protocol_error_minus32602() throws Exception {
        Map<String, Object> err = errorOf(handler.handle(call("tools/call",
                "{\"name\":\"not_there\",\"arguments\":{}}", "19"), liveSession()));
        assertEquals("协议示例把未知工具归为 -32602, 而不是自造的 -32001",
                Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    @Test
    public void missing_name_is_invalid_params() throws Exception {
        Map<String, Object> err = errorOf(handler.handle(call("tools/call",
                "{\"arguments\":{}}", "20"), liveSession()));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    @Test
    public void arguments_must_be_an_object() throws Exception {
        Map<String, Object> err = errorOf(handler.handle(call("tools/call",
                "{\"name\":\"echo\",\"arguments\":\"hi\"}", "21"), liveSession()));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tool_timeout_surfaces_as_execution_error() throws Exception {
        properties.setToolTimeoutSeconds(1);
        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"slow\",\"arguments\":{}}", "22"), liveSession()));
        assertEquals(Boolean.TRUE, r.get("isError"));
        assertTrue(((String) ((java.util.List<Map<String, Object>>) r.get("content")).get(0).get("text"))
                .contains("timed out"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void declared_output_schema_produces_structured_content() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"numbers\",\"arguments\":{}}", "23"), liveSession()));
        Map<String, Object> structured = wireMap(r.get("structuredContent"));
        assertNotNull(structured);
        assertEquals(7, ((Number) structured.get("count")).intValue());
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) r.get("content");
        assertFalse("必须同时给一份 JSON 文本块兼容老客户端", content.isEmpty());
        assertEquals("text", content.get(0).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void custom_content_blocks_pass_through() throws Exception {
        registry.tool("pic")
                .description("returns a block")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .annotations(ToolAnnotations.readOnly("图"))
                .register(args -> ContentBlock.image(new byte[]{1, 2, 3}, "image/png"));
        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"pic\",\"arguments\":{}}", "24"), liveSession()));
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) r.get("content");
        assertEquals("image", content.get(0).get("type"));
        assertEquals("AQID", content.get(0).get("data"));
        assertEquals("image/png", content.get(0).get("mimeType"));
    }

    // ------------------------------------------------------------- annotations

    @Test
    @SuppressWarnings("unchecked")
    public void annotations_are_emitted_so_clients_stop_assuming_destructive() throws Exception {
        java.util.List<Map<String, Object>> tools =
                (java.util.List<Map<String, Object>>) resultOf(
                        handler.handle(call("tools/list", null, "25"), liveSession())).get("tools");
        java.util.Set<String> declaring = new java.util.HashSet<String>(java.util.Arrays.asList(
                "echo", "get_time", "generate_uuid", "system_info", "numbers"));
        for (Map<String, Object> t : tools) {
            String name = (String) t.get("name");
            Map<String, Object> ann = (Map<String, Object>) t.get("annotations");
            if (declaring.contains(name)) {
                assertNotNull("内置工具必须显式声明注解, 否则客户端按 destructiveHint=true 处理: " + name, ann);
                assertEquals(Boolean.TRUE, ann.get("readOnlyHint"));
                assertEquals(Boolean.FALSE, ann.get("destructiveHint"));
                assertEquals(Boolean.FALSE, ann.get("openWorldHint"));
            } else {
                assertNull("未声明时不得凭空造键: " + name, ann);
            }
        }
    }

    // ----------------------------------------------------------- notifications

    @Test
    public void notification_gets_no_envelope() throws Exception {
        McpProtocolHandler.RequestContext ctx = liveSession();
        McpProtocolHandler.Reply reply = handler.handle(
                notify("notifications/initialized", null), ctx);
        assertTrue(reply.notification);
        assertNull("notification 绝不能产生响应体", reply.envelope);
        assertTrue(ctx.session.isInitialized());
    }

    @Test
    public void failing_notification_still_produces_no_envelope() throws Exception {
        McpProtocolHandler.Reply reply = handler.handle(
                notify("tools/call", "{\"name\":\"boom\"}"), liveSession());
        assertTrue(reply.notification);
        assertNull(reply.envelope);
    }

    @Test
    public void request_without_method_is_invalid() throws Exception {
        Map<String, Object> err = errorOf(handler.handle(
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":99}"), liveSession()));
        assertEquals(Integer.valueOf(McpException.INVALID_REQUEST), err.get("code"));
    }

    @Test
    public void response_never_carries_result_and_error_together() throws Exception {
        Map<String, Object> ok = wire(handler.handle(call("ping", null, "26"), liveSession()));
        assertTrue(ok.containsKey("result") && !ok.containsKey("error"));
        Map<String, Object> bad = wire(handler.handle(call("tools/launch", null, "27"), liveSession()));
        assertTrue(bad.containsKey("error") && !bad.containsKey("result"));
        assertEquals("2.0", ok.get("jsonrpc"));
    }

    // -------------------------------------------------------------- resources

    @Test
    @SuppressWarnings("unchecked")
    public void resources_list_and_read() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("resources/list", null, "28"), liveSession()));
        java.util.List<Map<String, Object>> resources =
                (java.util.List<Map<String, Object>>) r.get("resources");
        assertEquals(3, resources.size());
        assertEquals("z-mcp://self", resources.get(0).get("uri"));

        Map<String, Object> read = resultOf(handler.handle(call("resources/read",
                "{\"uri\":\"z-mcp://self\"}", "29"), liveSession()));
        java.util.List<Map<String, Object>> contents =
                (java.util.List<Map<String, Object>>) read.get("contents");
        assertEquals(1, contents.size());
        assertTrue(((String) contents.get(0).get("text")).contains("9.9.9-test"));
    }

    @Test
    public void unknown_resource_is_not_found() throws Exception {
        Map<String, Object> err = errorOf(handler.handle(call("resources/read",
                "{\"uri\":\"z-mcp://nope\"}", "30"), liveSession()));
        assertEquals(Integer.valueOf(McpException.RESOURCE_NOT_FOUND), err.get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void subscribe_is_refused_because_capability_is_not_claimed() throws Exception {
        Map<String, Object> caps = (Map<String, Object>) resultOf(handler.handle(call("initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"}}", "31"),
                new McpProtocolHandler.RequestContext())).get("capabilities");
        assertEquals("声明了 resources 但未实现订阅, 就不能谎报 subscribe",
                Boolean.FALSE, ((Map<String, Object>) caps.get("resources")).get("subscribe"));

        Map<String, Object> err = errorOf(handler.handle(call("resources/subscribe",
                "{\"uri\":\"z-mcp://self\"}", "32"), liveSession()));
        assertEquals(Integer.valueOf(McpException.METHOD_NOT_FOUND), err.get("code"));
    }

    // --------------------------------------------------------------- logging

    @Test
    public void logging_set_level_is_validated_and_stored() throws Exception {
        McpProtocolHandler.RequestContext ctx = liveSession();
        assertTrue(resultOf(handler.handle(call("logging/setLevel",
                "{\"level\":\"warning\"}", "33"), ctx)).isEmpty());
        assertEquals("warning", ctx.session.getLogLevel());

        Map<String, Object> err = errorOf(handler.handle(call("logging/setLevel",
                "{\"level\":\"verbose\"}", "34"), ctx));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    /**
     * capabilities 里广告了 {@code logging}, 而 {@code logging/setLevel} 存的级别必须真的
     * 决定服务端往下喂什么 —— 否则这个方法和 0.1.x 那批"写进 yaml 没人读"的配置是同一件事,
     * 只是换到了协议侧.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void a_failing_tool_sends_a_log_notification_and_still_returns_isError() throws Exception {
        McpProtocolHandler.RequestContext ctx = liveSession();
        RecordingEmitter frames = withStream(ctx);

        Map<String, Object> result = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"boom\",\"arguments\":{}}", "37"), ctx));
        assertEquals("工具失败不能变成 JSON-RPC error", Boolean.TRUE, result.get("isError"));

        assertEquals(1, frames.size());
        String frame = frames.get(0);
        assertTrue(frame, frame.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(frame, frame.contains("\"method\":\"notifications/message\""));
        assertTrue(frame, frame.contains("\"level\":\"warning\""));
        assertTrue(frame, frame.contains("kaboom"));
        assertTrue(frame, frame.contains("\"logger\":"));
        assertFalse("通知不能带 id: " + frame, frame.contains("\"id\""));
    }

    @Test
    public void set_level_is_a_threshold_not_a_switch() throws Exception {
        McpProtocolHandler.RequestContext ctx = liveSession();
        RecordingEmitter frames = withStream(ctx);
        handler.handle(call("logging/setLevel", "{\"level\":\"error\"}", "38"), ctx);

        handler.handle(call("tools/call", "{\"name\":\"boom\",\"arguments\":{}}", "39"), ctx);
        assertEquals("客户端要 error 以上, warning 就不该出现在流上: " + frames, 0, frames.size());

        handler.handle(call("logging/setLevel", "{\"level\":\"warning\"}", "40"), ctx);
        handler.handle(call("tools/call", "{\"name\":\"boom\",\"arguments\":{}}", "41"), ctx);
        assertEquals(1, frames.size());
        assertTrue(frames.get(0), frames.get(0).contains("\"level\":\"warning\""));
    }

    /** 超时是最该让客户端知道的一种失败, 但它以前一条通知都不发. */
    @Test
    public void a_tool_timeout_is_announced_at_error_level() throws Exception {
        properties.setToolTimeoutSeconds(1);
        McpProtocolHandler.RequestContext ctx = liveSession();
        RecordingEmitter frames = withStream(ctx);

        Map<String, Object> result = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"slow\",\"arguments\":{}}", "42"), ctx));
        assertEquals(Boolean.TRUE, result.get("isError"));
        assertTrue(((String) ((Map<String, Object>) ((java.util.List<Object>) result.get("content"))
                .get(0)).get("text")).contains("timed out"));

        assertEquals(1, frames.size());
        assertTrue(frames.get(0), frames.get(0).contains("\"level\":\"error\""));
        assertTrue(frames.get(0), frames.get(0).contains("notifications/message"));
    }

    /**
     * 关掉超时池 ⇒ 工具在调用线程上直接跑, 异常走的是另一条 catch 分支.
     * 客户端不该因为服务端配了哪个超时值就看到两种形状.
     */
    @Test
    public void a_failure_without_the_timeout_pool_looks_identical() throws Exception {
        properties.setToolTimeoutSeconds(0);
        McpProtocolHandler.RequestContext ctx = liveSession();
        RecordingEmitter frames = withStream(ctx);

        Map<String, Object> result = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"boom\",\"arguments\":{}}", "43"), ctx));
        assertEquals(Boolean.TRUE, result.get("isError"));
        assertEquals(1, frames.size());
        assertTrue(frames.get(0), frames.get(0).contains("\"level\":\"warning\""));
        assertTrue(frames.get(0), frames.get(0).contains("kaboom"));
    }

    /** 没建 GET 流时推日志不该出事, 也不该把工具失败跟着一起吞掉. */
    @Test
    public void log_notifications_without_a_stream_do_not_disturb_the_response() throws Exception {
        Map<String, Object> result = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"boom\",\"arguments\":{}}", "44"), liveSession()));
        assertEquals(Boolean.TRUE, result.get("isError"));
    }

    private RecordingEmitter withStream(McpProtocolHandler.RequestContext ctx) {
        RecordingEmitter frames = new RecordingEmitter();
        ctx.session.addEmitter(frames);
        return frames;
    }

    // -------------------------------------------------------------- prompts

    @Test
    @SuppressWarnings("unchecked")
    public void prompts_list_can_be_empty_and_still_wellformed() throws Exception {
        Map<String, Object> r = resultOf(handler.handle(call("prompts/list", null, "35"), liveSession()));
        assertTrue(r.get("prompts") instanceof java.util.List);
        ((java.util.List<Object>) r.get("prompts")).isEmpty();
        Map<String, Object> err = errorOf(handler.handle(call("prompts/get",
                "{\"name\":\"missing\"}", "36"), liveSession()));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void prompt_with_required_argument_and_composition() throws Exception {
        registry.registerPrompt(new com.zifang.z.mcp.api.dto.McpPromptDto(
                "review", "评审一个工具",
                java.util.Arrays.asList(new com.zifang.z.mcp.api.dto.McpPromptDto.Arg(
                        "tool", "工具名", true)),
                arguments -> java.util.Arrays.asList(
                        com.zifang.z.mcp.api.dto.McpPromptDto.message("user",
                                ContentBlock.text("请评审 " + arguments.get("tool"))))));

        Map<String, Object> missing = errorOf(handler.handle(call("prompts/get",
                "{\"name\":\"review\",\"arguments\":{}}", "37"), liveSession()));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), missing.get("code"));

        Map<String, Object> ok = resultOf(handler.handle(call("prompts/get",
                "{\"name\":\"review\",\"arguments\":{\"tool\":\"echo\"}}", "38"), liveSession()));
        java.util.List<Map<String, Object>> messages = (java.util.List<Map<String, Object>>) ok.get("messages");
        assertEquals("user", messages.get(0).get("role"));
        assertTrue(((String) ((Map<String, Object>) messages.get(0).get("content")).get("text"))
                .contains("echo"));
    }

    // -------------------------------------------------------- 内部错误路径

    @Test
    public void params_must_be_an_object() throws Exception {
        Map<String, Object> err = errorOf(handler.handle(call("tools/list", "[]", "39"), liveSession()));
        assertEquals(Integer.valueOf(McpException.INVALID_PARAMS), err.get("code"));
    }

    @Test
    public void string_ids_survive_the_round_trip() throws Exception {
        Map<String, Object> w = wire(handler.handle(call("ping", null, "\"abc-123\""), liveSession()));
        assertEquals("abc-123", w.get("id"));
    }

    @Test
    public void null_argument_map_is_tolerated() throws Exception {
        try {
            registry.call("generate_uuid", null);
        } catch (Exception e) {
            fail("null arguments 应该被当作空对象: " + e);
        }
    }

    /** 收流上帧原文的 emitter — 通知的形状只能按客户端看到的样子断言. */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> frames = new ArrayList<String>();

        RecordingEmitter() {
            super(Long.valueOf(Long.MAX_VALUE));
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder data = new StringBuilder();
            for (DataWithMediaType part : builder.build()) {
                if (part.getData() != null) data.append(part.getData());
            }
            frames.add(data.toString());
        }

        int size() { return frames.size(); }

        String get(int i) { return frames.get(i); }

        // 压成一行: surefire 在换行处截断断言消息, 一帧多行的 SSE 直接拼进消息只剩 "id:1"
        @Override public String toString() {
            StringBuilder flat = new StringBuilder();
            for (String frame : frames) {
                if (flat.length() > 0) flat.append(" || ");
                flat.append(frame.replace("\r", "").replace("\n", " <LF> "));
            }
            return flat.toString();
        }
    }
}
