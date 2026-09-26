package com.zifang.z.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.mcp.core.properties.McpProperties;
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
 * 令牌这道闸在<em>这套发行件的配置形状</em>下到底锁住了什么.
 *
 * <p>core 那边已经把 {@link TransportSecurityGuard} 的三种 401 形状按单元钉过了; 这里钉的是
 * 只有真进程能回答的两件事: {@code bearer-tokens: ${Z_MCP_BEARER_TOKENS:}} 这一行在环境变量
 * 不给值时绑成什么(空列表=关鉴权, 还是单元素 {@code [""]}=任何请求都永远 401、包括带正确令牌的),
 * 以及逗号分隔的多令牌是不是真的按"一个逗号一个令牌"落进白名单(轮换期新旧并存靠的就是它).
 *
 * <p>第二组断言全部走真 HTTP: 闸装在控制器里, 从 bean 上读字段只能证明配置绑对了,
 * 证明不了四扇门都过了这道闸.
 */
public class BearerGateTest {

    private static final String HUB_TOKEN = "hub-door-token";
    private static final String LEAF_TOKEN = "leaf-door-token";
    private static final long DEADLINE_MILLIS = 30_000L;

    private final List<ConfigurableApplicationContext> opened =
            new ArrayList<ConfigurableApplicationContext>();

    @After public void closeEverything() {
        for (ConfigurableApplicationContext ctx : opened) ctx.close();
        opened.clear();
    }

    // -------------------------------------------------------- 发行件里那行占位符

    @Test public void an_unset_token_variable_leaves_the_door_open_instead_of_locking_it_shut()
            throws Exception {
        ConfigurableApplicationContext leaf = leaf("text");
        List<String> bound = tokens(leaf);
        // 绑成 [""] 的话后果是"这台服务谁都不理, 包括带对令牌的": 消息里不会有任何线索,
        // 因为它看起来像"配了鉴权"而实际上配的是一个谁也带不出来的空令牌.
        assertEquals("z.mcp.security.bearer-tokens: ${Z_MCP_BEARER_TOKENS:} 在变量未给值时应绑成空列表,"
                + " 空列表才是\"关闭鉴权\"的表示: " + bound,
                Collections.<String>emptyList(), bound);

        Wire wire = Wire.client(Boot.port(leaf));
        assertEquals(Arrays.asList("text_stats", "text_transform"), sorted(wire.toolNames()));
        assertStatus(wire, 200, "/mcp/info");
        assertStatus(wire, 200, "/mcp/admin/overview");
    }

    @Test public void a_comma_separated_value_binds_to_one_token_per_entry() throws Exception {
        // 轮换期的常规做法: 新旧令牌并存, 两边都能进来. 这里量的是"逗号到底拆不拆",
        // 而不是拿文档里的说法当结论 —— 不拆的话第二把令牌会静默失效, 老令牌一撤就全场断开.
        ConfigurableApplicationContext leaf = leaf("text", "--z.mcp.security.bearer-tokens=t1,t2");
        assertEquals(Arrays.asList("t1", "t2"), tokens(leaf));
        for (String token : Arrays.asList("t1", "t2")) {
            Wire wire = Wire.client(Boot.port(leaf), token);
            assertFalse(token + " 应当打得开: " + wire.initializeResult(),
                    wire.callTool("text_stats", Boot.jsonArgs("text", "a b")).path("isError").asBoolean());
            assertStatus(wire, 200, "/mcp/info");
            assertStatus(wire, 200, "/mcp/admin/overview");
        }
    }

    // ------------------------------------------------------------ 四扇门一把闸

