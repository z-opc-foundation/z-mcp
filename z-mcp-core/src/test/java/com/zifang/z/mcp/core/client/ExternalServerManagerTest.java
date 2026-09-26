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

    /** 同 {@link #handshake}, 但对面的 {@code serverInfo.name} 由调用方给 —— 自指判据就落在这一个字段上. */
    private static void handshakeAs(FakeExchange e, String sid, String advertisedName) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (sid != null) h.put("Mcp-Session-Id", sid);
        e.enqueue(new JsonRpcExchange.Response(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"serverInfo\":{\"name\":\"" + advertisedName + "\",\"version\":\"1\"}}}", h));
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

    /**
     * 自指的上游: hub 指向"另一台 hub", 而那台其实就是自己(或自己的复制品)时, 对岸的目录里
     * 装的是我这台目录的副本 —— 每同步一轮就往目录里加一代, 实测十分钟从 150 涨到 228.
     *
     * <p>判据取握手回来的 {@code serverInfo.name}: 它比 endpoint 可靠(hub 换端口指向自己、
     * 或两台同配置的复制品互为上游, 都是同一个名). 负向断言带阳性对照: 名字不同的那台照常聚合,
     * 这样"一个自指上游"不会顺带把整张目录清空.
     */
    @Test
    public void an_upstream_that_advertises_this_servers_own_name_is_refused() throws Exception {
        Fixture f = new Fixture();
        f.properties.setServerName("z-mcp");
        f.server("twin");
        FakeExchange twinUp = f.upstream("twin");
        handshakeAs(twinUp, "s1", "z-mcp");
        //  这条目录就是猎物: 没有闸时它会被整份抄进目录. 不给这一页的话"没聚合"会因为
        //  "脚本答完"而成立, 那条负向断言就是空跑的.
        toolsPage(twinUp, "[{\"name\":\"echo\"},{\"name\":\"find_tools\"}]");
        f.server("acme");
        FakeExchange acme = f.upstream("acme");
        handshakeAs(acme, "s2", "acme-hub");
        toolsPage(acme, "[{\"name\":\"lookup\"}]");

        f.manager.syncAll();

        Map<String, Object> twin = state(f, "twin");
        Map<String, Object> other = state(f, "acme");
        assertEquals("自指的那台一条工具都不许进目录", 0, num(twin, "toolCount"));
        assertEquals("只有 acme 的 1 条留在目录里", 1, f.registry.toolCount());
        assertEquals(1, num(other, "toolCount"));
        assertTrue("拒绝要在 lastError 里说清是谁自指, 不能静悄悄",
                String.valueOf(twin.get("lastError")).contains("identifies itself as this server"));
        assertEquals("自指的那台算 failed", 1, num(f.manager.health(), "failed"));
    }

    /**
     * 健康检查每轮都会把每条上游的目录重拉一遍 —— 上游没变时这一趟**不该**惊动任何会话.
     *
     * <p>这一条是 250 上量出来的: 一条什么都不做的流在 80 秒里收到 24 帧
     * {@code notifications/tools/list_changed}(每轮 8 帧 = 两条上游 ×(摘 2 + 登 2) 次 fire),
     * 而按协议收到该通知的客户端会立刻重取 {@code tools/list} —— 服务端在自己制造负载.
     */
    @Test
    public void an_unchanged_upstream_directory_does_not_re_announce_itself() throws Exception {
        Fixture f = new Fixture();
        final int[] fires = new int[1];
        f.registry.addChangeListener(McpRegistry.Change.TOOLS, new Runnable() {
            @Override public void run() { fires[0]++; }
        });
        f.server("acme");
        FakeExchange acme = f.upstream("acme");
        handshake(acme, "s1");
        String page = "[{\"name\":\"lookup\"},{\"name\":\"fetch\"}]";
        toolsPage(acme, page);
        toolsPage(acme, page);

        f.manager.syncAll();
        int afterFirst = fires[0];
        assertTrue("第一轮必须真有变更可播, 否则下面的负向断言是空跑的: fires=" + afterFirst,
                afterFirst > 0);
        assertEquals(2, f.registry.toolCount());

        f.manager.syncAll();
        assertEquals("目录逐字未变, 不该多播一次", afterFirst, fires[0]);
        assertEquals(2, f.registry.toolCount());

        toolsPage(acme, "[{\"name\":\"lookup\"},{\"name\":\"fetch\"},{\"name\":\"extra\"}]");
        f.manager.syncAll();
        assertEquals("真加了工具就要进目录", 3, f.registry.toolCount());
        assertTrue("而且必须播", fires[0] > afterFirst);
    }

    private static Map<String, Object> state(Fixture f, String name) {
        for (Map<String, Object> s : f.manager.state()) {
            if (name.equals(s.get("name"))) return s;
        }
        fail("状态里没有 " + name + ": " + f.manager.state());
        return null;
    }

    /**
     * 两台**不同名**的 hub 互为上游时, 目录必须停在第一轮的大小.
     *
     * <p>上面那条自指判据只认得"对岸报的名就是我"; 而 hub-a 指 hub-b、hub-b 指 hub-a 这种形状里
     * 两边都看不见自己: b 把 a 的工具抄过去、撞上 b 自己的同名工具就冠上 {@code a__} 前缀,
     * a 下一轮再把那批复制品当新工具收下 —— 每同步一轮多一代前缀名.
     *
     * <p>判据换成分布式的: 每一跳在 {@code tools/list} 广告时把自己的 server-name 追加到
     * {@code _meta["z-mcp/origins"]} 链尾, 所以"被我抄出去又绕回来"的那条, 链上一定带着我.
     * 阳性对照是同页里对岸自己的那条 —— 不能为了断环把正常工具也拒了.
     */
    @Test
    public void tools_the_upstream_copied_from_us_are_not_copied_back() throws Exception {
        Fixture f = new Fixture();
        f.properties.setServerName("hub-a");
        f.server("mirror");
        FakeExchange mirror = f.upstream("mirror");
        handshakeAs(mirror, "s1", "hub-b");
        String page = "[{"
                + "\"name\":\"b_own\",\"description\":\"对岸自己的工具\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"_meta\":{\"z-mcp/server\":\"a\",\"z-mcp/origins\":\"hub-b\"}"
                + "},{"
                + "\"name\":\"hub-a__echo\",\"description\":\"我的 echo 被抄过去又抄回来\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"_meta\":{\"z-mcp/server\":\"a\",\"z-mcp/origins\":\"hub-a,hub-b\"}"
                + "}]";
        toolsPage(mirror, page);

        f.manager.syncAll();

        assertTrue("对岸自己的工具照常进目录(阳性对照)", f.registry.hasTool("b_own"));
        assertFalse("链上有自己的那条不许再收", f.registry.hasTool("hub-a__echo"));
        assertEquals(1, f.registry.toolCount());
        assertEquals("该 server 的计数按收进来的算", 1, num(state(f, "mirror"), "toolCount"));
        assertNull("拒收成环的复制品不是故障, 不该记成 lastError",
                state(f, "mirror").get("lastError"));
        assertEquals("存下来的链不含本机(广告时才追加自己)",
                java.util.Collections.singletonList("hub-b"), f.registry.lookup("b_own").origins);

        toolsPage(f.exchange("mirror"), page);
        f.manager.syncAll();
        assertEquals("下一轮同样的东西再来, 目录不许长", 1, f.registry.toolCount());
    }

    /**
     * 链只对**会带 {@code _meta} 的上游**成立. 第三方 hub 会把我们的 {@code _meta} 原样丢掉,
     * 于是它抄回来的那条没有链 —— 这一条钉住"没有链就照常收", 免得把判据写成"没链的一律拒",
     * 那会让所有第三方上游的工具全部消失.
     */
    @Test
    public void an_upstream_that_sends_no_provenance_chain_is_still_aggregated() throws Exception {
        Fixture f = new Fixture();
        f.properties.setServerName("hub-a");
        f.server("foreign");
        publish(f, "foreign", "[{\"name\":\"their_tool\",\"description\":\"没有 _meta\"}]");
        f.manager.syncAll();
        assertTrue(f.registry.hasTool("their_tool"));
        assertNull("不带链的条目存下来就是没有链", f.registry.lookup("their_tool").origins);
        assertEquals(1, f.registry.toolCount());
    }
}
