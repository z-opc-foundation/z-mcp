package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.admin.controller.AdminController;
import com.zifang.z.mcp.admin.service.AdminQueryService;
import com.zifang.z.mcp.core.client.ExternalServerManager;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpProtocolHandler;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.security.TransportSecurityGuard;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.junit.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 自动装配的门禁 —— 证明"引入 starter 就真的得到一套能用的 MCP 面", 以及
 * {@code z.mcp.servers[]} 真的被读、真的有人去连.
 *
 * <p>0.1.x 的问题不在代码在装配: servers / healthCheckIntervalSeconds 写进 yaml 后没有任何
 * bean 读取, README 却写着"支持多 server 聚合". 所以这里必须验到 bean 存在 *并且* 行为发生.
 */
public class ZMcpAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 真应用里 ObjectMapper 由 Boot 的 Jackson 装配提供, starter 只消费不新建
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, ZMcpAutoConfiguration.class));

    @Test
    public void nothing_is_wired_unless_the_module_is_asked_for() {
        runner.run(ctx -> {
            assertEquals("z.mcp.enabled 默认不匹配 ⇒ 一个 bean 都不该出现",
                    0, ctx.getBeanNamesForType(McpRegistry.class).length);
            assertEquals(0, ctx.getBeanNamesForType(McpProtocolHandler.class).length);
        });
    }

    @Test
    public void enabling_gives_a_registry_with_builtin_tools_and_a_full_http_stack() {
        runner.withPropertyValues("z.mcp.enabled=true").run(ctx -> {
            McpRegistry registry = ctx.getBean(McpRegistry.class);
            assertFalse("内置工具没注册进来", registry.listBuiltinTools().isEmpty());
            assertTrue(registry.hasTool("get_time"));
            assertNotNull(ctx.getBean(McpProtocolHandler.class));
            assertNotNull(ctx.getBean(McpSessionStore.class));
            assertNotNull(ctx.getBean(TransportSecurityGuard.class));
            assertNotNull("controller 由 @ComponentScan 装配",
                    ctx.getBean(com.zifang.z.mcp.core.controller.JsonRpcController.class));
        });
    }

    @Test
    public void server_identity_comes_from_configuration() {
        runner.withPropertyValues("z.mcp.enabled=true",
                "z.mcp.server-name=my-gateway",
                "z.mcp.server-version=9.9.9-test").run(ctx -> {
            McpProperties properties = ctx.getBean(McpProperties.class);
            assertEquals("my-gateway", properties.getServerName());
            assertEquals("9.9.9-test", properties.getServerVersion());
        });
    }

    @Test
    public void strict_tool_names_is_honoured_by_the_registry() {
        runner.withPropertyValues("z.mcp.enabled=true",
                "z.mcp.strict-tool-names=false").run(ctx -> {
            assertFalse(ctx.getBean(McpProperties.class).isStrictToolNames());
            // 关掉严格模式后, 不合规的名字要能进来
            ctx.getBean(McpRegistry.class).tool("bad name!")
                    .inputSchema("{\"type\":\"object\"}")
                    .register(args -> "ok");
            assertTrue(ctx.getBean(McpRegistry.class).hasTool("bad name!"));
        });
    }

    /** 这一条是"死配置落地"的证据: yaml 里的一行 ⇒ 真的有人去连, 且连不上会被摘掉. */
    @Test
    public void configured_servers_are_dialed_by_the_manager_at_startup() throws Exception {
        runner.withPropertyValues("z.mcp.enabled=true",
                "z.mcp.health-check-interval-seconds=0",
                "z.mcp.servers[0].name=acme",
                "z.mcp.servers[0].transport=http",
                "z.mcp.servers[0].endpoint=http://127.0.0.1:1/mcp",
                "z.mcp.servers[0].connect-timeout-millis=300",
                "z.mcp.servers[0].read-timeout-millis=300").run(ctx -> {
            McpProperties properties = ctx.getBean(McpProperties.class);
            assertEquals(1, properties.getServers().size());
            assertEquals("acme", properties.getServers().get(0).getName());
            assertEquals(300, properties.getServers().get(0).getConnectTimeoutMillis());

            ExternalServerManager manager = ctx.getBean(ExternalServerManager.class);
            Map<String, Object> health = awaitHealth(manager, 5_000L);
            assertEquals("后台线程没去连配置里的 server: " + health,
                    1, ((Number) health.get("configured")).intValue());
            assertEquals(0, ((Number) health.get("connected")).intValue());
            assertEquals(1, ((Number) health.get("failed")).intValue());
            // 连不上的 server 不能往目录里留工具
            McpRegistry registry = ctx.getBean(McpRegistry.class);
            assertEquals(registry.listBuiltinTools().size(), registry.toolCount());
        });
    }

    @Test
    public void no_servers_configured_still_produces_a_working_local_server() {
        runner.withPropertyValues("z.mcp.enabled=true",
                "z.mcp.health-check-interval-seconds=0").run(ctx -> {
            ExternalServerManager manager = ctx.getBean(ExternalServerManager.class);
            assertTrue(manager.state().isEmpty());
            assertEquals(0, ((Number) manager.health().get("configured")).intValue());
            assertTrue(ctx.getBean(McpRegistry.class).toolCount() > 0);
        });
    }

    @Test
    public void the_user_can_replace_the_manager_with_their_own() {
        final ExternalServerManager mine = new ExternalServerManager(new McpRegistry(),
                new McpProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
        runner.withPropertyValues("z.mcp.enabled=true")
                .withBean(ExternalServerManager.class, () -> mine)
                .run(ctx -> {
                    String[] names = ctx.getBeanNamesForType(ExternalServerManager.class);
                    assertEquals("@ConditionalOnMissingBean 要让位于用户自定义的 bean", 1, names.length);
                    assertSame(mine, ctx.getBean(ExternalServerManager.class));
                });
    }

    /** 这一条钉的是"死模块复活": 开关打开后控制面必须真的在容器里. */
    @Test
    public void expose_admin_mounts_the_control_plane() {
        runner.withPropertyValues("z.mcp.enabled=true", "z.mcp.expose-admin=true").run(ctx -> {
            AdminController controller = ctx.getBean(AdminController.class);
            assertNotNull(controller);
            RequestMapping mapping = controller.getClass().getAnnotation(RequestMapping.class);
            assertNotNull("@RequestMapping 必须还在", mapping);
            // 前缀跟着 base-path 走(默认空串 ⇒ 路径与 0.1.x 一致); 占位符到底展开成什么 URL,
            // 由 ZMcpAdminEndpointTest 在真 Tomcat 上验, 这里只钉"值里带的是这个占位符而不是裸 /mcp/admin".
            assertArrayEquals(new String[]{"${z.mcp.base-path:}/mcp/admin"}, mapping.value());
            assertNotNull(ctx.getBean(AdminQueryService.class));

            McpRegistry registry = ctx.getBean(McpRegistry.class);
            Map<String, Object> overview = ctx.getBean(AdminQueryService.class).overview();
            assertEquals("控制面报的工具数必须是注册表里的真数",
                    registry.toolCount(), ((Number) overview.get("toolCount")).intValue());
            assertEquals(registry.serverCount(),
                    ((Number) overview.get("serverCount")).intValue());
        });
    }

    @Test
    public void the_control_plane_stays_off_without_the_flag() {
        runner.withPropertyValues("z.mcp.enabled=true").run(ctx -> assertEquals(
                "expose-admin 默认必须是关的, 否则控制面跟着业务端口一起暴露",
                0, ctx.getBeanNamesForType(AdminController.class).length));
    }

    /** 首轮同步在守护线程里跑(不能让一个死上游卡住启动), 所以断言要带上限地等它. */
    private static Map<String, Object> awaitHealth(ExternalServerManager manager, long maxWaitMillis) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        Map<String, Object> health = manager.health();
        while (System.currentTimeMillis() < deadline
                && ((Number) health.get("configured")).intValue() == 0) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for the bootstrap sync");
            }
            health = manager.health();
        }
        return health;
    }

    @Test
    public void closing_the_context_stops_the_health_check_thread() throws Exception {
        runner.withPropertyValues("z.mcp.enabled=true",
                "z.mcp.health-check-interval-seconds=1",
                "z.mcp.servers[0].name=acme",
                "z.mcp.servers[0].endpoint=http://127.0.0.1:1/mcp",
                "z.mcp.servers[0].connect-timeout-millis=300",
                "z.mcp.servers[0].read-timeout-millis=300").run(ctx -> {
            ExternalServerManager manager = ctx.getBean(ExternalServerManager.class);
            awaitHealth(manager, 5_000L);
            assertTrue("首轮同步后应该已经有健康检查线程", awaitThread(HEALTH_THREAD, 3_000L));

            ctx.close();
        });
        assertFalse("context 已关, 健康检查线程还在跑 = destroyMethod 没生效",
                awaitThread(HEALTH_THREAD, 1_500L));
    }

    private static final String HEALTH_THREAD = "z-mcp-server-health";

    private static boolean awaitThread(String name, long maxWaitMillis) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            if (hasThread(name)) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return hasThread(name);
    }

    private static boolean hasThread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive()) return true;
        }
        return false;
    }
}