    @Test public void every_door_refuses_the_same_three_ways_and_opens_for_the_right_token()
            throws Exception {
        ConfigurableApplicationContext leaf = leaf("text", "--z.mcp.security.bearer-tokens=t1,t2");
        int port = Boot.port(leaf);

        for (Door door : Door.values()) {
            Wire anonymous = Wire.of(port);
            Wire.Response res = door.send(anonymous, null);
            assertEquals(door + " 匿名访问必须 401: " + res, 401, res.status);
            assertEquals(door + " 的 401 必须带裸挑战(RFC 6750), 否则客户端学不到该带凭证: " + res,
                    "Bearer realm=\"z-mcp\"", res.header("WWW-Authenticate"));

            for (String[] bad : new String[][]{{"nope", "invalid_token"}, {"", "invalid_request"}}) {
                Wire.Response wrong = door.send(Wire.of(port, bad[0]), null);
                assertEquals(door + " 带 " + (bad[0].isEmpty() ? "空令牌" : "错令牌") + " 应 401",
                        401, wrong.status);
                assertTrue(door + " 的挑战要区分\"令牌被拒\"和\"请求不成形\", 客户端据此决定重试还是重来: "
                                + wrong.header("WWW-Authenticate"),
                        wrong.header("WWW-Authenticate").contains("error=\"" + bad[1] + "\""));
            }
        }

        // 对照: 如果闸写成"永远 401", 上面十二条会全部通过而没人发现.
        assertTrue(Wire.client(port, "t1").toolNames().contains("text_stats"));
    }

    // ------------------------------------------------- hub 与上游各走各的凭证

    @Test public void the_hub_door_and_the_upstream_door_take_different_tokens() throws Exception {
        int leafPort = Boot.port(leaf("text", "--z.mcp.security.bearer-tokens=" + LEAF_TOKEN));
        List<String> args = new ArrayList<String>();
        args.add("--spring.profiles.active=hub");
        args.add("--z.mcp.security.bearer-tokens=" + HUB_TOKEN);
        addUpstream(args, 0, "text", leafPort, "Bearer " + LEAF_TOKEN);
        // yml 里第二条上游指向 18097: 测试机器上那个端口归谁不该由本测试赌, 显式停用比
        // "顺手把它也指到某个真服务上"更诚实.
        args.add("--z.mcp.servers[1].enabled=false");
        ConfigurableApplicationContext hub = app(args.toArray(new String[0]));
        int port = Boot.port(hub);

        // 两把令牌不可互换: 拿上游那把去开 hub 的门 = 被拒, 反之亦然.
        Wire.Response crossed = Wire.of(port, LEAF_TOKEN).initializeAttempt();
        assertEquals("上游的令牌不该能开 hub 的门", 401, crossed.status);

        Wire wire = Wire.client(port, HUB_TOKEN);
        awaitCatalog(wire, Arrays.asList("echo", "find_tools", "generate_uuid", "get_time",
                "registry_state", "system_info", "text_stats", "text_transform"));
        JsonNode called = wire.callTool("text_stats", Boot.jsonArgs("text", "a b\nc"));
        assertFalse(Boot.firstText(called), called.path("isError").asBoolean());
        assertEquals(3, Boot.json(Boot.firstText(called)).path("words").asInt());
        assertEquals("被停用那条不该混进目录", 1,
                Boot.json(Boot.firstText(wire.callTool("registry_state", "{}")))
                        .path("upstreams").path("connected").asInt());
    }

    /**
     * 部署最容易踩的一种"上游连不上": yml 里写了 {@code Authorization: "Bearer ${VAR}"},
     * 而 {@code Z_MCP_UPSTREAM_TOKEN} 忘了给 ⇒ 头里只剩方案名. 状态视图必须把这句话讲到
     * "去看 servers[] 的 headers"这一层, 而不是留一个 {@code HTTP 401} 让运维两头猜.
     */
    @Test public void an_upstream_whose_token_variable_is_unset_says_so_in_the_state_view()
            throws Exception {
        int leafPort = Boot.port(leaf("text", "--z.mcp.security.bearer-tokens=" + LEAF_TOKEN));
        List<String> args = new ArrayList<String>();
        args.add("--spring.profiles.active=hub");
        args.add("--z.mcp.health-check-interval-seconds=1");
        addUpstream(args, 0, "text", leafPort, null);
        args.add("--z.mcp.servers[1].enabled=false");
        ConfigurableApplicationContext hub = app(args.toArray(new String[0]));

        final Wire wire = Wire.client(Boot.port(hub));
        Boot.until(System.currentTimeMillis() + DEADLINE_MILLIS, "上游带着\"只有 Bearer 前缀\"的头被拒",
                new Boot.Probe() {
                    @Override public String inspect() throws Exception {
                        JsonNode state = serverOf(Boot.json(Boot.firstText(
                                wire.callTool("registry_state", "{}"))), "text");
                        if (state.path("connected").asBoolean()) {
                            throw new Boot.ExpectationPending("居然还连上了: " + state);
                        }
                        String why = state.path("lastError").asText("");
                        if (why.isEmpty()) throw new Boot.ExpectationPending("还没记下原因: " + state);
                        if (!why.contains("401")) {
                            throw new Boot.ExpectationPending("原因里没有 401: " + why);
                        }
                        if (!why.contains("no token value") && !why.contains("no credential")) {
                            throw new Boot.ExpectationPending("原因只报了状态码, 没说我们带出去了什么: " + why);
                        }
                        return why;
                    }
                });
        assertFalse("连不上的上游不该继续广告工具: " + wire.toolNames(),
                wire.toolNames().contains("text_stats"));
        assertFalse("令牌值不许出现在状态视图里: ",
                Boot.firstText(wire.callTool("registry_state", "{}")).contains(LEAF_TOKEN));
    }

