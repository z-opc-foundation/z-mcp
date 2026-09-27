package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpSchema;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * stdio 传输的真子进程往返 —— 上游是 {@link StdioMcpServerFixture}, 没有 mock.
 *
 * <p>这里钉的问题全来自真实本地 server(filesystem / git 那一类): 响应之间会插通知、
 * 会插别人 id 的响应、stderr 会写爆管道、进程会说没就没。"顺序读到 id 匹配为止"的实现
 * 在第一条插进来的通知上就会把请求读丢。
 */
public class StdioJsonRpcExchangeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String fixtureClasspath() {
        try {
            URI uri = StdioMcpServerFixture.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return new File(uri).getAbsolutePath();
        } catch (Exception e) {
            throw new IllegalStateException("cannot locate test-classes for the stdio fixture", e);
        }
    }

    private static List<String> fixtureCommand() {
        return new ArrayList<String>(Arrays.asList(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-cp", fixtureClasspath(),
                StdioMcpServerFixture.class.getName()));
    }

    private static String request(long id, String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
    }

    private static StdioJsonRpcExchange open(long timeoutMillis) throws IOException {
        return new StdioJsonRpcExchange(fixtureCommand(), MAPPER, timeoutMillis);
    }

    // ------------------------------------------------------------------ 往返

    @Test
    public void a_request_is_answered_by_the_child_process() throws Exception {
        StdioJsonRpcExchange exchange = open(15_000L);
        try {
            JsonRpcExchange.Response res =
                    exchange.post(request(1, "initialize", "{}"), null);
            assertEquals(200, res.status());
            String body = res.body();
            assertTrue(body, body.contains("\"id\":1"));
            assertTrue(body, body.contains("2025-06-18"));
            assertFalse("响应里绝不能混进通知帧", body.contains("notifications/message"));
        } finally {
            exchange.close();
        }
    }

    @Test
    public void responses_are_paired_by_id_even_when_unsolicited_frames_come_first()
            throws Exception {
        StdioJsonRpcExchange exchange = open(15_000L);
        try {
            // fixture 先推一个 id=99990001 的响应, 再回我们等的这一条
            JsonRpcExchange.Response res = exchange.post(request(42, "out_of_order", null), null);
            assertTrue(res.body(), res.body().contains("\"id\":42"));
            assertFalse(res.body(), res.body().contains("unsolicited"));
            // 同一条传输还能继续用
            JsonRpcExchange.Response next = exchange.post(request(43, "ping", null), null);
            assertTrue(next.body(), next.body().contains("\"id\":43"));
        } finally {
            exchange.close();
        }
    }

    @Test
    public void initialize_then_tools_list_then_a_call_all_share_one_pipe() throws Exception {
        StdioJsonRpcExchange exchange = open(15_000L);
        try {
            assertTrue(exchange.post(request(1, "initialize", "{}"), null).body()
                    .contains("stdio-fixture"));
            assertTrue(exchange.post(request(2, "tools/list", "{}"), null).body()
                    .contains("fixture_echo"));
            JsonRpcExchange.Response call = exchange.post(
                    request(3, "tools/call",
                            "{\"name\":\"fixture_echo\",\"arguments\":{\"text\":\"hi there\"}}"),
                    null);
            assertTrue(call.body(), call.body().contains("echo:hi there"));
        } finally {
            exchange.close();
        }
    }

    @Test
    public void a_notification_frame_needs_no_answer_and_is_not_read_as_one() throws Exception {
        StdioJsonRpcExchange exchange = open(15_000L);
        try {
            JsonRpcExchange.Response note = exchange.post(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null);
            assertEquals(202, note.status());
            assertEquals("", note.body());
            // 后面那条请求仍然配得上
            assertTrue(exchange.post(request(7, "ping", null), null).body().contains("\"id\":7"));
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------ 上游反过来请求

    /**
     * 上游推一条 {@code ping} 过来. 协议规定被 ping 的一方 MUST 回一个空 result ——
     * 而这条链路里"被 ping 的一方"是我们自己(hub 是客户端).
     *
     * <p>fixture 推完就阻塞在 {@code readLine()} 上, 所以这一条不是在测"日志好看不好看":
     * 不回答, 孩子就连读带写一起停住, 我们那条 {@code push_then_wait} 自己也永远拿不到回答.
     */
    @Test
    public void a_ping_pushed_by_the_upstream_gets_an_empty_result() throws Exception {
        StdioJsonRpcExchange exchange = open(6_000L);
        try {
            JsonRpcExchange.Response res = exchange.post(
                    request(20, "push_then_wait",
                            "{\"pushId\":700001,\"pushMethod\":\"ping\"}"), null);
            String body = res.body();
            assertTrue("配错了 id: " + body, body.contains("\"id\":20"));
            assertTrue(body, body.contains("\"pushed\":\"ping\""));
            assertTrue("没把回执挂在它问的那个 id 上: " + body, body.contains("\"id\":700001"));
            assertTrue("ping 要回空 result: " + body, body.contains("\"result\":{}"));
            assertFalse("ping 不该回错误帧: " + body, body.contains("\"error\""));
        } finally {
            exchange.close();
        }
    }

    /**
     * 上游推一条我们做不了的请求. 我们**没有**广告 {@code roots}(#46 把它摘了), 但这不是一条
     * 可以拒收的帧 —— 协议没规定"没广告就不许问", 而且第三方 server 确实会照自己的一套问.
     *
     * <p>参照实现不是"没 handler 就闭嘴": TS SDK 的 {@code Protocol._onrequest} 对找不到
     * handler 的请求立刻回 {@code -32601 Method not found}. 差别是实打实的 —— 静默让对端
     * 一直等到它自己的超时, 而回一个错它当场就能继续干活(并且知道这条路走不通).
     */
    @Test
    public void a_request_we_cannot_fulfil_is_answered_method_not_found() throws Exception {
        StdioJsonRpcExchange exchange = open(6_000L);
        try {
            JsonRpcExchange.Response res = exchange.post(
                    request(21, "push_then_wait",
                            "{\"pushId\":700002,\"pushMethod\":\"roots/list\"}"), null);
            String body = res.body();
            assertTrue(body, body.contains("\"pushed\":\"roots/list\""));
            assertTrue("没回 -32601: " + body, body.contains("-32601"));
            assertTrue("没挂到它问的 id 上: " + body, body.contains("\"id\":700002"));
            assertTrue("消息里要点名是哪个方法, 否则对端只知道\"不行\"不知道\"哪儿不行\": " + body,
                    body.contains("roots/list"));
        } finally {
            exchange.close();
        }
    }

    /**
     * 反过来的一半: **不是请求的帧一条都不许回**. 上游会插通知(没 id)也会插一条没人等的响应
     * (有 id 没 method) —— 两种都不该换来我们写进行的一行.
     *
     * <p>这是一条负向断言, 它的猎物和判据同等重要: 把判据写成"凡是有 id 就答一句"的用例 1/2
     * 照样全绿, 而这条会红(fixture 会把收到的那一行原样记账带回来). 光有前两条不构成守卫.
     */
    @Test
    public void the_client_never_answers_a_frame_that_is_not_a_request() throws Exception {
        StdioJsonRpcExchange exchange = open(6_000L);
        try {
            // fixture 在这里推一条 notifications/message(有 method, 没 id)
            exchange.post(request(1, "initialize", "{}"), null);
            // fixture 在这里推一条 id=99990001 的响应(有 id, 没 method), 然后正常回答我们
            JsonRpcExchange.Response ooo = exchange.post(request(2, "out_of_order", null), null);
            assertTrue(ooo.body(), ooo.body().contains("\"id\":2"));

            JsonRpcExchange.Response report = exchange.post(request(3, "report_spurious", null), null);
            assertTrue("孩子没向我们发过请求, 却收到了一句回答: " + report.body(),
                    report.body().contains("\"spurious\":null"));
        } finally {
            exchange.close();
        }
    }

    /**
     * 管道那头**实际看到**的承诺 —— 这一格量的是传输写出去的字节, 不是塞进 params 的 Map.
     *
     * <p>两层不是重复: {@code McpRemoteClientTest} 那条在"交给传输"之前就停了, 结构上看不见传输
     * 究竟写出了什么。打过一支量这个差别: 在 stdio 的 {@code writeln} 里把
     * {@code "capabilities":{},} 抹掉 ⇒ 只有这一层红, 另一层全绿。
     * (顺带否掉我自己先前的一个假设: 把 MAPPER 换成 {@code NON_EMPTY} 并**不会**抹掉这个空对象,
     * 帧里它照样在 —— 所以这一层的价值不在防某一种序列化配置, 而在"必填字段真在帧里"这句话
     * 由管道那侧来作证。)
     */
    @Test
    public void the_local_server_sees_an_empty_yet_present_capabilities_object() throws Exception {
        StdioJsonRpcExchange exchange = open(6_000L);
        try {
            McpProperties.ServerConfig cfg = new McpProperties.ServerConfig();
            cfg.setName("stdio-fixture");
            McpRemoteClient client = new McpRemoteClient("stdio-fixture", cfg, exchange,
                    MAPPER, "z-mcp", "0.2.0");
            client.connect();

            JsonNode seen = MAPPER.readTree(
                    exchange.post(request(31, "report_initialize", null), null).body())
                    .get("result").get("initialize");
            assertTrue("孩子那侧没看见握手帧: " + seen, seen != null && seen.isObject());
            JsonNode params = seen.get("params");
            JsonNode caps = params.get("capabilities");
            assertTrue("管道里的 initialize 缺了必填的 capabilities: " + seen,
                    caps != null && caps.isObject());
            assertEquals("这一侧兑现不了的承诺一件都不该出现在帧里: " + seen, 0, caps.size());
            // 阳性对照: 同一份 params 里 clientInfo 的内层键读得见 ⇒ 上面那句"没有"是真没有,
            // 不是这套判定看不见嵌套对象(那样它同样会看不见 roots/sampling/elicitation).
            assertTrue("对照: 帧里的嵌套对象应当看得见内层键: " + params,
                    params.get("clientInfo").isObject() && params.get("clientInfo").has("name"));
            client.disconnect();
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------ 上游推来的通知

    /**
     * 上游推来的通知要有落地处. 这一格只问传输: 那一帧有没有带着自己的方法名交到监听方手上,
     * 以及**交错在一帧通知之后**的那条响应还配不配对得上 id.
     *
     * <p>不需要 sleep: reader 是单线程按行派发的, 它先处理通知再处理响应, 而 {@code post()}
     * 是在响应被派发之后才返回的 —— 所以返回时通知一定已经交出去了.
     */
    @Test
    public void a_pushed_notification_reaches_the_listener_without_breaking_the_pairing()
            throws Exception {
        StdioJsonRpcExchange exchange = open(6_000L);
        AtomicReference<String> seen = new AtomicReference<String>();
        try {
            exchange.onUpstreamNotification(new JsonRpcExchange.NotificationListener() {
                @Override public void onNotification(String method) { seen.set(method); }
            });

            JsonRpcExchange.Response res = exchange.post(request(33, "push_list_changed", null), null);
            assertTrue("配错了 id: " + res.body(), res.body().contains("\"id\":33"));
            // 判"通知没被当成响应"要看帧自己的形状: 通知有 method 没 result, 反过来才是响应.
            // (不能拿方法名当线索 —— fixture 的回执里就写着那个名字.)
            assertFalse("把上游推来的通知当成这次请求的响应交出去了: " + res.body(),
                    res.body().contains("\"method\""));
            assertTrue(res.body(), res.body().contains("\"result\""));
            assertEquals("通知没交到监听方手上", McpSchema.N_TOOLS_LIST_CHANGED, seen.get());

            // 交出去之后这条管道仍然转得动
            assertTrue(exchange.post(request(34, "ping", null), null).body().contains("\"id\":34"));
        } finally {
            exchange.close();
        }
    }

    /**
     * 监听方是**别人**注册的回调, 它抛了不能把读侧线程带走: 那条线程一死, 这条传输再也收不到
     * 任何响应, 而现场只留一行 {@code stdio reader stopped}.
     *
     * <p>所以判据不是"有没有异常日志", 而是"下一句话还答不答" —— 摘掉 {@code deliverNotification}
     * 里那圈 try/catch, 第一条断言就会红在超时上(那一帧通知先派发, 异常会把 reader 一起带走).
     */
    @Test
    public void a_notification_listener_that_throws_cannot_kill_the_reader() throws Exception {
        StdioJsonRpcExchange exchange = open(2_000L);
        try {
            exchange.onUpstreamNotification(new JsonRpcExchange.NotificationListener() {
                @Override public void onNotification(String method) {
                    throw new IllegalStateException("listener blew up on " + method);
                }
            });

            JsonRpcExchange.Response pushed = exchange.post(request(35, "push_list_changed", null), null);
            assertTrue("在监听方抛过一次之后, 连推这一帧的请求都没回答了: " + pushed.body(),
                    pushed.body().contains("\"id\":35"));
            assertTrue("监听方抛一次就把传输弄聋了: 后面的请求没有回答",
                    exchange.post(request(36, "ping", null), null).body().contains("\"id\":36"));
            assertTrue("传输自称还活着但已经答不上话, 状态视图就是假的", exchange.isAlive());
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------ 管道与死亡

    @Test
    public void a_flooded_stderr_does_not_deadlock_the_child() throws Exception {
        StdioJsonRpcExchange exchange = open(20_000L);
        try {
            long start = System.currentTimeMillis();
            JsonRpcExchange.Response res = exchange.post(request(9, "stderr_flood", null), null);
            assertTrue(res.body(), res.body().contains("\"id\":9"));
            // 6000 行日志 ≈ 350KB, 远超 64KB 管道缓冲; 没人读 stderr 就会挂到超时
            assertTrue("耗了 " + (System.currentTimeMillis() - start) + "ms",
                    System.currentTimeMillis() - start < 15_000L);
        } finally {
            exchange.close();
        }
    }

    @Test
    public void an_unanswered_request_times_out_instead_of_hanging_forever() throws Exception {
        StdioJsonRpcExchange exchange = open(600L);
        long start = System.currentTimeMillis();
        try {
            exchange.post(request(5, "never_answers", null), null);
            fail("上游不回答时必须自己收口");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("did not answer id=5"));
            long cost = System.currentTimeMillis() - start;
            assertTrue("timeout fired too late: " + cost + "ms", cost < 5_000L);
        } finally {
            exchange.close();
        }
    }

    /**
     * 等上游回答的中途被打断: 必须在这一层就收口成 IOException, 而不是把 InterruptedException
     * 漏给调用方, 也不能悄悄把中断状态吃掉。
     *
     * <p>以前只有工具超时会走到这里, 现在每一次 {@code notifications/cancelled} 都会 ——
     * 漏出去就是一个"服务端内部错误"而不是"这次取消了", 吃掉则让线程池带着中断位去跑下一个任务。
     * 中断落在 {@code await} 之前还是之中不影响这条路径(两处都在同一个 catch 里收口), 所以这里
     * 只等一个很短的固定时间让请求真的挂上去, 判定用的是"调用线程自己结束"而不是睡眠结束。
     */
    @Test
    public void an_interrupt_while_waiting_for_the_child_is_an_io_failure() throws Exception {
        final StdioJsonRpcExchange exchange = open(60_000L);   // 远大于用例寿命: 只有中断能收口
        try {
            final AtomicReference<IOException> seen = new AtomicReference<IOException>();
            final AtomicReference<Throwable> leaked = new AtomicReference<Throwable>();
            final AtomicBoolean flagRestored = new AtomicBoolean();
            Thread caller = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        exchange.post(request(11, "never_answers", null), null);
                        leaked.set(new AssertionError("上游不会回答这一条, 它不该正常返回"));
                    } catch (IOException e) {
                        seen.set(e);
                    } catch (Throwable t) {
                        leaked.set(t);
                    } finally {
                        flagRestored.set(Thread.currentThread().isInterrupted());
                    }
                }
            }, "stdio-interrupt-probe");
            caller.start();
            Thread.sleep(200L);
            caller.interrupt();
            caller.join(15_000L);

            assertFalse("调用线程还挂着: 中断没能收口这一次等待", caller.isAlive());
            assertNull("漏出去的异常: " + leaked.get(), leaked.get());
            assertNotNull("没有 IOException 收口", seen.get());
            assertTrue(seen.get().getMessage(),
                    seen.get().getMessage().contains("interrupted while waiting for stdio id=11"));
            assertTrue("中断位要还回去: 传输层把它吃掉, 上面就没法知道这个线程被中断过",
                    flagRestored.get());

            // 一次中断之后这条传输还得能用: reader / 等待槽都没被它带坏
            String answered = exchange.post(request(12, "tools/list", null), null).body();
            assertTrue(answered, answered.contains("\"tools\""));
        } finally {
            exchange.close();
        }
    }

    @Test
    public void a_child_that_vanishes_fails_the_pending_request_with_a_reason() throws Exception {
        StdioJsonRpcExchange exchange = open(6_000L);
        try {
            exchange.post(request(6, "vanish", null), null);
            fail("子进程自己退了, 请求不能永远挂着");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("did not answer id=6"));
        }
        assertTrue("进程已退, 传输必须自认为不可用", !exchange.isAlive());
        try {
            exchange.post(request(8, "ping", null), null);
            fail("closed 之后不得再写管道");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("closed"));
        }
    }

    /**
     * stdout 关了但进程还活着 —— reaper 在这一点上永远不会动, 因为只有 reader 看得见 EOF.
     * 这一条把"reader 负责收口"钉成确定性测试: 上面那条 vanish 用例的时机取决于谁来得更快.
     */
    @Test
    public void a_server_that_blinds_its_stdout_is_dead_even_while_the_process_lives()
            throws Exception {
        StdioJsonRpcExchange exchange = open(2_000L);
        try {
            try {
                exchange.post(request(11, "blind_stdout", null), null);
                fail("上游把 stdout 关了, 这个请求不可能有回答");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("did not answer id=11"));
                assertFalse("进程没退, 现场里不该出现 exit code: 收口的必须是 reader",
                        expected.getMessage().contains("exit code"));
            }
            assertTrue("stdout 到 EOF 后再也收不到响应, 传输不能还自称可用", !exchange.isAlive());
        } finally {
            exchange.close();
        }
    }

    @Test
    public void close_terminates_the_child_process() throws Exception {
        StdioJsonRpcExchange exchange = open(15_000L);
        exchange.post(request(1, "initialize", "{}"), null);
        assertTrue(exchange.isAlive());

        exchange.close();

        assertFalse(exchange.isAlive());
    }

    // ------------------------------------------------------------------ 配置

    @Test
    public void an_empty_command_is_rejected_before_anything_is_spawned() {
        try {
            new StdioJsonRpcExchange(new ArrayList<String>(), MAPPER, 1000L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("command"));
        } catch (IOException e) {
            fail("参数校验应在拉起进程之前: " + e);
        }
        try {
            new StdioJsonRpcExchange(Arrays.asList("  "), MAPPER, 1000L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        } catch (IOException e) {
            fail("参数校验应在拉起进程之前: " + e);
        }
    }

    /** 一行 stdio 配置 ⇒ 把 {@link StdioMcpServerFixture} 当真子进程接进来(走 defaultFactory). */
    private static ExternalServerManager managerFor(McpRegistry registry,
                                                    int healthCheckIntervalSeconds) {
        McpProperties properties = new McpProperties();
        properties.setHealthCheckIntervalSeconds(healthCheckIntervalSeconds);
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("local-fixture");
        config.setTransport("stdio");
        List<String> command = fixtureCommand();
        config.setCommand(command.get(0));
        config.setArgs(new ArrayList<String>(command.subList(1, command.size())));
        config.setReadTimeoutMillis(15_000);
        properties.getServers().add(config);
        return new ExternalServerManager(registry, properties, MAPPER);
    }

    /** 端到端: 一行 z.mcp.servers[] 配置 ⇒ 真子进程的工具出现在本地目录里, 并且调得动. */
    @Test
    public void the_manager_aggregates_a_real_stdio_server_end_to_end() throws Exception {
        McpRegistry registry = new McpRegistry();
        registry.registerBuiltin("get_time", "本机时间", "{\"type\":\"object\"}",
                new McpRegistry.ToolExecutor() {
                    @Override public Object execute(Map<String, Object> a) {
                        return CallToolResult.text("local-clock");
                    }
                });
        ExternalServerManager manager = managerFor(registry, 0);
        try {
            manager.syncAll();

            assertTrue("stdio 工具没聚合进来: " + registry.toolNames(),
                    registry.hasTool("fixture_echo"));
            assertTrue("撞了内置名字的 stdio 工具必须加前缀而不是覆盖",
                    registry.hasTool("local-fixture__get_time"));
            assertEquals("2025-06-18", manager.state().get(0).get("protocolVersion"));
            assertEquals(0, ((Number) manager.health().get("failed")).intValue());

            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("text", "via stdio");
            CallToolResult res = (CallToolResult) registry.call("fixture_echo", args);
            assertFalse(res.isError());
            List<?> blocks = (List<?>) res.toWire().get("content");
            assertEquals("echo:via stdio",
                    ((Map<?, ?>) blocks.get(0)).get("text"));
            // 内置的那个 get_time 仍然是本机实现, 没被子进程顶掉
            CallToolResult local = (CallToolResult) registry.call("get_time", null);
            List<?> localBlocks = (List<?>) local.toWire().get("content");
            assertEquals("local-clock", ((Map<?, ?>) localBlocks.get(0)).get("text"));
        } finally {
            manager.destroy();
        }
    }

    /**
     * 上游自己添了一件工具并推来 {@code notifications/tools/list_changed} —— 本地目录要当场跟上,
     * 而不是最长陈旧一整个健康检查周期.
     *
     * <p>这一格量的是<b>整条接线</b>: 配置 → defaultFactory 现造的真子进程 → 读侧收到那一帧 →
     * 有人把它接到接入器上 → 接入器在另一条线程上重取目录。单元层那些自造 fake 的监听方结构上
     * 看不见"注册动作压根没发生"和"重同步跑在读侧线程上把自己等死"这两类缺陷, 所以这一条不是重复.
     *
     * <p>{@code healthCheckIntervalSeconds=0} 是这条判据的全部力气: 周期清扫那条线程根本不存
     * 在, 于是后来多出来的那一件工具只可能是被推着去重取的结果.
     */
    @Test
    public void a_pushed_list_changed_grows_the_real_stdio_catalogue_without_a_sweep()
            throws Exception {
        McpRegistry registry = new McpRegistry();
        ExternalServerManager manager = managerFor(registry, 0);
        try {
            manager.syncAll();
            assertFalse("上游还没推, 目录里就已经有它后来那件工具: " + registry.toolNames(),
                    registry.hasTool("late_arrival"));

            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("text", "mutate");
            CallToolResult res = (CallToolResult) registry.call("fixture_echo", args);
            assertFalse(res.toWire().toString(), res.isError());

            // 重同步跑在 z-mcp-upstream-push 上, 只能等它自己到 —— 有上限, 不是固定 sleep
            long deadline = System.currentTimeMillis() + 15_000L;
            while (!registry.hasTool("late_arrival") && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertTrue("上游推了 list_changed 而目录里仍然只有: " + registry.toolNames(),
                    registry.hasTool("late_arrival"));
            assertEquals("重同步把这条上游弄成不健康了: " + manager.state().get(0),
                    0, ((Number) manager.health().get("failed")).intValue());
        } finally {
            manager.destroy();
        }
    }
}
