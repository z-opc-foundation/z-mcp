package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpSchema;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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

    private static class FakeExchange implements JsonRpcExchange {
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

        int listCalls() {
            int n = 0;
            for (String b : bodies) if (b.contains("\"tools/list\"")) n++;
            return n;
        }
    }

    /**
     * 能像 stdio 那样被上游反向推一条的传输 —— 走的正是 {@link JsonRpcExchange.PushCapable}
     * 这一个能力面, 所以"manager 有没有去装监听方"这件事在本用例里是**可见的**:
     * {@link #push} 在 listener 缺席时当场就红, 而不是静悄悄地什么也没发生.
     */
    private static final class NotifyExchange extends FakeExchange
            implements JsonRpcExchange.PushCapable {

        /** 每一次 post 落在哪条线程上(按对象身份记) —— 重同步跑错了线程由它现形. */
        final List<Thread> postThreads =
                java.util.Collections.synchronizedList(new ArrayList<Thread>());
        private JsonRpcExchange.NotificationListener listener;
        volatile CountDownLatch enteredResync;
        volatile CountDownLatch releaseResync;

        @Override public void onUpstreamNotification(JsonRpcExchange.NotificationListener l) {
            listener = l;
        }

        void push(String method) {
            assertNotNull("manager 没有把监听方装到这条可被推的传输上(注册点丢了?)", listener);
            listener.onNotification(method);
        }

        @Override public JsonRpcExchange.Response post(String body, Map<String, String> header)
                throws IOException {
            postThreads.add(Thread.currentThread());
            if (body.contains("\"tools/list\"") && enteredResync != null) {
                enteredResync.countDown();
                try {
                    // 上限是安全阀不是判据: 重同步若被写在推帧的那条线程上(变异体), 卡在这里的
                    // 就是测试线程自己, 只有超时能把它放出来 —— 所以这个数要小.
                    if (releaseResync != null) releaseResync.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.post(body, header);
        }
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

        /** 一条"上游能反向推帧进来"的传输 —— stdio 的那一类能力面. */
        NotifyExchange notifying(String name) {
            NotifyExchange e = new NotifyExchange();
            pre.put(name, e);
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

    /**
     * 上游目录里**只有被环检测拒收的那部分**变了, 收进来的那部分不许被重发布.
     *
     * <p>签名如果取在过滤**之前**, hub↔hub 环上对岸每聚合一轮都会把"抄回来的复制品"换一批代际,
     * 于是这一轮被当成"目录变了" —— 而"变了"的代价是先把收进来的那几条 {@code unregister} 再重新
     * {@code register}。中间那一下, 正在读 {@code tools/list} 的人看见自己这边的目录凭空少几条
     * (真进程尺 {@code HubAggregationTest.two_hubs_that_aggregate_each_other_reach_a_fixed_point}
     * 在本机 jdk17 全量跑到过这个形状: 12 条里只剩 10 条, 缺的正是 {@code origin__registry_state}
     * 与 {@code origin__system_info}), 每条会话还白收一对 {@code list_changed}。
     *
     * <p>"任何一次 fire 里都读不到空洞"这条守卫, 只对**被拒部分的抖动**成立(那本来就不该动目录);
     * 收进来的部分真变了要不要也做成无洞的原子替换, 是另一件事(见下面那条阳性对照里刻意没断言它)。
     */
    @Test
    public void churn_only_in_the_rejected_part_does_not_re_publish_the_accepted_part() throws Exception {
        final Fixture f = new Fixture();
        f.properties.setServerName("hub-a");
        final int[] fires = new int[1];
        final List<String> blind = new ArrayList<String>();
        f.registry.addChangeListener(McpRegistry.Change.TOOLS, new Runnable() {
            @Override public void run() {
                fires[0]++;
                if (!f.registry.hasTool("b_own")) blind.add(f.registry.toolNames().toString());
            }
        });
        f.server("mirror");
        FakeExchange mirror = f.upstream("mirror");
        handshakeAs(mirror, "s1", "hub-b");
        String keep = "{\"name\":\"b_own\",\"description\":\"对岸自己的工具\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"_meta\":{\"z-mcp/server\":\"a\",\"z-mcp/origins\":\"hub-b\"}}";
        String echoed = "{\"name\":\"hub-a__echo\",\"description\":\"抄回来的一条\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"_meta\":{\"z-mcp/server\":\"a\",\"z-mcp/origins\":\"hub-a,hub-b\"}}";
        toolsPage(mirror, "[" + keep + "," + echoed + "]");

        f.manager.syncAll();
        int afterFirst = fires[0];
        assertTrue("第一轮必须有东西可播, 否则下面的负向断言是空跑: fires=" + afterFirst, afterFirst > 0);
        assertTrue(f.registry.hasTool("b_own"));
        assertEquals(1, f.registry.toolCount());
        blind.clear();

        // 只有被拒的那部分变了(对岸又聚合一轮 ⇒ 复制品多了一代描述), 收进来的那条逐字未变
        toolsPage(mirror, "[" + keep + ","
                + "{\"name\":\"hub-a__echo\",\"description\":\"抄回来的第二条(换了措辞)\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"_meta\":{\"z-mcp/server\":\"a\",\"z-mcp/origins\":\"hub-a,hub-b\"}}" + "]");
        f.manager.syncAll();

        assertEquals("被拒的条目抖动不该惊动任何会话", afterFirst, fires[0]);
        assertEquals("也不该让目录出现哪怕一次空洞: " + blind, 0, blind.size());
        assertEquals(1, f.registry.toolCount());
        assertTrue(f.registry.hasTool("b_own"));

        // 阳性对照(反向不许过头): 收进来的那条真变了就要落地 —— 否则这条尺等于"永远不重发布"
        toolsPage(mirror, "["
                + "{\"name\":\"b_own\",\"description\":\"对岸改了描述\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"_meta\":{\"z-mcp/server\":\"a\",\"z-mcp/origins\":\"hub-b\"}}" + "]");
        f.manager.syncAll();
        assertTrue("收进来的部分变了必须播", fires[0] > afterFirst);
        assertEquals("对岸改了描述", "对岸改了描述", f.registry.lookup("b_own").description);
    }

    /**
     * 收进来的部分**真的**变了 ⇒ 目录必须**整份一次换到位**.
     *
     * <p>与上一条分得很开: 上一条量"被拒的部分抖动 ⇒ 一次 fire 都不许有", 这一条量"真有变更时,
     * 那几次 fire 里读得到什么". 判据是**整份目录等于同步结束后的那份** —— 不是"少了 0 条"这种
     * 数数, 因为逐条摘再逐条登的形状里, 中间那几拍读到的目录既可能少 acme 的两条、也可能只剩
     * 邻居的一条, 而每一次 fire 都在同时对所有会话说"目录变了, 来重取 {@code tools/list}" ——
     * 客户端按这句话来读, 读到的就是洞.
     *
     * <p>邻居那台(`other`)在这一轮里逐字未变, 它有两个用处: 让"读到的目录"不只有猎物一条可看,
     * 也让"这台没变就不该播"跟着一起被数进 fires 里(它一条都不许贡献).
     */
    @Test
    public void a_real_upstream_change_lands_as_one_whole_directory() throws Exception {
        final Fixture f = new Fixture();
        f.server("acme");
        f.server("other");
        FakeExchange acme = f.upstream("acme");
        FakeExchange other = f.upstream("other");
        String twoTools = "[{\"name\":\"a1\",\"description\":\"一\","
                + "\"inputSchema\":{\"type\":\"object\"}},"
                + "{\"name\":\"a2\",\"description\":\"二\",\"inputSchema\":{\"type\":\"object\"}}]";
        String oneTool = "[{\"name\":\"a1\",\"description\":\"一改了\","
                + "\"inputSchema\":{\"type\":\"object\"}}]";
        String neighbour = "[{\"name\":\"n1\",\"description\":\"邻居的一条\","
                + "\"inputSchema\":{\"type\":\"object\"}}]";
        handshake(acme, "s1");
        toolsPage(acme, twoTools);
        handshake(other, "s1");
        toolsPage(other, neighbour);

        final List<String> seenDuringFire = new ArrayList<String>();
        f.registry.addChangeListener(McpRegistry.Change.TOOLS, new Runnable() {
            @Override public void run() { seenDuringFire.add(f.registry.toolNames().toString()); }
        });
        f.manager.syncAll();
        assertEquals("首轮之后目录该有 3 条(a1/a2/n1): " + f.registry.toolNames(),
                3, f.registry.toolCount());
        assertTrue("首轮确实播过(否则下面的计数是在空跑上做的)", seenDuringFire.size() > 0);
        seenDuringFire.clear();

        // 真变更: acme 那一轮只剩一条, 且留下那条的描述改了; 邻居逐字不变
        toolsPage(acme, oneTool);
        toolsPage(other, neighbour);
        f.manager.syncAll();

        String finalDirectory = f.registry.toolNames().toString();
        assertEquals("一次真变更只该播一次 —— 每一帧都是一次全体会话重取 tools/list: "
                + seenDuringFire, 1, seenDuringFire.size());
        assertEquals("fire 那一刻读到的目录必须已经是换到位的那份(摘一条+登一条的中间态就是洞): "
                + seenDuringFire, finalDirectory, seenDuringFire.get(0));
        // 阳性对照: 上面两句不许在"目录根本没动"的前提下成立
        assertEquals("[a1, n1]", finalDirectory);
        assertEquals("一改了", "一改了", f.registry.lookup("a1").description);
        assertFalse("摘掉的那条必须真的不在了", f.registry.hasTool("a2"));
        assertTrue("邻居那条一轮都不许被惊动", f.registry.hasTool("n1"));
    }

    /** {@link ExternalServerManager} 起的那条周期线程叫这个. */
    private static final String HEALTH_THREAD = "z-mcp-server-health";

    /**
     * 当下活着的健康检查线程.
     *
     * <p>{@link Thread} 没有覆写 {@code equals}/{@code hashCode}, 所以放进 Set 就是按**对象身份**比 ——
     * 正是这里要的判据: 同 JVM 里别的上下文(或将来别的用例)留着的线程不该算到这台 manager 头上,
     * 而"这台 manager 又新起了一条"只能靠身份看出来, 数名字会误报.
     */
    private static Set<Thread> healthThreads() {
        Set<Thread> out = new HashSet<Thread>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (HEALTH_THREAD.equals(t.getName()) && t.isAlive()) out.add(t);
        }
        return out;
    }

    private static List<String> namesOf(Set<Thread> in) {
        List<String> out = new ArrayList<String>();
        for (Thread t : in) out.add(t.getName() + "#" + System.identityHashCode(t));
        return out;
    }

    /**
     * {@code destroy()} 是终点: 它之后一次**迟到**的同步也不许再把健康检查线程起回来.
     *
     * <p>CI 上那条红落点在 z-mcp-starter
     * ({@code AssertionError: context 已关, 健康检查线程还在跑 = destroyMethod 没生效}), 但根因不在
     * starter 的装配 —— 装配确实声明了 {@code @Bean(destroyMethod = "destroy")}. 根因是
     * {@code destroy()} 只把 {@code scheduler} 置成 null, 于是把门重新打开: 首轮同步跑在守护线程里
     * ({@code ZMcpAutoConfiguration} 起的 bootstrap 调 {@code manager.syncAll()}), 它只要比
     * {@code ctx.close()} 晚抢到 manager 这把锁, 就在一个**已经销毁的 bean** 上又开了一条周期线程,
     * 而这一条再没有任何人负责关(同 JVM 里每关启一次漏一条)。
     *
     * <p>间隔故意设成 300 秒, 所以周期任务在本用例里**永远不会自己触发**; 那次迟到的同步由测试
     * 亲自调 {@code syncAll()} 复现 —— 同一个方法、同一把锁, 只是换了调用者. 于是红绿完全由调用
     * 次序决定, 不赌时序(本机跑不出 CI 那种交错, 正是它一直绿的原因).
     */
    @Test
    public void destroy_is_terminal_even_when_a_late_sync_arrives_afterwards() throws Exception {
        Fixture f = new Fixture();
        f.properties.setHealthCheckIntervalSeconds(300);
        f.server("acme");
        publish(f, "acme", "[{\"name\":\"echo\"}]");

        f.manager.syncAll();
        Set<Thread> first = healthThreads();
        assertEquals("配了正间隔就该有一条健康检查线程", 1, first.size());

        f.manager.destroy();
        for (Thread t : first) {
            t.join(3_000L);
            // 上限而不是无超时 await: 无界的等只会把"摘掉守卫"的变异从判红变成挂死, 把量具一起拖走.
            assertFalse("destroy 之后那条健康检查线程还活着", t.isAlive());
        }

        // 迟到的首轮同步: bootstrap 线程在 ctx.close() 之后才拿到 manager 这把锁.
        f.manager.syncAll();
        Set<Thread> resurrected = healthThreads();
        resurrected.removeAll(first);
        assertTrue("destroy 是终点, 但一次迟到的 syncAll 又把周期线程起回来了: "
                + namesOf(resurrected), resurrected.isEmpty());
    }

    // ------------------------------------------------------ 上游推来的目录变更(#47)

    /** 等一条异步重同步落到目录里 —— 有上限的轮询, 不是固定 sleep. */
    private static void awaitTool(McpRegistry registry, String tool) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!registry.hasTool(tool) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    /**
     * 等"重同步不再往这条传输上发请求", 返回这之后一共发了几发.
     *
     * <p>连续两次数到同一个值(且中间隔了 300ms)才算静下来. 注意这个窗口只会朝"少数了几轮"的方向
     * 出偏差, 而少数的读数照样满足 {@code <=} 的断言 —— 所以它不会假红, 只可能放过.
     */
    private static int awaitQuiet(FakeExchange exchange, int from) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000L;
        int last = from;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300L);
            int now = exchange.listCalls();
            if (now == last) return now;
            last = now;
        }
        return last;
    }

    /**
     * 上游推一帧 {@code notifications/tools/list_changed}, 本地目录要立刻跟上.
     *
     * <p>这里的判据力气全在 {@code healthCheckIntervalSeconds=0}: 这一台 manager 上**没有**周期
     * 清扫线程(整批 {@code Fixture()} 用例都是 0), 所以"目录里多了东西"只可能是被推着去重取的
     * 结果, 而不是恰好撞上了一轮 healthCheck.
     *
     * <p>反向的两半: 只重取被推的那一台(否则一台上游闹一下全机器都跟着翻页), 和不吃的那一类通知
     * 一帧都不许换成一次往返. 后半一度写成"再推一条该吃的, 若前一条也被收下了则计数必然多一轮" ——
     * 变异体 M4(摘掉类目过滤)当场证明这把尺是空的: 两帧挨得近时合并本来就把它们并成同一轮,
     * 摘与不摘都是 before+1. 现在改成在同一把门上取双向证据, 见下面那段注释.
     */
    @Test
    public void a_pushed_list_changed_resyncs_that_one_server_without_the_health_sweep()
            throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        f.server("other");
        NotifyExchange acme = f.notifying("acme");
        handshake(acme, "s1");
        toolsPage(acme, "[{\"name\":\"echo\"}]");
        FakeExchange other = f.upstream("other");
        handshake(other, "s2");
        toolsPage(other, "[{\"name\":\"far_away\"}]");

        try {
            f.manager.syncAll();
            assertTrue(f.registry.hasTool("echo"));
            assertFalse(f.registry.hasTool("late_tool"));
            int acmeLists = acme.listCalls();
            int otherCalls = other.listCalls();

            toolsPage(acme, "[{\"name\":\"echo\"},{\"name\":\"late_tool\"}]");
            acme.push(McpSchema.N_TOOLS_LIST_CHANGED);
            awaitTool(f.registry, "late_tool");
            assertTrue("上游推了 list_changed 而目录里仍然只有: " + f.registry.toolNames(),
                    f.registry.hasTool("late_tool"));
            assertEquals("一帧推送引起了不止一轮重取: ", acmeLists + 1, acme.listCalls());
            assertEquals("推给 acme 却把别台也重取了一遍(一台上游闹一下, 全机器跟着翻页): ",
                    otherCalls, other.listCalls());

            // 反向: resources 那一类的目录变更与本接入器无关(只聚合 tools), 一帧都不许换成一次往返.
            // "少一轮"是数不出来的 —— 两帧挨得够近时合并本来就会把它们并成同一轮(合并正是下一条
            // 用例要钉的东西), 摘掉类目过滤照样满足 before+1. 所以这里换成门: 有没有走到 post
            // 是当场可见的, 并在**同一把门**上补一条阳性对照 —— 先证明推 tools 时它一定响,
            // 才说明推 resources 时没响, 而不是那把门本来就什么都测不到.
            int before = acme.listCalls();
            acme.enteredResync = new CountDownLatch(1);
            acme.releaseResync = new CountDownLatch(1);
            toolsPage(acme, "[{\"name\":\"echo\"},{\"name\":\"late_tool\"},"
                    + "{\"name\":\"later_tool\"}]");
            try {
                acme.push(McpSchema.N_RESOURCES_LIST_CHANGED);
                assertFalse("resources/list_changed 引起了重取: 本接入器只吃 tools 那一类通知",
                        acme.enteredResync.await(2, TimeUnit.SECONDS));
                acme.push(McpSchema.N_TOOLS_LIST_CHANGED);
                assertTrue("同一把门在推 tools/list_changed 时也没响 ⇒ 上面那句 assertFalse 是空跑出来的",
                        acme.enteredResync.await(10, TimeUnit.SECONDS));
            } finally {
                acme.releaseResync.countDown();
            }
            awaitTool(f.registry, "later_tool");
            assertEquals("resources/list_changed 被当成了 tools 的变更: ", before + 1,
                    acme.listCalls());
        } finally {
            // 关掉那条为推送而起的线程: 它活着的话, 按对象身份比的那条用例会被牵连
            f.manager.destroy();
        }
    }

    /**
     * 连推五帧要合并成一轮, 而且重同步一条都不许跑在**推来的那条线程**上.
     *
     * <p>后半句才是这条用例的主要目的: stdio 的读侧线程是那条传输唯一的收字人, 在它上面做一次
     * 往返 = 自己等自己读, 那一发 {@code tools/list} 只能读到超时 —— 现场看起来却只是"重同步没
     * 生效". 所以这里把重同步卡在 {@code post} 里, 记下每次 post 落在哪条线程, 再和推的那条比.
     *
     * <p>合并的上界是"至多两轮"而不是恰好一轮: 标记必须先清再同步(否则同步期间来的那一帧就丢了),
     * 于是紧跟在清标记之后的那一帧有权利再排一轮. 摘掉 CAS 就是五轮 —— 那才是这条要抓的东西.
     */
    @Test
    public void repeated_pushes_coalesce_and_never_run_on_the_pushing_thread() throws Exception {
        Fixture f = new Fixture();
        f.server("acme");
        NotifyExchange acme = f.notifying("acme");
        handshake(acme, "s1");
        toolsPage(acme, "[{\"name\":\"echo\"}]");
        f.manager.syncAll();
        int initial = acme.listCalls();
        acme.postThreads.clear();                       // 只看事件驱动那几发落在哪条线程

        for (int page = 0; page < 6; page++) {
            toolsPage(acme, "[{\"name\":\"echo\"},{\"name\":\"late_" + page + "\"}]");
        }
        acme.enteredResync = new CountDownLatch(1);
        acme.releaseResync = new CountDownLatch(1);
        try {
            acme.push(McpSchema.N_TOOLS_LIST_CHANGED);
            assertTrue("重同步根本没开始(一轮都没跑)",
                    acme.enteredResync.await(10, TimeUnit.SECONDS));
            // 第一轮还卡在 post 里, 这四帧就该并到它后面去, 而不是各排一轮
            for (int i = 0; i < 4; i++) acme.push(McpSchema.N_TOOLS_LIST_CHANGED);
        } finally {
            acme.releaseResync.countDown();            // 无论断言结果如何都不把那条线程卡在门里
        }

        // 先比线程身份(结构性判据, 当场定论), 再数轮次(awaitQuiet 只会少数, 是时间耦合的那一半)
        assertFalse("重同步一条都没跑", acme.postThreads.isEmpty());
        for (Thread t : acme.postThreads) {
            assertNotSame("重同步跑回了推它的那条线程(stdio 上就是读侧线程: 自己等自己读, 只能读到超时)",
                    Thread.currentThread(), t);
        }

        int rounds = awaitQuiet(acme, initial) - initial;
        assertTrue("五帧推送合并后仍然跑了 " + rounds + " 轮", rounds <= 2);

        // destroy 是终点: 那条为推送而起的线程不能留在机器上(同 JVM 里每关启一次留一条),
        // 判据按**对象身份**比而不是数名字 —— 别的用例留着的同名线程不该算到这台 manager 头上.
        List<Thread> spawned = new ArrayList<Thread>(acme.postThreads);
        f.manager.destroy();
        for (Thread t : spawned) {
            t.join(3_000L);
            // 有上限的 join: 无界的等只会把"忘了关线程池"的变异从判红变成挂死, 把量具一起拖走.
            assertFalse("destroy() 之后这条重同步线程还在: 线程池没关", t.isAlive());
        }
    }
}
