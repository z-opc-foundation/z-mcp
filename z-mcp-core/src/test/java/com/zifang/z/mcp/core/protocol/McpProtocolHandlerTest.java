package com.zifang.z.mcp.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.ContentBlock;
import com.zifang.z.mcp.api.dto.McpToolDto;
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
    public void a_constraint_behind_a_ref_is_enforced_end_to_end() throws Exception {
        // #48 的接线层: 官方 python SDK 交出来的 inputSchema 把主要约束藏在 $ref 后面.
        // 校验器自己的单测绿, 不代表 tools/call 真的把这条违规带回给模型 —— 所以这一条
        // 从 JSON-RPC 帧进、从 content[0].text 出, 中间不许有任何"手工平铺 schema"的捷径.
        registry.registerBuiltin("visit", "records a visit",
                "{\"$defs\":{\"Address\":{\"type\":\"object\",\"properties\":{"
                        + "\"city\":{\"type\":\"string\"},"
                        + "\"zipcode\":{\"type\":\"string\",\"pattern\":\"^\\\\d{6}$\"}},"
                        + "\"required\":[\"city\",\"zipcode\"]}},"
                        + "\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/Address\"}},"
                        + "\"required\":[\"a\"]}",
                args -> "ok");

        Map<String, Object> r = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"visit\",\"arguments\":{\"a\":{\"city\":\"hz\"}}}", "45"), liveSession()));
        assertEquals(Boolean.TRUE, r.get("isError"));
        String text = (String) ((java.util.List<Map<String, Object>>) r.get("content")).get(0).get("text");
        assertTrue("要把解析后的路径与字段名带回给模型: " + text,
                text.contains("$.a") && text.contains("zipcode"));

        // 阳性对照: 同一台工具, 参数补齐就必须真跑到实现里去
        Map<String, Object> ok = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"visit\",\"arguments\":{\"a\":{\"city\":\"hz\",\"zipcode\":\"311100\"}}}",
                "46"), liveSession()));
        assertFalse("补齐参数反而被误拒: " + ok, Boolean.TRUE.equals(ok.get("isError")));
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

    // ------------------------------ outputSchema 与"工具自己交回 CallToolResult"这一支

    private static final String COUNT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}},"
                    + "\"required\":[\"count\"]}";

    /** 生产者自己排版的 JSON —— 交回一份带 structuredContent 的结果. */
    private CallToolResult structuredResult(String json) throws Exception {
        return CallToolResult.structured(mapper.readTree(json), json);
    }

    private void registerResultTool(String name, String outputSchema, Object returned) {
        McpRegistry.Builder b = registry.tool(name)
                .description("returns a hand-built result")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}");
        if (outputSchema != null) b.outputSchema(outputSchema);
        b.register(args -> returned);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCall(String name, String id) throws Exception {
        return resultOf(handler.handle(call("tools/call",
                "{\"name\":\"" + name + "\",\"arguments\":{}}", id), liveSession()));
    }

    @SuppressWarnings("unchecked")
    private String verdictText(Map<String, Object> result) {
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) result.get("content");
        assertNotNull("结果里一个内容块都没有: " + result, content);
        assertEquals("判红只该有一块说明: " + content, 1, content.size());
        return (String) content.get(0).get("text");
    }

    @Test
    public void a_violating_call_tool_result_is_reported_as_an_execution_error() throws Exception {
        // 广告的 schema 是自己的承诺: 手工搭的结果没有绕过校验的通道.
        registerResultTool("block_bad", COUNT_SCHEMA, structuredResult("{\"count\":\"seven\"}"));
        Map<String, Object> r = toolsCall("block_bad", "61");
        assertEquals(Boolean.TRUE, r.get("isError"));
        String text = verdictText(r);
        assertTrue("要点名违反的是 outputSchema: " + text,
                text.contains("produced output violating its outputSchema"));
        assertTrue("要带出校验器的原话: " + text,
                text.contains("$.count: expected type integer but got string"));
        assertNull("判红之后不许把违例字节再发一遍: " + r, r.get("structuredContent"));
    }

    @Test
    public void both_return_shapes_give_the_same_verdict_for_the_same_violation() throws Exception {
        // 两支各写一份判定迟早分叉(#48-#55 的校验器只有一份, 出口却曾是两条).
        Map<String, Object> bad = new java.util.LinkedHashMap<String, Object>();
        bad.put("count", "seven");
        registry.tool("via_map").description("returns a map")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .outputSchema(COUNT_SCHEMA).register(args -> bad);
        registerResultTool("via_block", COUNT_SCHEMA, structuredResult("{\"count\":\"seven\"}"));
        String mapVerdict = verdictText(toolsCall("via_map", "62"));
        String blockVerdict = verdictText(toolsCall("via_block", "63"));
        assertEquals("同一个违例在两支上必须同一句话(只换工具名)",
                mapVerdict.replace("via_map", "TOOL"), blockVerdict.replace("via_block", "TOOL"));
    }

    @Test
    public void a_conforming_call_tool_result_keeps_the_producers_own_bytes() throws Exception {
        // 校验是给违例用的, 不是把合规的产物重排一遍.
        String produced = "{ \"count\" : 7 }";
        registerResultTool("block_ok", COUNT_SCHEMA, structuredResult(produced));
        Map<String, Object> r = toolsCall("block_ok", "64");
        assertNull("合规不许变红: " + r, r.get("isError"));
        assertEquals("内容块要原样留着: " + r, produced, verdictText(r));
        assertEquals(7, ((Number) wireMap(r.get("structuredContent")).get("count")).intValue());
    }

    @Test
    public void an_advertised_output_schema_demands_structured_content() throws Exception {
        // 两家参照在"缺 structuredContent"上同判(TS 抛 Output validation error / python model_validate(None)).
        registerResultTool("block_bare", COUNT_SCHEMA, CallToolResult.text("42"));
        Map<String, Object> r = toolsCall("block_bare", "65");
        assertEquals(Boolean.TRUE, r.get("isError"));
        assertTrue("要说清缺的是哪一半: " + verdictText(r),
                verdictText(r).contains("declares an outputSchema but returned no structuredContent"));
    }

    @Test
    public void an_error_result_is_never_relabelled_as_a_schema_violation() throws Exception {
        // isError 是给模型自我纠正的生产者原话, 改判成校验失败会把它盖掉.
        CallToolResult failed = CallToolResult.text("upstream said no");
        failed.setError(true);
        failed.setStructuredContent(mapper.readTree("{\"count\":\"seven\"}"));
        registerResultTool("block_failed", COUNT_SCHEMA, failed);
        Map<String, Object> r = toolsCall("block_failed", "66");
        assertEquals(Boolean.TRUE, r.get("isError"));
        assertEquals("原话一个字不许改", "upstream said no", verdictText(r));
        assertFalse("不许顺手换上校验器的话: " + verdictText(r),
                verdictText(r).contains("outputSchema"));
    }

    @Test
    public void an_undeclared_output_schema_gains_no_constraints() throws Exception {
        // 没广告就没有承诺: 非对象的 structuredContent 也不该被兜底 schema 判红.
        registerResultTool("block_free", null, structuredResult("5"));
        Map<String, Object> r = toolsCall("block_free", "67");
        assertNull("没声明 schema 就不许凭空长出约束: " + r, r.get("isError"));
        assertEquals("5", verdictText(r));
        assertEquals(5, wireNode(r.get("structuredContent")).asInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void a_relayed_result_is_checked_against_the_schema_the_hub_copied() throws Exception {
        // hub 把上游的 outputSchema 逐字抄进目录, 于是这条广告由 hub 对客户负责.
        McpToolDto upstreamTool = new McpToolDto("translated", null, "an upstream tool",
                "ref-upstream", "{\"type\":\"object\",\"properties\":{}}", COUNT_SCHEMA, null);
        final java.util.Map<String, Object> relayed = new java.util.LinkedHashMap<String, Object>();
        relayed.put("count", "seven");                       // fromWire 交回的是普通 Map
        List<String> names = registry.replaceServerTools("ref-upstream",
                java.util.Collections.singletonList(upstreamTool),
                tool -> args -> CallToolResult.structured(relayed, "{\"count\":\"seven\"}"));
        String published = names.get(0);

        java.util.List<Map<String, Object>> tools =
                (java.util.List<Map<String, Object>>) resultOf(
                        handler.handle(call("tools/list", null, "68"), liveSession())).get("tools");
        Map<String, Object> advertised = null;
        for (Map<String, Object> t : tools) if (published.equals(t.get("name"))) advertised = t;
        assertNotNull("目录里该有这条转发工具", advertised);
        assertEquals("广告侧抄的是上游那份 schema",
                mapper.readTree(COUNT_SCHEMA), mapper.valueToTree(advertised.get("outputSchema")));

        Map<String, Object> r = toolsCall(published, "69");
        assertEquals(Boolean.TRUE, r.get("isError"));
        assertTrue("兑现侧要挡下上游的违例字节: " + verdictText(r),
                verdictText(r).contains("produced output violating its outputSchema"));
        assertNull(r.get("structuredContent"));
    }

    // --------------- #62: 其余六条出口(null/文本/字符/数字/布尔/单块/块列表/兜底)也并进同一个兑现口

    /** "长得像对象但不在 Map/JsonNode 那一支"的返回值 —— 消费者最常交回的形状, 从前落到 String.valueOf. */
    public static class CountBean {
        @Override
        public String toString() {
            return "CountBean{count=7}";
        }
    }

    private void registerShapeTool(String name, String outputSchema, final Object returned) {
        McpRegistry.Builder b = registry.tool(name)
                .description("returns a shape that is not a CallToolResult")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}");
        if (outputSchema != null) b.outputSchema(outputSchema);
        b.register(args -> returned);
    }

    /** 九种返回值形状, 覆盖 normalize() 除 CallToolResult 与 Map/JsonNode 之外的全部出口. */
    private Object[][] bypassShapes() {
        return new Object[][]{
                {"null", null},
                {"String", "plain text"},
                {"Character", Character.valueOf('x')},
                {"Number", Integer.valueOf(42)},
                {"Boolean", Boolean.TRUE},
                {"ContentBlock", ContentBlock.text("one block")},
                {"List<ContentBlock>", java.util.Arrays.asList(ContentBlock.text("a"), ContentBlock.text("b"))},
                {"POJO", new CountBean()},
                {"List<String>", java.util.Arrays.asList("a", "b")},
        };
    }

    @SuppressWarnings("unchecked")
    private String firstBlockText(Map<String, Object> r) {
        Object blocks = r.get("content");
        if (!(blocks instanceof List) || ((List<Object>) blocks).isEmpty()) return "-";
        Object first = ((List<Object>) blocks).get(0);
        return first instanceof Map ? String.valueOf(((Map<String, Object>) first).get("text")) : String.valueOf(first);
    }

    @Test
    public void every_bypassing_exit_now_honours_the_advertised_schema() throws Exception {
        // 探针量过的现状(改之前, 九格逐个现量): 全部 isError=null、structuredContent 缺席, 广告形同虚设.
        // 一张表一次断言(#56 的纪律): 漏了哪一条出口, 那一格自己点名.
        List<String> wrong = new ArrayList<String>();
        int i = 0;
        for (Object[] shape : bypassShapes()) {
            String name = "exit_" + (++i);
            registerShapeTool(name, COUNT_SCHEMA, shape[1]);
            Map<String, Object> r = toolsCall(name, "7" + i);
            if (!Boolean.TRUE.equals(r.get("isError"))) {
                wrong.add(shape[0] + " 仍然静默成功: " + r);
            } else if (!firstBlockText(r).contains("declares an outputSchema but returned no structuredContent")) {
                wrong.add(shape[0] + " 说的不是缺字段那句话: " + firstBlockText(r));
            } else if (r.containsKey("structuredContent")) {
                wrong.add(shape[0] + " 判红之后还把字节发出去了: " + r);
            }
        }
        assertEquals("九条形状都该经同一个兑现口: " + wrong, 0, wrong.size());
    }

    @Test
    public void the_same_shapes_stay_untouched_when_nothing_was_advertised() throws Exception {
        // 兑现是广告换来的义务: 没广告就不许凭空长出约束(与上面那张表只差一句 outputSchema).
        String[] expectedText = {"-", "plain text", "x", "42", "true", "one block", "a",
                "CountBean{count=7}", "[a, b]"};
        int[] expectedBlocks = {0, 1, 1, 1, 1, 1, 2, 1, 1};
        List<String> wrong = new ArrayList<String>();
        int i = 0;
        for (Object[] shape : bypassShapes()) {
            String name = "free_" + (++i);
            registerShapeTool(name, null, shape[1]);
            Map<String, Object> r = toolsCall(name, "8" + i);
            Object blocks = r.get("content");
            int n = blocks instanceof java.util.List ? ((java.util.List<?>) blocks).size() : -1;
            if (r.get("isError") != null) {
                wrong.add(shape[0] + " 没广告也被判红: " + r);
            } else if (n != expectedBlocks[i - 1] || !expectedText[i - 1].equals(firstBlockText(r))) {
                wrong.add(shape[0] + " 形状被改了: blocks=" + n + " 期望=" + expectedBlocks[i - 1]
                        + " text=" + firstBlockText(r) + " 期望=" + expectedText[i - 1]);
            }
        }
        assertEquals("未广告的出口一格都不该动: " + wrong, 0, wrong.size());

        // 阳性对照: 同一格("String")一广告就该判红 —— 否则上面那句"一格都没动"没有牙.
        registerShapeTool("free_prey", COUNT_SCHEMA, "plain text");
        Map<String, Object> prey = toolsCall("free_prey", "90");
        assertEquals(Boolean.TRUE, prey.get("isError"));
        assertTrue("对照要说出缺的是哪一半: " + firstBlockText(prey),
                firstBlockText(prey).contains("declares an outputSchema but returned no structuredContent"));
    }

    @Test
    public void json_inside_a_text_block_is_never_reinterpreted_as_structured_content() throws Exception {
        // 只认 structuredContent 这一半(两家参照同口径): 把合规 JSON 塞进文本块不算兑现.
        registerShapeTool("smuggle", COUNT_SCHEMA,
                java.util.Collections.singletonList(ContentBlock.text("{\"count\":7}")));
        Map<String, Object> r = toolsCall("smuggle", "91");
        assertEquals(Boolean.TRUE, r.get("isError"));
        assertTrue("要说清缺的是 structuredContent: " + firstBlockText(r),
                firstBlockText(r).contains("no structuredContent"));
        // 同一份字节走 structuredContent 那一半就是合规的 —— 这句不是推的, 现取.
        java.util.Map<String, Object> same = new java.util.LinkedHashMap<String, Object>();
        same.put("count", Integer.valueOf(7));
        registerShapeTool("legitimate", COUNT_SCHEMA, same);
        Map<String, Object> ok = toolsCall("legitimate", "92");
        assertNull("合规字节不许变红: " + ok, ok.get("isError"));
        assertEquals(7, wireMap(ok.get("structuredContent")).get("count"));
    }

    @Test
    public void an_unserializable_value_keeps_its_own_diagnosis() throws Exception {
        // 折形状那一步的 catch 先产出 isError, 兑现那一步必须放行原话: 顺序反了会把病因盖成"缺字段".
        java.util.Map<String, Object> boom = new java.util.LinkedHashMap<String, Object>();
        boom.put("count", new Object());                       // Jackson 写不出没有可序列化属性的裸 Object
        registerShapeTool("boom_shape", COUNT_SCHEMA, boom);
        Map<String, Object> r = toolsCall("boom_shape", "93");
        assertEquals(Boolean.TRUE, r.get("isError"));
        String text = firstBlockText(r);
        assertTrue("要保留序列化失败这句原话: " + text,
                text.contains("returned a value that cannot be serialized"));
        assertFalse("不许改判成缺 structuredContent: " + text, text.contains("no structuredContent"));
    }

    // --------------------------------------------------- #63: 广告槽位的形状(MCP schema.json 那两个槽)

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> listedTools(String id) throws Exception {
        return (java.util.List<Map<String, Object>>) resultOf(
                handler.handle(call("tools/list", null, id), liveSession())).get("tools");
    }

    private Map<String, Object> listedEntry(String name, String id) throws Exception {
        for (Map<String, Object> t : listedTools(id)) if (name.equals(t.get("name"))) return t;
        return null;
    }

    private void registerSchemaProbe(String name, String inputSchema, String outputSchema, Object returned) {
        McpRegistry.Builder b = registry.tool(name).description("schema probe " + name)
                .inputSchema(inputSchema);
        if (outputSchema != null) b.outputSchema(outputSchema);
        b.register(args -> returned);
    }

    /**
     * 复述 spec 2025-06-18 {@code schema.json} 对 {@code inputSchema}/{@code outputSchema} 的形状要求:
     * 根 {@code type} 必须在且恒为 "object"、{@code properties} 整槽与其中每一项都必须是对象、
     * {@code required} 必须是字符串数组. 官方 TS 客户端的 {@code ToolSchema} 逐条同判.
     */
    private void assertMcpSchemaSlot(JsonNode schema, String where, List<String> wrong) {
        if (!schema.isObject()) {
            wrong.add(where + " 根本不是对象: " + schema);
            return;
        }
        JsonNode type = schema.get("type");
        if (type == null || !type.isTextual() || !"object".equals(type.asText())) {
            wrong.add(where + " 的根 type 不是 \"object\": " + schema);
        }
        JsonNode props = schema.get("properties");
        if (props != null) {
            if (!props.isObject()) {
                wrong.add(where + ".properties 整槽不是对象: " + props);
            } else {
                for (java.util.Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
                    String f = it.next();
                    if (!props.get(f).isObject()) wrong.add(where + ".properties." + f + " 不是 schema 对象: " + props.get(f));
                }
            }
        }
        JsonNode req = schema.get("required");
        if (req != null) {
            if (!req.isArray()) {
                wrong.add(where + ".required 不是数组: " + req);
            } else {
                for (JsonNode r : req) if (!r.isTextual()) wrong.add(where + ".required 含非字符串项: " + req);
            }
        }
    }

    @Test
    public void a_declared_schema_without_a_root_type_is_completed_not_discarded() throws Exception {
        registerSchemaProbe("no_root_type", "{\"type\":\"object\",\"properties\":{}}",
                "{\"properties\":{\"r\":{\"type\":\"integer\"}},\"required\":[\"r\"]}", "ok");
        JsonNode out = wireNode(listedEntry("no_root_type", "101").get("outputSchema"));
        assertEquals("缺的根 type 要补成 object: " + out, "object", out.path("type").asText());
        assertTrue("补形状不许把真约束一起丢掉: " + out, out.path("properties").path("r").path("type").isTextual());
        assertEquals("required 原样保留: " + out, mapper.readTree("[\"r\"]"), out.path("required"));
        List<String> wrong = new ArrayList<String>();
        assertMcpSchemaSlot(out, "outputSchema", wrong);
        assertEquals(wrong.toString(), 0, wrong.size());
    }

    @Test
    public void a_schema_whose_root_type_is_something_else_is_never_advertised() throws Exception {
        registerSchemaProbe("string_root_out", "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"string\"}", "ok");
        registerSchemaProbe("string_root_in", "{\"type\":\"string\"}", null, "ok");
        registerSchemaProbe("well_formed_neighbour", "{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}}}", "ok");

        JsonNode out = wireNode(listedEntry("string_root_out", "102").get("outputSchema"));
        JsonNode in = wireNode(listedEntry("string_root_in", "103").get("inputSchema"));
        JsonNode permissive = mapper.readTree("{\"type\":\"object\",\"properties\":{}}");
        assertEquals("非 object 的根型不能原样上线: " + out, permissive, out);
        assertEquals("inputSchema 同理: " + in, permissive, in);
        // 阳性对照: 同一张目录里合规的那份一格都不该被削弱(否则上面两句是"全都宽化"的假绿).
        JsonNode kept = wireNode(listedEntry("well_formed_neighbour", "104").get("inputSchema"));
        assertEquals("string", kept.path("properties").path("p").path("type").asText());
        assertTrue("合规广告不许被顺手抹掉: " + kept, kept.path("properties").path("p").size() > 0);
    }

    @Test
    public void a_property_entry_that_is_not_a_schema_object_widens_on_its_own() throws Exception {
        registerSchemaProbe("one_bad_property", "{\"type\":\"object\",\"properties\":{\"a\":5,\"b\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"r\":null},\"required\":[\"r\"]}", "ok");
        JsonNode in = wireNode(listedEntry("one_bad_property", "105").get("inputSchema"));
        assertTrue("坏的那一项要退化成接受任何值: " + in, in.path("properties").path("a").isObject());
        assertEquals("退化就是空 schema, 不许留下别的键: " + in, 0, in.path("properties").path("a").size());
        assertEquals("但它不该连累同槽的兄弟: " + in, "string", in.path("properties").path("b").path("type").asText());
        JsonNode out = wireNode(listedEntry("one_bad_property", "106").get("outputSchema"));
        assertTrue("properties 里写 null 也算不合形: " + out, out.path("properties").path("r").isObject());
        assertEquals("required 与它无关, 不许一起消失: " + out, mapper.readTree("[\"r\"]"), out.path("required"));

        // 整槽都不是对象的那一格: 只能不广告这一槽, 别的一格都不许动.
        registerSchemaProbe("whole_slot_bad", "{\"type\":\"object\",\"properties\":5,\"required\":[\"a\"]}",
                "{\"type\":\"object\",\"properties\":[],\"required\":[\"r\"]}", "ok");
        JsonNode slotIn = wireNode(listedEntry("whole_slot_bad", "1061").get("inputSchema"));
        assertFalse("properties 整槽不合形就不许上线: " + slotIn, slotIn.has("properties"));
        assertEquals("同一条工具的 required 该原样留着: " + slotIn, mapper.readTree("[\"a\"]"), slotIn.path("required"));
        JsonNode slotOut = wireNode(listedEntry("whole_slot_bad", "1062").get("outputSchema"));
        assertFalse(slotOut.has("properties"));
        assertEquals("object", slotOut.path("type").asText());
    }

    @Test
    public void a_required_slot_that_is_not_an_array_is_dropped_without_touching_the_rest() throws Exception {
        registerSchemaProbe("required_scalar", "{\"type\":\"object\",\"required\":\"a\"}",
                "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}},\"required\":\"r\"}", "ok");
        JsonNode in = wireNode(listedEntry("required_scalar", "107").get("inputSchema"));
        JsonNode out = wireNode(listedEntry("required_scalar", "108").get("outputSchema"));
        assertFalse("不合形的 required 只能整槽不广告: " + in, in.has("required"));
        assertFalse(out.has("required"));
        assertEquals("折形状只能削弱, 不许顺手发明槽位: " + in, mapper.readTree("{\"type\":\"object\"}"), in);
        assertEquals("同槽其余部分原样: " + out, "integer", out.path("properties").path("r").path("type").asText());
    }

    @Test
    public void a_required_array_keeps_only_its_string_entries() throws Exception {
        // 一张表一次断言(#56 的纪律): "该留的没留"与"该丢的没丢"各自点名.
        Object[][] rows = {
                {"mixed", "[\"r\",5,null]", "[\"r\"]", true},
                {"all-bad", "[5,7,null]", null, false},
        };
        List<String> wrong = new ArrayList<String>();
        int i = 0;
        for (Object[] row : rows) {
            String name = "req_" + (++i);
            registerSchemaProbe(name, "{\"type\":\"object\",\"properties\":{}}",
                    "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}},\"required\":" + row[1] + "}", "ok");
            JsonNode out = wireNode(listedEntry(name, "10" + i + "9").get("outputSchema"));
            JsonNode expected = row[2] == null ? null : mapper.readTree(String.valueOf(row[2]));
            JsonNode actual = out.has("required") ? out.get("required") : null;
            if (!mapper.valueToTree(actual).equals(mapper.valueToTree(expected))) {
                wrong.add(row[0] + " 得到 " + actual + " 期望 " + expected);
            }
        }
        assertEquals(wrong.toString(), 0, wrong.size());
    }

    @Test
    public void unknown_keywords_and_refs_survive_the_repair() throws Exception {
        String declared = "{\"type\":\"object\",\"z-mcp/x\":1,\"additionalProperties\":false,"
                + "\"$defs\":{\"R\":{\"type\":\"object\"}},\"$ref\":\"#/$defs/R\","
                + "\"patternProperties\":{\"^a\":{\"type\":\"string\"}}}";
        registerSchemaProbe("keeps_extra", declared, declared, "ok");
        Map<String, Object> entry = listedEntry("keeps_extra", "111");
        assertEquals("规范没锁 additionalProperties, 抹掉别人写的键是替上游做决定: ",
                mapper.readTree(declared), wireNode(entry.get("inputSchema")));
        assertEquals("outputSchema 同理: ",
                mapper.readTree(declared), wireNode(entry.get("outputSchema")));
    }

    @Test
    public void a_malformed_inputSchema_does_not_evict_its_neighbours() throws Exception {
        assertCatalogSurvives("inputSchema", new String[]{
                "{\"type\":\"string\"}", "{\"properties\":{\"a\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"a\":5}}", "{\"type\":\"object\",\"required\":\"a\"}",
                "{\"type\":\"object\",\"properties\":{\"a\":5,\"b\":6}}"});
    }

    @Test
    public void a_malformed_outputSchema_does_not_evict_its_neighbours() throws Exception {
        assertCatalogSurvives("outputSchema", new String[]{
                "{\"type\":\"string\"}", "{\"properties\":{\"r\":{\"type\":\"integer\"}}}",
                "{\"type\":\"object\",\"properties\":{\"r\":5}}", "{\"type\":\"object\",\"required\":\"r\"}",
                "{\"type\":\"object\",\"properties\":{\"r\":5},\"required\":[\"r\",7]}"});
    }

    /**
     * 这一支才是 #63 的病象本身: 官方 TS 客户端把<b>整个</b> tools/list 结果过一次
     * {@code ListToolsResultSchema}({@code shared/protocol.js:696}), 一条工具的形状不合形
     * ⇒ 同目录里所有别的工具一起看不见. 所以判据不是"那条工具被宽化了", 而是"目录还剩几条".
     */
    private void assertCatalogSurvives(String slot, String[] malformed) throws Exception {
        List<String> wrong = new ArrayList<String>();
        int i = 0;
        for (String declared : malformed) {
            String name = "poison_" + slot + "_" + (++i);
            registerSchemaProbe(name,
                    "inputSchema".equals(slot) ? declared : "{\"type\":\"object\",\"properties\":{}}",
                    "outputSchema".equals(slot) ? declared : "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}}}",
                    "ok");
            registry.tool("alive_a_" + i).description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}")
                    .register(args -> "ok");
            registry.tool("alive_b_" + i).description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .outputSchema("{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}},\"required\":[\"r\"]}")
                    .register(args -> "ok");

            Map<String, Object> entry = listedEntry(name, "12" + i);
            assertNotNull("目录里读不到这条工具: " + name, entry);
            assertEquals("一条畸形广告不该让别的工具消失: " + name, 3,
                    countAlive(i, listedTools("12" + i)));
            JsonNode wire = wireNode(entry.get(slot));
            assertMcpSchemaSlot(wire, name + "." + slot, wrong);
            String otherSlot = "inputSchema".equals(slot) ? "outputSchema" : "inputSchema";
            assertMcpSchemaSlot(wireNode(entry.get(otherSlot)), name + "." + otherSlot, wrong);
        }
        assertEquals(wrong.toString(), 0, wrong.size());
    }

    private int countAlive(int i, java.util.List<Map<String, Object>> tools) {
        int alive = 0;
        for (Map<String, Object> t : tools) {
            String n = String.valueOf(t.get("name"));
            if (n.equals("poison_inputSchema_" + i) || n.equals("poison_outputSchema_" + i)
                    || n.equals("alive_a_" + i) || n.equals("alive_b_" + i)) alive++;
        }
        return alive;
    }

    /**
     * 真实读法那一层: 官方客户端是分页读目录的, 一条畸形广告不能让别人消失.
     * pageSize 压到 1 ⇒ 每一页都要能被 {@code ListToolsResultSchema} 收下(zod 按整页判定),
     * 遍历完必须还能看见那两条合规的.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void a_poisoned_entry_does_not_break_pagination() throws Exception {
        registerSchemaProbe("poison_z1", "{\"type\":\"string\"}", "{\"type\":\"object\",\"required\":\"r\"}", "ok");
        registerSchemaProbe("poison_z2", "{\"type\":\"object\",\"properties\":{\"a\":5}}",
                "{\"properties\":{\"r\":{\"type\":\"integer\"}}}", "ok");
        registry.tool("alive_z").description("d")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}")
                .outputSchema("{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}},\"required\":[\"r\"]}")
                .register(args -> "ok");
        registry.tool("alive_y").description("d")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}")
                .outputSchema("{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}},\"required\":[\"r\"]}")
                .register(args -> "ok");

        properties.setPageSize(1);
        List<String> seen = new ArrayList<String>();
        List<String> wrong = new ArrayList<String>();
        String cursor = null;
        int pages = 0;
        Map<String, Object> page;
        do {
            page = resultOf(handler.handle(call("tools/list",
                    cursor == null ? null : "{\"cursor\":\"" + cursor + "\"}", "1" + (++pages)),
                    liveSession()));
            java.util.List<Map<String, Object>> items =
                    (java.util.List<Map<String, Object>>) page.get("tools");
            assertTrue("分页走到一半给出空页: page=" + pages, !items.isEmpty());
            assertTrue("每页至多 pageSize 项: " + items, items.size() <= 1);
            for (Map<String, Object> t : items) {
                String name = (String) t.get("name");
                assertFalse("分页重复项: " + name, seen.contains(name));
                seen.add(name);
                for (String slot : new String[]{"inputSchema", "outputSchema"}) {
                    if (t.get(slot) == null) continue;
                    assertMcpSchemaSlot(wireNode(t.get(slot)), name + "." + slot, wrong);
                }
            }
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);

        assertTrue("合规的那条要还在目录里: " + seen, seen.contains("alive_z"));
        assertTrue("合规的那条要还在目录里: " + seen, seen.contains("alive_y"));
        assertTrue("畸形的那条也在(只是形状被折过): " + seen, seen.contains("poison_z1"));
        assertEquals("每一页的形状都要过得了严格客户端: " + wrong, 0, wrong.size());
    }

    /**
     * 根值连对象都不是的那一族(数组/字符串/数字/null/不可解) —— 这一格不是补形状能救的,
     * 只能整份换成宽 schema. 变异体 M69_12(把这条分支摘掉, 让数组直接 `(ObjectNode)` 强转)
     * 在别的手法里会一路炸成 JSON-RPC error, 整个 tools/list 一条工具都读不到.
     */
    @Test
    public void a_schema_that_is_not_even_an_object_degrades_without_taking_the_catalog() throws Exception {
        String[] roots = {"[]", "[1,2]", "\"not-json\"", "\"{\\\"type\\\":\\\"object\\\"}\"",
                "5", "true", "null", "{", "{\"type\":", "   "};
        List<String> wrong = new ArrayList<String>();
        JsonNode permissive = mapper.readTree("{\"type\":\"object\",\"properties\":{}}");
        int i = 0;
        for (String declared : roots) {
            String name = "not_object_" + (++i);
            registerSchemaProbe(name, declared, declared, "ok");
            registry.tool("alive_" + i).description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}")
                    .register(args -> "ok");
            Map<String, Object> entry = listedEntry(name, "14" + i);
            assertNotNull("根值不合形的那条也要还在目录里: " + name, entry);
            int alive = 0;
            for (Map<String, Object> t : listedTools("14" + i)) {
                String n = String.valueOf(t.get("name"));
                if (n.equals(name) || n.equals("alive_" + i)) alive++;
            }
            assertEquals("一条不合形的广告不该让别人读不到: " + name, 2, alive);
            for (String slot : new String[]{"inputSchema", "outputSchema"}) {
                if ("outputSchema".equals(slot) && declared.trim().isEmpty()) {
                    // 空白声明本来就不进线格(toolWire 的非空闸门), 这一格与折形状无关: 槽位必须缺席.
                    if (entry.get(slot) != null) {
                        wrong.add(name + "." + slot + " 空白声明不该被广告出去, 却带上了 " + entry.get(slot));
                    }
                    continue;
                }
                JsonNode wire = wireNode(entry.get(slot));
                if (!wire.equals(permissive)) wrong.add(name + "." + slot + " 得到 " + wire + " 期望 " + permissive);
            }
        }
        assertEquals(wrong.toString(), 0, wrong.size());
        // 阳性对照: 合规的那一份不许被一起换成宽 schema(否则上面两句是"全都塌成兜底"的假绿).
        registerSchemaProbe("not_object_control", "{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"string\"}}}",
                null, "ok");
        JsonNode kept = wireNode(listedEntry("not_object_control", "1499").get("inputSchema"));
        assertEquals("string", kept.path("properties").path("p").path("type").asText());
    }

    @Test
    public void what_we_advertise_is_what_we_enforce() throws Exception {
        // 兑现与广告共用同一份折好的形状: 一边宽化另一边不宽化, 就会出现"看不见约束却被打红".
        java.util.Map<String, Object> bad = new java.util.LinkedHashMap<String, Object>();
        bad.put("count", "seven");
        java.util.Map<String, Object> good = new java.util.LinkedHashMap<String, Object>();
        good.put("count", Integer.valueOf(7));
        registerSchemaProbe("declared_string_root", "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"string\"}", good);
        registerSchemaProbe("declared_string_root_in", "{\"type\":\"string\"}", null, good);
        registerSchemaProbe("strict_control", "{\"type\":\"object\",\"properties\":{}}", COUNT_SCHEMA, bad);

        Map<String, Object> r = toolsCall("declared_string_root", "131");
        assertNull("广告已折成宽 schema, 兑现不许还按原来那份判红: " + r, r.get("isError"));
        assertTrue("对象返回值该照常带 structuredContent: " + r, r.containsKey("structuredContent"));

        // 改前实测: 一份 {"type":"string"} 的 inputSchema 会让校验器把每个对象入参判成违例,
        // 于是这条工具被自己的畸形广告锁死 —— 线格式与校验必须吃同一份折好的形状.
        Map<String, Object> called = resultOf(handler.handle(call("tools/call",
                "{\"name\":\"declared_string_root_in\",\"arguments\":{\"a\":\"text\"}}", "132"), liveSession()));
        assertFalse("不合形的 inputSchema 不该把工具锁死: " + verdictText(called),
                verdictText(called).contains("invalid arguments"));

        // 阳性对照: 合规的严格广告仍然兑现 —— 上面两句不是"兑现整条被摘掉"的假绿.
        Map<String, Object> control = toolsCall("strict_control", "133");
        assertEquals(Boolean.TRUE, control.get("isError"));
        assertTrue("对照要说清违的是 outputSchema: " + verdictText(control),
                verdictText(control).contains("produced output violating its outputSchema"));
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

    /**
     * #64: 宿主 composer 交回来的是<b>裸</b> Map 列表, 而协议把 PromptMessage 定成
     * role ∈ {user, assistant} + content 必须是带 type 的内容块. 官方两个客户端都按
     * <b>整份</b> GetPromptResult 判定 ⇒ 一条坏消息把同批那条合规消息一起带走, 而客户端
     * 只报一句 Invalid input(逐格实测读数在 README #64 一节).
     *
     * <p>所以判据有两层: 这一份结果不许上线, 以及服务端要说出是第几条、坏在哪个字段.
     */
    @Test
    public void a_composed_message_the_protocol_forbids_is_refused_and_named() throws Exception {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(new Object[]{"role=system", composedMsg("system"), "message 1"});
        rows.add(new Object[]{"role=null", composedMsg(null), "message 1"});
        rows.add(new Object[]{"role=User(大写不算合规)", composedMsg("User"), "message 1"});
        Map<String, Object> noContent = new java.util.LinkedHashMap<String, Object>();
        noContent.put("role", "user");
        rows.add(new Object[]{"content 整槽缺失", noContent, "message 1"});
        rows.add(new Object[]{"content 是裸字符串", composedContent("plain string"), "message 1"});
        rows.add(new Object[]{"content 是没有 type 的 map",
                composedContent(new java.util.LinkedHashMap<String, Object>()), "message 1"});
        rows.add(new Object[]{"整条 message 是空 map",
                new java.util.LinkedHashMap<String, Object>(), "message 1"});
        rows.add(new Object[]{"整条 message 是 null", null, "message 1"});
        rows.add(new Object[]{"composer 直接返回 null", SENTINEL_NULL_LIST, "null messages list"});

        List<String> wrong = new ArrayList<String>();
        int i = 0;
        for (Object[] row : rows) {
            String name = "poison_" + (++i);
            List<Map<String, Object>> messages =
                    row[1] == SENTINEL_NULL_LIST ? null : secondSlot(row[1]);
            registerComposedPrompt(name, messages);
            Map<String, Object> w = wire(handler.handle(call("prompts/get",
                    "{\"name\":\"" + name + "\",\"arguments\":{}}", "4" + i), liveSession()));
            if (w.get("error") == null) {
                wrong.add(row[0] + " 没被拒, 直接发上线了: " + w.get("result"));
                continue;
            }
            if (w.get("result") != null) {
                wrong.add(row[0] + " result 与 error 同时出现: " + w);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> err = (Map<String, Object>) w.get("error");
            if (!Integer.valueOf(McpException.INVALID_PARAMS).equals(err.get("code"))) {
                wrong.add(row[0] + " 没有按 -32602 拒, 而是 " + err);
                continue;
            }
            String msg = String.valueOf(err.get("message"));
            if (!msg.contains(String.valueOf(row[2]))) {
                wrong.add(row[0] + " 的话里点不出问题所在(要含 \"" + row[2] + "\"): " + msg);
            }
            if (row[1] != SENTINEL_NULL_LIST && msg.contains("\"messages\"")) {
                wrong.add(row[0] + " 拒答里漏出了宿主的消息体: " + msg);
            }
        }
        assertEquals("逐格读数: " + wrong, 0, wrong.size());
    }

    /** 阳性对照: 合规的两条消息经过这道闸之后字节不动, 一条都不该少. */
    @Test
    @SuppressWarnings("unchecked")
    public void a_wellformed_composition_keeps_its_own_bytes_through_the_message_gate() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(com.zifang.z.mcp.api.dto.McpPromptDto.message("user", ContentBlock.text("请评审 echo")));
        messages.add(com.zifang.z.mcp.api.dto.McpPromptDto.message("assistant", ContentBlock.text("好的")));
        registerComposedPrompt("clean", messages);
        Map<String, Object> ok = resultOf(handler.handle(call("prompts/get",
                "{\"name\":\"clean\",\"arguments\":{}}", "50"), liveSession()));
        List<Map<String, Object>> wire = (List<Map<String, Object>>) ok.get("messages");
        assertEquals("闸不许顺手吃掉一条: " + wire, 2, wire.size());
        assertEquals(messages, wire);
    }

    /**
     * #66: 上一道闸只查到"`type` 是一个字符串"为止, 于是 ContentBlock.raw(宿主手搓的 map)
     * 能交出 `type:"image"` 却没有 `data` 的消息 —— 官方 TS 1.30.1 与 python 1.27.1 都按
     * <b>整份</b> GetPromptResult 判定, 这一条把同批那条合规邻居一起带走.
     *
     * <p>每格的判决都量过, 不是推的: 12 格来自 ref66_cells.json(19 格那张表), 7 格来自
     * ref66b_cells.json(专测"候选闸门会不会比客户端严"的 12 格), 两条腿逐格同判.
     * 唯一一格例外是 resource_inner_text_number —— 它与已测的 cb_image_data_number
     * 是同一判据(必填字符串给了数字), 但两腿都没逐格交过这一形, 名字里明写.
     */
    @Test
    public void content_blocks_that_are_not_one_of_the_five_branches_are_refused_and_named()
            throws Exception {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(new Object[]{"cb_type_number", "type 不是字符串(旧闸已管)", block("type", 5, "text", "x"),
                "not a typed content block"});
        rows.add(new Object[]{"cb_type_null", "type 为 null(旧闸已管)", block("type", null, "text", "x"),
                "not a typed content block"});
        rows.add(new Object[]{"cb_type_missing", "type 整槽缺失(旧闸已管)", block("text", "x"),
                "not a typed content block"});
        rows.add(new Object[]{"cb_banana", "type=banana 不在五支里", block("type", "banana", "text", "x"),
                "content type \"banana\" is not one of"});
        rows.add(new Object[]{"cb_text_missing_text", "text 缺 text", block("type", "text"), "string field \"text\""});
        rows.add(new Object[]{"cb_text_number", "text 的 text 是数字", block("type", "text", "text", 5),
                "string field \"text\""});
        rows.add(new Object[]{"cb_text_null", "text 的 text 是 null", block("type", "text", "text", null),
                "string field \"text\""});
        rows.add(new Object[]{"cb_image_missing_data", "image 缺 data", block("type", "image", "mimeType", "image/png"),
                "string field \"data\""});
        rows.add(new Object[]{"cb_image_missing_mime", "image 缺 mimeType", block("type", "image", "data", "AAAA"),
                "string field \"mimeType\""});
        rows.add(new Object[]{"cb_image_data_number", "image 的 data 是数字", block("type", "image", "data", 5,
                "mimeType", "image/png"), "string field \"data\""});
        rows.add(new Object[]{"cb_image_mime_number", "image 的 mimeType 是数字", block("type", "image", "data", "AAAA",
                "mimeType", 5), "string field \"mimeType\""});
        rows.add(new Object[]{"cb_audio_missing_both", "audio 支内必填全缺", block("type", "audio"),
                "string field \"data\""});
        rows.add(new Object[]{"cb_audio_missing_mime", "audio 缺 mimeType(我们自己的 builder 也交得出这一形)",
                block("type", "audio", "data", "AAAA"), "string field \"mimeType\""});
        rows.add(new Object[]{"cb_link_missing_uri", "resource_link 缺 uri", block("type", "resource_link", "name", "n"),
                "string field \"uri\""});
        rows.add(new Object[]{"cb_link_missing_name", "resource_link 缺 name", block("type", "resource_link", "uri", "z-mcp://y"),
                "string field \"name\""});
        rows.add(new Object[]{"cb_link_uri_number", "resource_link 的 uri 是数字",
                block("type", "resource_link", "name", "n", "uri", 5), "string field \"uri\""});
        rows.add(new Object[]{"cb_resource_missing_inner", "resource 缺里层对象", block("type", "resource"),
                "an object field \"resource\""});
        rows.add(new Object[]{"cb_resource_inner_not_map", "resource 的里层是字符串",
                block("type", "resource", "resource", "oops"), "an object field \"resource\""});
        rows.add(new Object[]{"cb_resource_inner_empty", "resource 的里层是空对象",
                block("type", "resource", "resource", block()), "string field \"uri\" inside \"resource\""});
        rows.add(new Object[]{"cb_resource_inner_no_uri", "resource 的里层缺 uri",
                block("type", "resource", "resource", block("text", "x")),
                "string field \"uri\" inside \"resource\""});
        rows.add(new Object[]{"cb_resource_inner_no_text", "resource 的里层既无 text 也无 blob",
                block("type", "resource", "resource", block("uri", "z-mcp://y")),
                "either a string \"text\" or a string"});
        rows.add(new Object[]{"cb_resource_inner_text_null", "resource 的里层 text 是 null",
                block("type", "resource", "resource", block("uri", "z-mcp://y", "text", null)),
                "either a string \"text\" or a string"});
        rows.add(new Object[]{"x_resource_inner_text_number", "resource 的里层 text 是数字(同判据推得, 两腿未逐格交过这一形)",
                block("type", "resource", "resource", block("uri", "z-mcp://y", "text", 5)),
                "either a string \"text\" or a string"});

        List<String> wrong = new ArrayList<String>();
        List<String> refused = new ArrayList<String>();
        int i = 0;
        for (Object[] row : rows) {
            String name = "branch_" + (++i);
            registerComposedPrompt(name, secondSlot(composedContent(row[2])));
            Map<String, Object> w = wire(handler.handle(call("prompts/get",
                    "{\"name\":\"" + name + "\",\"arguments\":{}}", "6" + i), liveSession()));
            if (w.get("error") == null) {
                wrong.add(row[0] + "| " + row[1] + " 没被拒, 字节原样上线了: " + w.get("result"));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> err = (Map<String, Object>) w.get("error");
            if (!Integer.valueOf(McpException.INVALID_PARAMS).equals(err.get("code"))) {
                wrong.add(row[0] + "| 没有按 -32602 拒, 而是 " + err);
                continue;
            }
            String msg = String.valueOf(err.get("message"));
            if (!msg.contains(String.valueOf(row[3]))) {
                wrong.add(row[0] + "| 的话里点不出问题所在(要含 " + row[3] + "): " + msg);
            }
            if (!msg.contains("message 1")) {
                wrong.add(row[0] + "| 的话里点不出是第几条: " + msg);
            }
            refused.add(name + " " + msg);
        }
        assertEquals("逐格读数: " + wrong, 0, wrong.size());
        assertEquals("闸门只该拒掉有读数的格, 拒少了说明判据没接上", rows.size(), refused.size());
    }

    /**
     * #66 的反向半: 官方两条腿放行的 9 形必须一个都不拦.
     *
     * <p>这一半才是"闸门比客户端严"的保险 —— 客户端容忍的形状被我们 -32602 掉, 是新的
     * 互操作故障. 里层同时带 text 与 blob、带规范里没写的多余字段、text 为空串这三格
     * 都实测过 ACCEPT(ref66b_cells.json), 所以不许把它们收进判据.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void every_branch_the_reference_clients_accept_still_passes_the_content_gate()
            throws Exception {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(new Object[]{"cb_valid_text", "text", block("type", "text", "text", "x")});
        rows.add(new Object[]{"cb_valid_image", "image", block("type", "image", "data", "AAAA", "mimeType", "image/png")});
        rows.add(new Object[]{"cb_valid_audio", "audio", block("type", "audio", "data", "AAAA", "mimeType", "audio/wav")});
        rows.add(new Object[]{"cb_valid_resource_link", "resource_link",
                block("type", "resource_link", "name", "n", "uri", "z-mcp://y")});
        rows.add(new Object[]{"cb_valid_resource_text", "resource 带 text",
                block("type", "resource", "resource", block("uri", "z-mcp://y", "text", "x"))});
        rows.add(new Object[]{"cb_valid_resource_blob", "resource 带 blob",
                block("type", "resource", "resource", block("uri", "z-mcp://y", "blob", "AAAA"))});
        rows.add(new Object[]{"cb_valid_text_empty", "text 为空串(我们自己的 Embedded builder 就这么写)",
                block("type", "text", "text", "")});
        rows.add(new Object[]{"cb_extra_unknown_field", "多带一个规范里没有的字段",
                block("type", "text", "text", "x", "banana", 1)});
        rows.add(new Object[]{"cb_resource_inner_both_text_and_blob", "里层同时带 text 与 blob",
                block("type", "resource", "resource",
                        block("uri", "z-mcp://y", "text", "x", "blob", "AAAA"))});

        List<String> refused = new ArrayList<String>();
        int i = 0;
        for (Object[] row : rows) {
            String name = "clean_" + (++i);
            List<Map<String, Object>> messages = secondSlot(composedContent(row[2]));
            registerComposedPrompt(name, messages);
            Map<String, Object> w = wire(handler.handle(call("prompts/get",
                    "{\"name\":\"" + name + "\",\"arguments\":{}}", "7" + i), liveSession()));
            if (w.get("error") != null) {
                refused.add(row[0] + "| (" + row[1] + ") 被拒了, 而官方两条腿都放行这一格: "
                        + w.get("error"));
                continue;
            }
            List<Map<String, Object>> wireMessages =
                    (List<Map<String, Object>>) ((Map<String, Object>) w.get("result")).get("messages");
            if (!messages.equals(wireMessages)) {
                refused.add(row[0] + "| 过了闸但字节被改: " + wireMessages);
            }
        }
        assertEquals("闸门比客户端严的那些格: " + refused, 0, refused.size());
    }

    /** 拼一个 content 块: 成对写; 不传那一对了就是"整槽缺失", 传 null 就是"槽在而值为 null". */
    private static Map<String, Object> block(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static final List<Map<String, Object>> SENTINEL_NULL_LIST =
            new ArrayList<Map<String, Object>>();

    private void registerComposedPrompt(String name, final List<Map<String, Object>> messages) {
        registry.registerPrompt(new com.zifang.z.mcp.api.dto.McpPromptDto(name, "d",
                java.util.Collections.<com.zifang.z.mcp.api.dto.McpPromptDto.Arg>emptyList(),
                new com.zifang.z.mcp.api.dto.McpPromptDto.Composer() {
                    @Override public List<Map<String, Object>> compose(Map<String, Object> arguments) {
                        return messages;
                    }
                }));
    }

    /** 宿主 composer 的真实形状: 第一条合规, 第二条是被测的那条. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> secondSlot(Object bad) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(com.zifang.z.mcp.api.dto.McpPromptDto.message("user", ContentBlock.text("neighbour")));
        messages.add((Map<String, Object>) bad);
        return messages;
    }

    private Map<String, Object> composedMsg(String role) {
        return composedContentWith(role, com.zifang.z.mcp.api.dto.McpPromptDto
                .message("user", ContentBlock.text("candidate")).get("content"));
    }

    private Map<String, Object> composedContent(Object content) {
        return composedContentWith("user", content);
    }

    private Map<String, Object> composedContentWith(String role, Object content) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("role", role);
        m.put("content", content);
        return m;
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
