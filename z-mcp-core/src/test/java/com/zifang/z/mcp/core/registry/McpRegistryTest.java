package com.zifang.z.mcp.core.registry;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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
        r.registerServerTool("github", "create_issue", "create GH issue", "{}", args -> "ok");
        assertEquals(1, r.toolCount());
        assertEquals("github", r.listTools().get(0).getServerName());
        assertEquals(0, r.listBuiltinTools().size());
    }

    @Test
    public void call_routes_to_executor() {
        McpRegistry r = new McpRegistry();
        r.registerBuiltin("echo", "echo", "{}", args -> args.get("text"));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("text", "hello");
        assertEquals("hello", r.call("echo", args));
    }

    @Test
    public void call_unknown_tool_throws() {
        McpRegistry r = new McpRegistry();
        try {
            r.call("missing", Collections.emptyMap());
            assertTrue("expected exception", false);
        } catch (com.zifang.z.mcp.api.exception.McpException e) {
            assertEquals(-32001, e.getCode());
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
}