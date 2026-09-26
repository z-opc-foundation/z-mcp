package com.zifang.z.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * hub 把内网其它 MCP server 聚合成一个入口 —— 这是"服务中心"这个说法的全部内容, 所以每一条
 * 都从 hub 的 wire 上判, 不去查 manager 的字段.
 *
 * <p>上游是<em>真的</em>另一套 Spring 上下文(真 Tomcat、真 HTTP 往返), 不是 mock: 撞名规则、
 * 掉线摘除、跨进程调用这三件事全都发生在进程边界上, 把网络换掉就等于什么都没测.
 */
public class HubAggregationTest {

    /** 同步周期在测试里压到 1 秒, 所以掉线可见性以 30 秒为上限去等. */
    private static final long DEADLINE_MILLIS = 30_000L;

    private static final List<String> HUB_LOCALS = Arrays.asList(
            "echo", "find_tools", "generate_uuid", "get_time", "registry_state", "system_info");

    private final List<ConfigurableApplicationContext> opened =
            new ArrayList<ConfigurableApplicationContext>();

    @After public void closeEverything() {
        for (ConfigurableApplicationContext ctx : opened) ctx.close();
        opened.clear();
    }

    // ------------------------------------------------------------------ 聚合

    @Test public void upstreams_share_one_catalog_and_only_collisions_get_a_namespace()
            throws Exception {
        List<String> servers = new ArrayList<String>();
        int textA = upstream("text");
        int textB = upstream("text");
        int codec = upstream("codec");
        addServer(servers, 0, "dup-a", textA);
        addServer(servers, 1, "dup-b", textB);
        addServer(servers, 2, "codec", codec);
        Wire hub = Wire.client(hubOf(servers));

        // 两路上游提供<em>同名</em>工具: 先到的拿到裸名, 后到的撞上 hub 已有条目才带 dup-b__ 前缀.
        List<String> expected = new ArrayList<String>(HUB_LOCALS);
        expected.addAll(Arrays.asList("base64_codec", "hash_digest",
                "text_stats", "text_transform", "dup-b__text_stats", "dup-b__text_transform"));
        awaitCatalog(hub, expected);

        // 跨进程真的把调用打到上游, 而不是"目录里有、一调就死".
        assertEquals("aGVsbG8=", callVia(hub, "base64_codec", "text", "hello", "mode", "encode"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Boot.json(callVia(hub, "hash_digest", "text", "")).path("hex").asText());
        assertEquals(3, Boot.json(callVia(hub, "text_stats", "text", "a b\nc")).path("words").asInt());
        assertEquals("带前缀的名字也要能调到: ",
                3, Boot.json(callVia(hub, "dup-b__text_stats", "text", "a b\nc"))
                        .path("words").asInt());

        // 目录服务读到的是真状态.
        JsonNode found = Boot.json(callVia(hub, "find_tools", "query", "digest"));
        assertEquals(1, found.path("matched").asInt());
        assertEquals("codec", found.path("tools").get(0).path("server").asText());
        JsonNode state = Boot.json(callVia(hub, "registry_state"));
        assertEquals(state.path("upstreams").toString(), 3,
                state.path("upstreams").path("connected").asInt());
        assertEquals("http", serverOf(state, "dup-a").path("transport").asText());
        assertEquals("http://127.0.0.1:" + codec + "/mcp",
                serverOf(state, "codec").path("endpoint").asText());
        assertEquals(2, serverOf(state, "dup-b").path("tools").size());

        // 管理面与数据面看到的是同一件事(运维只有一处可查).
        JsonNode health = getJson(hub, "/mcp/admin/health");
        assertEquals(health.toString(), "UP", health.path("status").asText());
        assertEquals(3, health.path("externalServers").path("connected").asInt());
    }

    /**
     * 两台<em>不同名</em>的 hub 互为上游时, 目录必须停在不动点.
     *
     * <p>自指判据在这形状里帮不上忙 —— 两边握手看到的都是对岸自己的名字. 拦住它的是来源链:
     * a 的内置工具被 b 抄走再广告回来时, 链上带着 {@code z-mcp}(=a 自己), 于是 a 拒收.
     * 没有这条判据, 每一轮同步都会多出一代前缀名({@code mirror__origin__echo} 这种), 目录无界增长.
     *
     * <p>两边都用 {@code --server.port=} 钉死端口才可能互指; 启动顺序无关 —— 上游暂时连不上
     * 只是那一轮同步失败, 1 秒后的下一轮重来.
     */
    @Test public void two_hubs_that_aggregate_each_other_reach_a_fixed_point() throws Exception {
        int portA = freePort();
        int portB = freePort();
        List<String> aSees = new ArrayList<String>();
        addServer(aSees, 0, "mirror", portB);
        aSees.add("--server.port=" + portA);
        int a = hubOf(aSees);                       // server-name 用默认的 z-mcp
        List<String> bSees = new ArrayList<String>();
        addServer(bSees, 0, "origin", portA);
        bSees.add("--server.port=" + portB);
        bSees.add("--z.mcp.server-name=hub-b");     // 关键: 对岸不叫 z-mcp, 自指判据认不出来
        int b = hubOf(bSees);

        Wire hubA = Wire.client(a);
        Wire hubB = Wire.client(b);

        // 各拿到对面 6 件内置 —— 六件全与自己同名, 所以全部带前缀, 目录停在 6 + 6 = 12.
        List<String> wantA = new ArrayList<String>(HUB_LOCALS);
        List<String> wantB = new ArrayList<String>(HUB_LOCALS);
        for (String n : HUB_LOCALS) {
            wantA.add("mirror__" + n);
            wantB.add("origin__" + n);
        }
        awaitCatalog(hubA, wantA);
        awaitCatalog(hubB, wantB);

        // 负向断言要熬过若干个同步轮: 只看一眼会放过"每轮长一代"这个形状.
        for (int round = 0; round < 6; round++) {
            Thread.sleep(1_000L);
            assertEquals("第 " + (round + 2) + " 轮之后 a 的目录必须还是 12 条: " + hubA.toolNames(),
                    sorted(wantA), sorted(hubA.toolNames()));
            assertEquals("第 " + (round + 2) + " 轮之后 b 的目录必须还是 12 条: " + hubB.toolNames(),
                    sorted(wantB), sorted(hubB.toolNames()));
        }
        assertFalse("不许出现第二代前缀名: " + hubA.toolNames(),
                hubA.toolNames().toString().contains("__origin__"));

        // 判据是链, 不是名字: 抄回来的那条自带对岸的盖章, 而本机名字是广告时才追加上去的.
        assertEquals("链上必须只有对岸, 不能有第二跳: ",
                "hub-b,z-mcp", toolEntry(hubA.result("tools/list", "{}"), "mirror__echo")
                        .path("_meta").path("z-mcp/origins").asText());
        assertEquals("b 一侧同理",
                "z-mcp,hub-b", toolEntry(hubB.result("tools/list", "{}"), "origin__echo")
                        .path("_meta").path("z-mcp/origins").asText());
    }

    private static int freePort() throws java.io.IOException {
        java.net.ServerSocket socket = new java.net.ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static JsonNode toolEntry(JsonNode toolsListResult, String name) {
        for (JsonNode t : toolsListResult.path("tools")) {
            if (name.equals(t.path("name").asText())) return t;
        }
        throw new AssertionError("目录里没有 " + name + ": " + toolsListResult);
    }

    @Test public void an_upstream_that_dies_loses_its_tools_and_names_why_in_the_state_view()
            throws Exception {
        int text = upstream("text");
        ConfigurableApplicationContext codecCtx = leaf("codec");
        List<String> servers = new ArrayList<String>();
        addServer(servers, 0, "text", text);
        addServer(servers, 1, "codec", Boot.port(codecCtx));
        Wire hub = Wire.client(hubOf(servers));

        List<String> both = new ArrayList<String>(HUB_LOCALS);
        both.addAll(Arrays.asList("base64_codec", "hash_digest", "text_stats", "text_transform"));
        awaitCatalog(hub, both);

        codecCtx.close();

        Boot.until(System.currentTimeMillis() + DEADLINE_MILLIS, "codec 掉线后被摘出目录",
                new Boot.Probe() {
                    @Override public String inspect() throws Exception {
                        JsonNode s = serverOf(Boot.json(callVia(hub, "registry_state")), "codec");
                        if (s.path("connected").asBoolean()) {
                            throw new Boot.ExpectationPending("还连着: " + s);
                        }
                        if (s.path("lastError").asText("").isEmpty()) {
                            throw new Boot.ExpectationPending("还没记下失败原因: " + s);
                        }
                        if (hub.toolNames().contains("base64_codec")) {
                            throw new Boot.ExpectationPending("工具还没摘干净: " + hub.toolNames());
                        }
                        return s.toString();
                    }
                });

        List<String> names = hub.toolNames();
        assertFalse("掉线的上游还在广告 base64_codec: " + names, names.contains("base64_codec"));
        assertFalse(names.contains("hash_digest"));
        assertTrue("另一个上游不该被牵连: " + names, names.contains("text_stats"));
        assertFalse("活着的上游照样可调", hub.callTool("text_stats",
                Boot.jsonArgs("text", "x y")).path("isError").asBoolean());

        // 目录里没有了还硬调: 协议规定是 -32602(tool 不存在), 不是 500, 也不是"hub 替它跑一遍".
        Wire.Response res = hub.post("/mcp", hub.request("tools/call",
                "{\"name\":\"base64_codec\",\"arguments\":{\"text\":\"a\",\"mode\":\"encode\"}}"),
                hub.session(), null);
        assertEquals(res.body, 200, res.status);
        assertTrue(res.body, res.body.contains("-32602"));

        JsonNode health = getJson(hub, "/mcp/admin/health");
        assertEquals(health.toString(), "DEGRADED", health.path("status").asText());
        assertTrue(health.toString(),
                health.path("unreachableServers").toString().contains("codec"));
        JsonNode state = getJson(hub, "/mcp/admin/servers/state");
        assertEquals("掉线的上游一个工具都不该留着: " + state,
                0, serverOfJson(state, "codec").path("toolCount").asInt());
        assertEquals(2, serverOfJson(state, "text").path("toolCount").asInt());
        assertTrue(serverOfJson(state, "codec").path("lastError").asText().length() > 0);
    }

    // ---------------------------------------------------------------- 上游鉴权

    @Test public void an_upstream_behind_a_bearer_token_is_reachable_only_because_the_header_is_configured()
            throws Exception {
        List<String> servers = new ArrayList<String>();
        addServer(servers, 0, "text", upstream("text", TOKENED), "Bearer upstream-secret");
        addServer(servers, 1, "codec", upstream("codec", TOKENED), "Bearer upstream-secret");
        Wire hub = Wire.client(hubOf(servers));

        List<String> expected = new ArrayList<String>(HUB_LOCALS);
        expected.addAll(Arrays.asList("base64_codec", "hash_digest", "text_stats", "text_transform"));
        awaitCatalog(hub, expected);
        assertEquals("aGVsbG8=", callVia(hub, "base64_codec", "text", "hello", "mode", "encode"));
        JsonNode state = Boot.json(callVia(hub, "registry_state"));
        assertEquals(2, state.path("upstreams").path("connected").asInt());
    }

    @Test public void a_wrong_upstream_token_leaves_that_upstream_out_of_the_catalog()
            throws Exception {
        List<String> servers = new ArrayList<String>();
        addServer(servers, 0, "text", upstream("text", TOKENED), "Bearer nope");
        addServer(servers, 1, "codec", upstream("codec"));
        Wire hub = Wire.client(hubOf(servers));

        // 令牌配错的那路进不了目录, 另一路不受影响 —— 一个配错不该拖垮整台机器.
        List<String> expected = new ArrayList<String>(HUB_LOCALS);
        expected.addAll(Arrays.asList("base64_codec", "hash_digest"));
        awaitCatalog(hub, expected);

        JsonNode state = Boot.json(callVia(hub, "registry_state"));
        JsonNode text = serverOf(state, "text");
        assertFalse(text.toString(), text.path("connected").asBoolean());
        assertTrue("401 得留个读得懂的原因: " + text, text.path("lastError").asText().length() > 0);
        assertEquals(1, state.path("upstreams").path("failed").asInt());
        assertTrue("hub 自己的 builtin 不该受上游影响",
                hub.toolNames().contains("system_info"));
    }

    // ---------------------------------------------------------------- 内部件

    private static final String[] TOKENED = {"--z.mcp.security.bearer-tokens=upstream-secret"};

    private static void addServer(List<String> args, int index, String name, int port) {
        addServer(args, index, name, port, null);
    }

    /** 每个字段一条命令行参数: 命令行属性源的优先级高于 yml, 是唯一可靠的上游覆盖通道. */
    private static void addServer(List<String> args, int index, String name, int port,
                                  String authorization) {
        String p = "--z.mcp.servers[" + index + "].";
        args.add(p + "name=" + name);
        args.add(p + "endpoint=http://127.0.0.1:" + port + "/mcp");
        args.add(p + "transport=http");
        if (authorization != null) args.add(p + "headers.Authorization=" + authorization);
    }

    private int upstream(String role, String... extra) {
        return Boot.port(leaf(role, extra));
    }

    private int hubOf(List<String> serverArgs) {
        List<String> args = new ArrayList<String>();
        args.add("--spring.profiles.active=hub");
        // 生产默认 30 秒一轮, 对测试太慢: 压到 1 秒才能在上限内看到"掉线被摘除".
        args.add("--z.mcp.health-check-interval-seconds=1");
        args.addAll(serverArgs);
        return Boot.port(openApp(args.toArray(new String[0])));
    }

    private ConfigurableApplicationContext openApp(String... args) {
        ConfigurableApplicationContext ctx = Boot.app(args);
        opened.add(ctx);
        return ctx;
    }

    private ConfigurableApplicationContext leaf(String role, String... extra) {
        List<String> args = new ArrayList<String>();
        args.add("--spring.profiles.active=" + role);
        args.addAll(Arrays.asList(extra));
        return openApp(args.toArray(new String[0]));
    }

    private void awaitCatalog(final Wire hub, final List<String> expected) throws Exception {
        final List<String> want = sorted(expected);
        Boot.until(System.currentTimeMillis() + DEADLINE_MILLIS,
                "hub 目录等于 " + want, new Boot.Probe() {
                    @Override public String inspect() throws Exception {
                        List<String> actual = sorted(hub.toolNames());
                        if (!actual.equals(want)) {
                            throw new Boot.ExpectationPending("现在是 " + actual);
                        }
                        return actual.toString();
                    }
                });
        assertEquals(want, sorted(hub.toolNames()));
    }

    private static String callVia(Wire hub, String tool, Object... keysAndValues) throws Exception {
        JsonNode result = hub.callTool(tool, Boot.jsonArgs(keysAndValues));
        assertFalse("经 hub 调用 " + tool + " 失败: " + result, result.path("isError").asBoolean());
        return Boot.firstText(result);
    }

    private static JsonNode getJson(Wire hub, String path) throws Exception {
        Wire.Response res = hub.get(path, null);
        assertEquals(path + " -> " + res, 200, res.status);
        return Boot.json(res.body);
    }

    private static JsonNode serverOf(JsonNode registryState, String name) {
        for (JsonNode s : registryState.path("servers")) {
            if (name.equals(s.path("name").asText())) return s;
        }
        throw new AssertionError("registry_state 里没有上游 " + name + ": " + registryState);
    }

    private static JsonNode serverOfJson(JsonNode array, String name) {
        for (JsonNode s : array) {
            if (name.equals(s.path("name").asText())) return s;
        }
        throw new AssertionError("admin 状态里没有上游 " + name + ": " + array);
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<String>(in);
        Collections.sort(out);
        return out;
    }
}
