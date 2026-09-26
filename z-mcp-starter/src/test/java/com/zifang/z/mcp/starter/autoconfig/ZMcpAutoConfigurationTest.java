package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.admin.controller.AdminController;
import com.zifang.z.mcp.admin.service.AdminQueryService;
import com.zifang.z.mcp.core.client.ExternalServerManager;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpProtocolHandler;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.security.TransportSecurityGuard;
import com.zifang.z.mcp.core.session.McpSessionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * 关掉上下文之后健康检查不再问任何东西 —— 判据是**上游收到几次请求**, 不是"有没有一条名字叫
     * {@code z-mcp-server-health} 的线程".
     *
     * <p>原来这一条问的是后者, 而那个名字是全局固定的: 红只证明"那一刻确实有一条这个名字的线程活着",
     * 说不清是本次那条 worker 没醒透（{@code shutdownNow()} 只发中断、不等终止）、还是同 JVM 里前一个
     * context 留着的残留 —— CI 的 jdk17 格就是这么红过而同一趟的 jdk8 格全绿。现在两半各归一处:
     * **数得出**的是"关闭以后上游还被问几次"(产品承诺), **按身份**等的是"我手里这几条线程得死"
     * (资源收口)。迟到同步那一族竞态不在这里量 —— 它由 core 的
     * {@code destroy_is_terminal_even_when_a_late_sync_arrives_afterwards} 按构造确定性地钉着。
     */
    @Test
    public void closing_the_context_stops_the_health_check_thread() throws Exception {
        final CountingUpstream upstream = new CountingUpstream();
        upstream.start();
        try {
            // 同名线程在同一个 JVM 里可以有好几批(surefire 默认复用一个 fork, 别的类的 context
            // 也可能留着一条): 开上下文**之前**先拍一张照, 只把我这一批新冒出来的算到自己头上 ——
            // 数名字会把别人的残留判成"我没关掉线程", 那正是旧断言红过却说不清因的形状。
            final Set<Thread> before = healthThreads();
            runner.withPropertyValues("z.mcp.enabled=true",
                    "z.mcp.health-check-interval-seconds=1",
                    "z.mcp.servers[0].name=acme",
                    "z.mcp.servers[0].endpoint=" + upstream.endpoint(),
                    "z.mcp.servers[0].connect-timeout-millis=300",
                    "z.mcp.servers[0].read-timeout-millis=300").run(ctx -> {
                ExternalServerManager manager = ctx.getBean(ExternalServerManager.class);
                awaitHealth(manager, 5_000L);
                // 先证明这台尺数得到东西, 否则"关完之后计数没动"是一次空跑
                int live = upstream.awaitPosts(2, 8_000L);
                assertTrue("健康检查没在跑: 等满 8 秒上游只收到 " + live + " 次请求", live >= 2);
                Set<Thread> ours = healthThreads();
                ours.removeAll(before);
                assertFalse("配了正间隔就该有一条属于本次上下文的健康检查线程", ours.isEmpty());

                ctx.close();

                // 宽限期是**定长**的, 不是"等计数自己稳定下来": 无界的等待会把"摘掉 shutdownNow"
                // 那支变异从判红变成挂死 —— 第一版的 settle 循环就是这么绕了 466 秒(周期任务还在跑,
                // quiet 窗口每拍都被复位, 主线程卡在 Thread.sleep 里)。2 秒是客户端总超时
                // (300ms 连接 + 300ms 读)的三倍, 足够吸收关闭前最后一拍还在路上的那一次;
                // 之后再等 2.5 秒 = 两个多周期, 周期任务只要还活着就一定会漏出来。
                Thread.sleep(2_000L);
                int afterClose = upstream.posts();
                Thread.sleep(2_500L);
                assertEquals("context 已关, 上游却还在被周期性地问 = destroyMethod 没生效或 shutdown 没生效",
                        Integer.valueOf(afterClose), Integer.valueOf(upstream.posts()));
                for (Thread t : ours) {
                    t.join(3_000L);
                    assertFalse("destroy 之后这条健康检查线程还活着: "
                            + t.getName() + "#" + System.identityHashCode(t), t.isAlive());
                }
            });
        } finally {
            upstream.stop();
        }
    }

    private static final String HEALTH_THREAD = "z-mcp-server-health";

    /**
     * 当下活着的健康检查线程, 按**对象身份**留档({@link Thread} 没覆写 equals/hashCode, 放进 Set
     * 就是按身份比)。数名字会把同 JVM 里别的上下文的残留算到这台 manager 头上 —— 那正是旧断言红过
     * 却说不清因的形状。
     */
    private static Set<Thread> healthThreads() {
        Set<Thread> out = new HashSet<Thread>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (HEALTH_THREAD.equals(t.getName()) && t.isAlive()) out.add(t);
        }
        return out;
    }

    /**
     * 一台只数 POST 的 JDK 真上游: 每一轮同步恰好落在一次 {@code initialize} 尝试上(永远答 500,
     * 所以上游连不上、每轮重试一次握手, 但**轮与轮之间没有别的东西**在打它)。
     *
     * <p>用 {@code port 0} 让内核挑端口 —— 这条尺要的是"只有这一个进程能打到我", 写死端口做不到
     * (本仓量过一次: 端口被人抢绑时流量会被别人的回环绑定影走)。收尾沿用 core 那侧学到的形状:
     * JDK 8 的 {@code ServerImpl.stop(delay)} 有一处实测死锁, 所以 stop 跑在守护线程上、只等上限,
     * 超时打一行可 grep 的 WARN 而不判红 —— 那是 JDK 与内核的毛病, 不该决定 CI 的寿命。
     */
    private static final class CountingUpstream {
        private final AtomicInteger posts = new AtomicInteger();
        private HttpServer server;
        private ExecutorService executor;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "z-mcp-starter-test-upstream");
                    t.setDaemon(true);
                    return t;
                }
            });
            server.setExecutor(executor);
            server.createContext("/mcp", new HttpHandler() {
                @Override public void handle(HttpExchange ex) throws IOException {
                    if ("POST".equals(ex.getRequestMethod())) posts.incrementAndGet();
                    drain(ex.getRequestBody());
                    byte[] payload = ("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,"
                            + "\"message\":\"counting upstream\"}}")
                            .getBytes(Charset.forName("UTF-8"));
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(500, payload.length);
                    OutputStream out = ex.getResponseBody();
                    try {
                        out.write(payload);
                    } finally {
                        out.close();
                    }
                    ex.close();
                }
            });
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        int posts() {
            return posts.get();
        }

        /** 等到至少 {@code want} 次, 或等满上限; 返回此刻的读数(调用方拿它判"是不是空跑"). */
        int awaitPosts(int want, long maxWaitMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + maxWaitMillis;
            while (System.currentTimeMillis() < deadline) {
                int seen = posts.get();
                if (seen >= want) return seen;
                Thread.sleep(50L);
            }
            return posts.get();
        }

        void stop() {
            final HttpServer toStop = server;
            final ExecutorService pool = executor;
            server = null;
            executor = null;
            if (toStop == null) return;
            Thread stopper = new Thread("z-mcp-starter-test-upstream-stop") {
                @Override public void run() {
                    toStop.stop(0);
                    pool.shutdownNow();
                }
            };
            stopper.setDaemon(true);
            stopper.start();
            try {
                stopper.join(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (stopper.isAlive()) {
                System.err.println("WARN z-mcp-starter-test-httpserver-stop: stop(0) 未在 10000ms "
                        + "内返回, 放弃等待 (JDK8 sun.net.httpserver 收尾死锁)");
            }
        }

        private static void drain(InputStream in) throws IOException {
            byte[] chunk = new byte[2048];
            while (in.read(chunk) >= 0) { /* 只要体被读完, 内容对这条尺无意义 */ }
            in.close();
        }
    }
}