    // ---------------------------------------------------------------- 内部件

    private enum Door {
        MCP_POST {
            @Override Wire.Response send(Wire wire, String session) throws java.io.IOException {
                return wire.post("/mcp", wire.request("tools/list", "{}"), session, null);
            }
        },
        MCP_STREAM {
            @Override Wire.Response send(Wire wire, String session) throws java.io.IOException {
                return wire.get("/mcp", session);
            }
        },
        MCP_DELETE {
            @Override Wire.Response send(Wire wire, String session) throws java.io.IOException {
                return wire.send("DELETE", "/mcp", null, session, null, null);
            }
        },
        INFO {
            @Override Wire.Response send(Wire wire, String session) throws java.io.IOException {
                return wire.get("/mcp/info", session);
            }
        },
        ADMIN {
            @Override Wire.Response send(Wire wire, String session) throws java.io.IOException {
                return wire.get("/mcp/admin/overview", session);
            }
        };

        abstract Wire.Response send(Wire wire, String session) throws java.io.IOException;
    }

    private static void assertStatus(Wire wire, int expected, String path) throws java.io.IOException {
        Wire.Response res = wire.get(path, null);
        assertEquals(path + " -> " + res, expected, res.status);
    }

    private static List<String> tokens(ConfigurableApplicationContext ctx) {
        return ctx.getBean(McpProperties.class).getSecurity().getBearerTokens();
    }

    private ConfigurableApplicationContext leaf(String role, String... extra) {
        List<String> args = new ArrayList<String>();
        args.add("--spring.profiles.active=" + role);
        args.addAll(Arrays.asList(extra));
        return app(args.toArray(new String[0]));
    }

    private ConfigurableApplicationContext app(String... args) {
        ConfigurableApplicationContext ctx = Boot.app(args);
        opened.add(ctx);
        return ctx;
    }

    private static void addUpstream(List<String> args, int index, String name, int port,
                                    String authorization) {
        String p = "--z.mcp.servers[" + index + "].";
        args.add(p + "name=" + name);
        args.add(p + "endpoint=http://127.0.0.1:" + port + "/mcp");
        args.add(p + "transport=http");
        if (authorization != null) args.add(p + "headers.Authorization=" + authorization);
    }

    private static JsonNode serverOf(JsonNode registryState, String name) {
        for (JsonNode s : registryState.path("servers")) {
            if (name.equals(s.path("name").asText())) return s;
        }
        throw new AssertionError("registry_state 里没有上游 " + name + ": " + registryState);
    }

    private void awaitCatalog(final Wire wire, final List<String> expected) throws Exception {
        final List<String> want = sorted(expected);
        Boot.until(System.currentTimeMillis() + DEADLINE_MILLIS,
                "目录等于 " + want, new Boot.Probe() {
                    @Override public String inspect() throws Exception {
                        List<String> actual = sorted(wire.toolNames());
                        if (!actual.equals(want)) {
                            throw new Boot.ExpectationPending("现在是 " + actual);
                        }
                        return actual.toString();
                    }
                });
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<String>(in);
        Collections.sort(out);
        return out;
    }
}
