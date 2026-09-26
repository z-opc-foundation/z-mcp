package com.zifang.z.mcp.core.registry;

import com.zifang.z.mcp.api.dto.McpPromptDto;
import com.zifang.z.mcp.api.dto.McpResourceDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.exception.McpException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class McpRegistryTest {

    @Test
    public void registerBuiltin_and_list() {
        McpRegistry r = new McpRegistry();
        r.registerBuiltin("echo", "echo", "{}", args -> args.get("text"));
        assertEquals(1, r.toolCount());
        assertEquals(0, r.serverCount());
        assertTrue(r.hasTool("echo"));
        assertFalse(r.hasTool("nope"));
        assertEquals(1, r.listBuiltinTools().size());
    }

    @Test
    public void registerServerTool_listedWithServerName() {
        McpRegistry r = new McpRegistry();
        String effective = r.registerServerTool("github", "create_issue", "create GH issue", "{}", args -> "ok");
        assertEquals("create_issue", effective);
        assertEquals(1, r.toolCount());
        assertEquals("github", r.listTools().get(0).getServerName());
        assertEquals(0, r.listBuiltinTools().size());
    }

    @Test
    public void call_routes_to_executor() throws Exception {
        McpRegistry r = new McpRegistry();
        r.registerBuiltin("echo", "echo", "{}", args -> args.get("text"));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("text", "hello");
        assertEquals("hello", r.call("echo", args));
    }

    @Test
    public void call_unknown_tool_throws_invalid_params() throws Exception {
        McpRegistry r = new McpRegistry();
        try {
            r.call("missing", Collections.emptyMap());
            fail("expected McpException");
        } catch (McpException e) {
            // 0.1.x 用的 -32001 不属于任何 MCP 修订版
            assertEquals(-32602, e.getCode());
        }
    }

    @Test
    public void unregister_removes_tool() {
        McpRegistry r = new McpRegistry();
        r.registerBuiltin("echo", "echo", "{}", args -> "x");
        r.unregister("echo");
        assertEquals(0, r.toolCount());
        assertNotNull(r.listTools());
    }

    // ------------------------------------------------ 0.2.0: 覆盖 / 命名空间

    @Test
    public void duplicate_registration_never_silently_overwrites() {
        McpRegistry r = new McpRegistry();
        r.registerBuiltin("echo", "first", "{}", args -> "1");
        try {
            r.registerBuiltin("echo", "second", "{}", args -> "2");
            fail("重复注册内置工具必须失败, 不能悄悄换掉执行器");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("duplicate tool registration"));
        }
        assertEquals("first", r.listTools().get(0).getDescription());
    }

    @Test
    public void server_tool_colliding_with_builtin_gets_namespaced() throws Exception {
        McpRegistry r = new McpRegistry();
        r.registerBuiltin("search", "builtin search", "{}", args -> "internal");
        String effective = r.registerServerTool("brave", "search", "external search", "{}", args -> "web");

        assertEquals("brave__search", effective);
        assertEquals(2, r.toolCount());
        // 类注释承诺的"名字冲突时 builtin 优先"现在是真的: 裸名仍指向内置实现
        assertEquals("internal", r.call("search", Collections.emptyMap()));
        assertEquals("web", r.call(effective, Collections.emptyMap()));
    }

    @Test
    public void server_tool_colliding_with_another_server_also_namespaces() throws Exception {
        McpRegistry r = new McpRegistry();
        r.registerServerTool("github", "list_issues", "gh", "{}", args -> "gh");
        String effective = r.registerServerTool("gitlab", "list_issues", "gl", "{}", args -> "gl");
        assertEquals("gitlab__list_issues", effective);
        assertEquals("gh", r.call("list_issues", Collections.emptyMap()));
        assertEquals("gl", r.call(effective, Collections.emptyMap()));
    }

    // ------------------------------------------------------ 0.2.0: 名字合法性

    @Test
    public void illegal_tool_names_are_rejected() {
        McpRegistry r = new McpRegistry();
        String[] bad = {"has space", "带中文", "", "emoji-\uD83D\uDE80", repeat('x', 129)};
        for (String name : bad) {
            try {
                r.registerBuiltin(name, "d", "{}", args -> "x");
                fail("应拒绝工具名: [" + name + "]");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("invalid tool name")
                        || e.getMessage().contains("tool name required"));
            }
        }
        assertEquals(0, r.toolCount());
    }

    @Test
    public void legal_tool_names_are_accepted() {
        McpRegistry r = new McpRegistry();
        String[] good = {"a", "get_time", "x.y-z", "A0_9.-", repeat('n', 128)};
        for (String name : good) r.registerBuiltin(name, "d", "{}", args -> "x");
        assertEquals(good.length, r.toolCount());
    }

    // ------------------------------------------------------ 0.2.0: 枚举稳定性

    @Test
    public void listTools_is_sorted_and_stable() {
        McpRegistry r = new McpRegistry();
        String[] names = {"zeta", "alpha", "mid", "beta"};
        for (String n : names) r.registerBuiltin(n, "d", "{}", args -> "x");
        List<String> out = r.toolNames();
        assertEquals(java.util.Arrays.asList("alpha", "beta", "mid", "zeta"), out);

        List<String> again = new ArrayList<String>();
        for (McpToolDto d : r.listTools()) again.add(d.getName());
        assertEquals("listTools 顺序必须与 toolNames 一致(分页依赖它)", out, again);
    }

    @Test
    public void pagination_walks_the_whole_set_without_gap_or_overlap() {
        McpRegistry r = new McpRegistry();
        for (int i = 0; i < 25; i++) {
            r.registerBuiltin(String.format("t%02d", Integer.valueOf(i)), "d", "{}", args -> "x");
        }
        List<String> seen = new ArrayList<String>();
        String cursor = null;
        do {
            McpRegistry.Page<McpToolDto> page = r.pageTools(cursor, 10);
            for (McpToolDto d : page.getItems()) seen.add(d.getName());
            cursor = page.getNextCursor();
        } while (cursor != null);
        assertEquals(25, seen.size());
        assertEquals("无重复", 25, new java.util.HashSet<String>(seen).size());
        assertEquals("字典序", sortedCopy(seen), seen);
    }

    @Test
    public void invalid_cursor_is_reported() {
        McpRegistry r = new McpRegistry();
        r.registerBuiltin("a", "d", "{}", args -> "x");
        try {
            r.pageTools("ghost", 1);
            fail("未知 cursor 必须报错");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("invalid cursor"));
        }
    }

    // ------------------------------------------------------ 0.2.0: 变更通知

    @Test
    public void change_listener_fires_on_register_and_unregister() {
        McpRegistry r = new McpRegistry();
        final AtomicInteger hits = new AtomicInteger();
        r.addChangeListener(McpRegistry.Change.TOOLS, () -> hits.incrementAndGet());
        r.registerBuiltin("a", "d", "{}", args -> "x");
        assertEquals(1, hits.get());
        r.registerServerTool("s", "b", "d", "{}", args -> "x");
        assertEquals(2, hits.get());
        r.unregister("a");
        assertEquals(3, hits.get());
        r.unregister("nothing-here");
        assertEquals("移除不存在的工具不该触发通知", 3, hits.get());
    }

    /**
     * 三类目录各有自己的 list_changed 通知, 所以变更不能混着播报:
     * 注册一个 prompt 却打到 TOOLS 监听器上, 线上表现是每个在线客户端收到
     * notifications/tools/list_changed 后去重刷一份根本没动的工具表.
     */
    @Test
    public void each_category_notifies_only_its_own_listeners() {
        McpRegistry r = new McpRegistry();
        final AtomicInteger tools = new AtomicInteger();
        final AtomicInteger resources = new AtomicInteger();
        final AtomicInteger prompts = new AtomicInteger();
        r.addChangeListener(McpRegistry.Change.TOOLS, () -> tools.incrementAndGet());
        r.addChangeListener(McpRegistry.Change.RESOURCES, () -> resources.incrementAndGet());
        r.addChangeListener(McpRegistry.Change.PROMPTS, () -> prompts.incrementAndGet());

        r.registerBuiltin("a", "d", "{}", args -> "x");
        assertEquals("只动 tools", 1, tools.get());
        assertEquals(0, resources.get());
        assertEquals(0, prompts.get());

        r.registerResource(new McpResourceDto("z-mcp://x", "x", "d", "text/plain",
                args -> com.zifang.z.mcp.api.dto.ContentBlock.text("hi")));
        assertEquals("只动 resources", 1, resources.get());
        assertEquals(1, tools.get());
        assertEquals(0, prompts.get());

        r.registerPrompt(new McpPromptDto("p", "d", null,
                args -> Collections.<Map<String, Object>>emptyList()));
        assertEquals("只动 prompts", 1, prompts.get());
        assertEquals(1, tools.get());
        assertEquals(1, resources.get());

        r.unregisterResource("z-mcp://x");
        assertEquals(2, resources.get());
        assertEquals("摘除资源也不该惊动 tools", 1, tools.get());
    }

    @Test
    public void broken_listener_cannot_break_registration() {
        McpRegistry r = new McpRegistry();
        r.addChangeListener(McpRegistry.Change.TOOLS, () -> { throw new RuntimeException("sink down"); });
        r.registerBuiltin("a", "d", "{}", args -> "x");
        assertEquals(1, r.toolCount());
    }

    // ------------------------------------------------------------ DTO 兜底

    @Test
    public void input_schema_falls_back_to_object_not_empty_or_null() {
        McpToolDto blank = new McpToolDto("n", "d", null, "  ");
        assertEquals("{\"type\":\"object\",\"properties\":{}}", blank.getInputSchemaJson());
        McpToolDto nul = new McpToolDto("n", "d", null, null);
        assertTrue(nul.getInputSchemaJson().contains("\"type\":\"object\""));
        assertEquals("(builtin)", nul.getServerDisplayName());
        assertTrue(nul.isBuiltin());
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static List<String> sortedCopy(List<String> in) {
        List<String> out = new ArrayList<String>(in);
        Collections.sort(out);
        return out;
    }
}
