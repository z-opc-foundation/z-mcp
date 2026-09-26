package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
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
 * {@link ExternalServerManager} —— 让 {@code z.mcp.servers[]} 与
 * {@code healthCheckIntervalSeconds} 这两项 0.1.x 的"死配置"真的产生行为.
 *
 * <p>钉三件事: 聚合(撞名前缀而不是覆盖)、摘除(连不上就不能继续广告工具)、
 * 隔离(一个 server 死了不拖累别的 server 与内置工具).
 */
public class ExternalServerManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class FakeExchange implements JsonRpcExchange {
        final List<String> bodies = new ArrayList<String>();
        final Deque<JsonRpcExchange.Response> script =
                new ArrayDeque<JsonRpcExchange.Response>();
        boolean unreachable;
        boolean closed;
        boolean alive = true;

        void enqueue(JsonRpcExchange.Response r) { script.add(r); }

        @Override public JsonRpcExchange.Response post(String body, Map<String, String> header)
                throws IOException {
            if (unreachable) throw new IOException("connection refused");
            bodies.add(body);
            JsonRpcExchange.Response r = script.poll();
            if (r == null) throw new IOException("no scripted answer for: " + body);
            return r;
        }

        @Override public void close() { closed = true; }

        /** stdio 的覆写就是这个语义: 关掉、或对面进程没了, 都不再是活的传输. */
        @Override public boolean isAlive() { return alive && !closed; }
    }

    private final class Fixture {
        final McpRegistry registry = new McpRegistry();
        final McpProperties properties = new McpProperties();
        final Map<String, FakeExchange> pre = new LinkedHashMap<String, FakeExchange>();
        final Map<String, FakeExchange> made = new LinkedHashMap<String, FakeExchange>();
        int createCalls;
        final ExternalServerManager manager;

        Fixture() {
            properties.setHealthCheckIntervalSeconds(0);   // 测试里不派后台线程
            final Map<String, FakeExchange> pre = this.pre;
            final Map<String, FakeExchange> made = this.made;
            final int[] creates = new int[1];
            ExternalServerManager.ExchangeFactory factory =
                    new ExternalServerManager.ExchangeFactory() {
                        @Override public JsonRpcExchange create(McpProperties.ServerConfig config) {
                            creates[0]++;
                            createCalls = creates[0];
                            FakeExchange e = pre.remove(config.getName());
                            if (e == null) e = new FakeExchange();
                            made.put(config.getName(), e);
                            return e;
                        }
                    };
            manager = new ExternalServerManager(registry, properties, mapper, factory);
        }

        McpProperties.ServerConfig server(String name) {
            McpProperties.ServerConfig c = new McpProperties.ServerConfig();
            c.setName(name);
            c.setTransport("http");
            c.setEndpoint("http://127.0.0.1:1/mcp");
            properties.getServers().add(c);
            return c;
        }

        /** 预先放一条传输, 好把脚本灌进去. */
        FakeExchange upstream(String name) {
            FakeExchange e = new FakeExchange();
            pre.put(name, e);
            return e;
        }

        /** 一个"连不上"的 server: 既进配置, 也预置一条会抛异常的传输. */
        FakeExchange dead(String name) {
            server(name);
            FakeExchange e = upstream(name);
            e.unreachable = true;
            return e;
        }

        FakeExchange exchange(String name) { return made.get(name); }
    }

    private static void handshake(FakeExchange e, String sid) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (sid != null) h.put("Mcp-Session-Id", sid);
        e.enqueue(new JsonRpcExchange.Response(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"serverInfo\":{\"name\":\"up\",\"version\":\"1\"}}}", h));
        e.enqueue(new JsonRpcExchange.Response(202, null, "", h));
    }

    private static void toolsPage(FakeExchange e, String toolsJson) {
        e.enqueue(new JsonRpcExchange.Response(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":" + toolsJson
                        + ",\"nextCursor\":\"\"}}", null));
    }

    private static void callResult(FakeExchange e, String text) {
        e.enqueue(new JsonRpcExchange.Response(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                        + text + "\"}]}}", null));
    }

    /** 一次成功的 sync: 握手 + 一页目录. */
    private static void publish(Fixture f, String server, String toolsJson) {
        FakeExchange up = f.upstream(server);
        handshake(up, "s1");
        toolsPage(up, toolsJson);
    }

    private static String firstText(Object res) {
        List<?> blocks = (List<?>) ((CallToolResult) res).toWire().get("content");
        assertFalse("结果必须至少有一个内容块", blocks.isEmpty());
        return String.valueOf(((Map<?, ?>) blocks.get(0)).get("text"));
    }

    private static int num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).intValue();
    }

    // ------------------------------------------------------------------ 聚合

    @Test
    public void configured_servers_are_aggregated_into_the_registry() throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\",\"description\":\"回声\","
                + "\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"lookup\"}]");

        f.manager.syncAll();

        assertTrue(f.registry.hasTool("echo"));
        assertTrue(f.registry.hasTool("lookup"));
        assertEquals("acme", f.registry.lookup("echo").serverName);
        assertEquals("回声", f.registry.lookup("echo").description);
        assertEquals("{\"type\":\"object\"}", f.registry.lookup("echo").schemaJson);
        assertEquals(1, f.registry.serverCount());
        assertEquals("2025-06-18", f.manager.state().get(0).get("protocolVersion"));
        assertEquals(0, num(f.manager.health(), "failed"));
        assertEquals(2, num(f.manager.state().get(0), "toolCount"));
    }

    @Test
    public void a_stdio_server_is_advertised_by_its_command_line_not_a_null_endpoint() throws Exception {
        Fixture f = new Fixture();
        McpProperties.ServerConfig c = f.server("local");
        c.setTransport("stdio");
        c.setEndpoint(null);                       // stdio 本来就没有 URL
        c.setCommand("/usr/bin/java");
        c.setArgs(java.util.Arrays.asList("-cp", "fixtures.jar", "com.acme.Fixture"));
        publish(f, "local", "[{\"name\":\"get_time\"}]");

        f.manager.syncAll();

        String want = "/usr/bin/java -cp fixtures.jar com.acme.Fixture";
        assertEquals(want, f.manager.state().get(0).get("endpoint"));
        assertEquals("registry 里的那份也要能看出是哪个子进程",
                want, f.registry.listServers().get(0).getEndpoint());

        // http 那条路不能因为这段显示逻辑被改动
        Fixture http = new Fixture();
        http.server("acme");
        publish(http, "acme", "[{\"name\":\"ok_tool\"}]");
        http.manager.syncAll();
        assertEquals("http://127.0.0.1:1/mcp", http.manager.state().get(0).get("endpoint"));
    }

    @Test
    public void a_tool_name_colliding_with_a_builtin_is_namespaced_not_overwritten() throws Exception {
        Fixture f = new Fixture();
        f.registry.registerBuiltin("get_time", "本机时间", "{\"type\":\"object\"}",
                new McpRegistry.ToolExecutor() {
                    @Override public Object execute(Map<String, Object> a) {
                        return CallToolResult.text("local-clock");
                    }
                });
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"get_time\",\"description\":\"上游时间\"}]");

        f.manager.syncAll();

        assertEquals("local-clock", firstText(f.registry.call("get_time", null)));
        assertTrue(f.registry.hasTool("acme__get_time"));
        assertEquals("acme", f.registry.lookup("acme__get_time").serverName);
        assertEquals("两边都要能被模型看见", 2, f.registry.toolNames().size());
    }

    @Test
    public void calling_an_aggregated_tool_forwards_to_the_upstream() throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\"}]");
        f.manager.syncAll();
        FakeExchange up = f.exchange("acme");
        up.bodies.clear();
        callResult(up, "from-upstream");

        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("text", "hello");
        Object res = f.registry.call("echo", args);

        assertEquals("from-upstream", firstText(res));
        assertEquals(1, up.bodies.size());
        String body = up.bodies.get(0);
        assertTrue(body, body.contains("\"method\":\"tools/call\""));
        assertTrue(body, body.contains("\"name\":\"echo\""));
        assertTrue(body, body.contains("\"text\":\"hello\""));
    }

    @Test
    public void an_upstream_transport_failure_becomes_an_error_result_the_model_can_see()
            throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\"}]");
        f.manager.syncAll();
        f.exchange("acme").unreachable = true;

        CallToolResult res = (CallToolResult) f.registry.call("echo", null);

        assertTrue("上游挂了必须是 isError, 不能抛给协议层", res.isError());
        assertTrue(firstText(res), firstText(res).contains("could not run echo"));
    }

    @Test
    public void resync_replaces_the_previous_catalogue_instead_of_accumulating_it() throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"old_tool\"}]");
        f.manager.syncAll();
        assertTrue(f.registry.hasTool("old_tool"));

        FakeExchange up = f.exchange("acme");
        up.bodies.clear();
        toolsPage(up, "[{\"name\":\"new_tool\"}]");
        f.manager.syncAll();

        assertFalse(f.registry.hasTool("old_tool"));
        assertTrue(f.registry.hasTool("new_tool"));
        assertEquals(1, f.registry.toolNames().size());
        // 会话还在就不该重新握手: 每轮健康检查都 initialize 一遍会把上游耗死
        assertEquals(1, up.bodies.size());
        assertFalse(up.bodies.get(0), up.bodies.get(0).contains("initialize"));
    }

    // ------------------------------------------------------------------ 摘除

    @Test
    public void an_unreachable_server_withdraws_its_tools_instead_of_lying_about_them()
            throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\"}]");
        f.manager.syncAll();
        assertTrue(f.registry.hasTool("echo"));

        f.exchange("acme").unreachable = true;
        f.manager.syncAll();

        assertFalse("目录拉不到就继续广告 = 客户端只会拿到一次失败", f.registry.hasTool("echo"));
        assertEquals(0, f.registry.serverCount());
        Map<String, Object> state = f.manager.state().get(0);
        assertEquals(0, num(state, "toolCount"));
        assertTrue(String.valueOf(state.get("lastError")).contains("IOException"));
        assertEquals(1, num(f.manager.health(), "failed"));
        assertEquals(0, num(f.manager.health(), "connected"));
    }

    /** 握手都成功了, 之后子进程自己退了 —— flag 仍然亮着, 但这条链路永远不会再回答. */
    @Test
    public void a_transport_that_dies_after_the_handshake_stops_being_reported_as_connected()
            throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\"}]");
        f.manager.syncAll();
        assertEquals(0, num(f.manager.health(), "failed"));

        f.exchange("acme").alive = false;

        assertEquals("传输都死了还在 admin 里报 connected=true, 等于让运维查一个不存在的现场",
                Boolean.FALSE, f.manager.state().get(0).get("connected"));
        assertEquals(1, num(f.manager.health(), "failed"));

        // 对着已死的管道重连不可能成功: 这一轮把它摘掉, 下一轮才换一条新传输
        f.manager.syncAll();
        FakeExchange back = f.upstream("acme");
        handshake(back, "s3");
        toolsPage(back, "[{\"name\":\"echo\"}]");
        f.manager.syncAll();

        assertEquals("已死的传输不能被继续复用", 2, f.createCalls);
        assertTrue(f.registry.hasTool("echo"));
        assertEquals(0, num(f.manager.health(), "failed"));
    }

    @Test
    public void a_server_that_comes_back_gets_its_catalogue_republished() throws Exception {
        Fixture f = new Fixture();
        FakeExchange deadUp = f.dead("acme");
        f.manager.syncAll();
        assertFalse(f.registry.hasTool("echo"));

        // 恢复必须换一条新传输: 复用已关掉的那条 = 这个 server 永远回不来
        FakeExchange back = f.upstream("acme");
        handshake(back, "s2");
        toolsPage(back, "[{\"name\":\"echo\"}]");
        f.manager.syncAll();

        assertTrue(f.registry.hasTool("echo"));
        assertTrue("旧传输要被关掉, 不然 stdio 的子进程会留在机器上", deadUp.closed);
        assertEquals(2, f.createCalls);
        assertEquals(0, num(f.manager.health(), "failed"));
    }

    @Test
    public void one_dead_server_does_not_take_down_another_server_or_a_builtin() throws Exception {
        Fixture f = new Fixture();
        f.registry.registerBuiltin("ping", "ping", "{\"type\":\"object\"}",
                new McpRegistry.ToolExecutor() {
                    @Override public Object execute(Map<String, Object> a) {
                        return CallToolResult.text("pong");
                    }
                });
        f.server("alive");
        publish(f, "alive", "[{\"name\":\"ok_tool\"}]");
        f.dead("dead");

        f.manager.syncAll();

        assertTrue(f.registry.hasTool("ok_tool"));
        assertTrue(f.registry.hasTool("ping"));
        assertEquals(2, num(f.manager.health(), "configured"));
        assertEquals(1, num(f.manager.health(), "connected"));
        assertEquals(1, num(f.manager.health(), "failed"));
    }

    @Test
    public void a_disabled_server_publishes_nothing_and_is_not_even_dialed() throws Exception {
        Fixture f = new Fixture();
        McpProperties.ServerConfig c = f.server("acme");
        c.setEnabled(false);

        f.manager.syncAll();

        assertEquals(0, f.createCalls);
        assertEquals(0, f.registry.toolCount());
        assertEquals("disabled", f.manager.state().get(0).get("lastError"));
    }

    @Test
    public void an_unnamed_server_entry_is_skipped_without_killing_the_sweep() throws Exception {
        Fixture f = new Fixture();
        f.properties.getServers().add(new McpProperties.ServerConfig());   // 没有 name
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\"}]");

        f.manager.syncAll();

        assertTrue(f.registry.hasTool("echo"));
        assertEquals(1, f.manager.state().size());
    }

    @Test
    public void destroy_closes_every_transport_and_empties_the_catalogue() throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\"}]");
        f.manager.syncAll();

        f.manager.destroy();

        assertTrue(f.exchange("acme").closed);
        assertEquals(0, f.registry.toolCount());
        assertEquals(0, num(f.manager.health(), "connected"));
        assertNull(f.registry.lookup("echo"));
    }

    // ------------------------------------------------------------------ 传输选择

    @Test
    public void the_deprecated_http_sse_transport_is_refused_with_a_reason() throws Exception {
        ExternalServerManager.ExchangeFactory factory =
                ExternalServerManager.defaultFactory(new McpProperties(), mapper);
        McpProperties.ServerConfig c = new McpProperties.ServerConfig();
        c.setName("legacy");
        c.setTransport("http+sse");
        c.setEndpoint("http://127.0.0.1:1/sse");
        try {
            factory.create(c);
            fail("已废弃的传输必须显式拒绝: 静默忽略配置是更难查的 bug");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("deprecated"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("http+sse"));
        }
    }

    @Test
    public void an_http_transport_without_an_endpoint_is_refused() throws Exception {
        ExternalServerManager.ExchangeFactory factory =
                ExternalServerManager.defaultFactory(new McpProperties(), mapper);
        McpProperties.ServerConfig c = new McpProperties.ServerConfig();
        c.setName("nohost");
        c.setTransport("http");
        try {
            factory.create(c);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no endpoint"));
        }
    }

    @Test
    public void streamable_http_aliases_are_all_accepted() throws Exception {
        ExternalServerManager.ExchangeFactory factory =
                ExternalServerManager.defaultFactory(new McpProperties(), mapper);
        for (String transport : new String[]{"http", "streamable-http", "HTTP", "http+json"}) {
            McpProperties.ServerConfig c = new McpProperties.ServerConfig();
            c.setName("t");
            c.setTransport(transport);
            c.setEndpoint("http://127.0.0.1:1/mcp");
            JsonRpcExchange exchange = factory.create(c);
            assertTrue(transport, exchange instanceof HttpJsonRpcExchange);
            exchange.close();
        }
    }
}
