package com.zifang.z.mcp.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.exception.McpException;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code notifications/cancelled} 到底取消了什么.
 *
 * <p>0.1.x 到 0.2.0 这一段, 这条通知一直是"202 收掉、然后什么都不做" —— 于是"支持取消"这个说法
 * 的真实内容是: 客户端按了停止键, 服务端继续把那次工具调用跑完、并且把结果当成功发回去。
 * 协议 §Cancellation 给接收方的三条(停止处理、释放资源、不发响应)一条都没兑现。
 *
 * <p>这里量的形状: 取消必须真的打断正在跑的那一次(而不只是"记个日志"), 但**只**打断它该打断的那一条
 * —— 请求 id 是客户端随手挑的小整数, 一张全局表等于任何会话都能凭 {@code requestId: 1}
 * 停掉别人的工具调用。规范没有给"取消已完成/不存在/属于别人"规定任何错误, 那一层是 race,
 * 只能静默; 而"静默"要带猎物才成立, 所以下面每条负向断言背后都真挂着一条在飞的请求。
 */
public class McpCancellationTest {

    /** 一切"等它发生"的上限: 撞到就说明实现没有兑现承诺, 直接判红而不是白等. */
    private static final long SETTLE_MS = 5_000L;

    private static final String CANCELLED = "notifications/cancelled";

    private final ObjectMapper mapper = new ObjectMapper();
    private McpProperties properties;
    private McpRegistry registry;
    private McpProtocolHandler handler;
    private McpSessionStore sessions;

    @Before
    public void setUp() {
        properties = new McpProperties();
        properties.setServerVersion("9.9.9-test");
        registry = new McpRegistry();
        sessions = new McpSessionStore(properties);
        handler = new McpProtocolHandler(registry, properties, mapper);
    }

    // ------------------------------------------------------------------ 用例

    /**
     * 取消一条正在跑的工具调用: 中断要真的落到工具线程上, 而那次请求给出的必须是"已取消"
     * 而不是迟到三十秒的成功结果。
     */
    @Test
    public void a_cancelled_tool_call_never_reports_a_result() throws Exception {
        final Gate gate = new Gate();
        slowTool("gated", gate);
        McpProtocolHandler.RequestContext live = liveSession();

        Running request = calls("gated", live, "7");
        assertTrue("猎物没在飞: 工具一次都没开始跑", gate.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));
        assertEquals("在飞表里就该挂着这一条", 1, live.session.inFlightCount());

        McpProtocolHandler.Reply ack = handler.handle(
                notify(CANCELLED, "{\"requestId\":7,\"reason\":\"用户按了停止\"}"), ctxFor(live.session));
        assertTrue("取消是一条通知, 不得有响应体", ack.notification);
        assertNull(ack.envelope);

        Map<String, Object> wire = errorOf(finish(request));
        assertEquals("取消掉的请求不能报成功: " + wire,
                Integer.valueOf(McpException.REQUEST_CANCELLED), wire.get("code"));
        assertTrue("客户端给的理由要跟着回来: " + wire,
                String.valueOf(wire.get("message")).contains("用户按了停止"));
        awaitInterrupted(gate);
        assertTrue("中断要真的送到工具线程, 而不是等它自己跑完", gate.interrupted);
        assertEquals("跑完的在飞登记必须注销掉", 0, live.session.inFlightCount());
    }

    /**
     * 取消比"工具真的开跑"更早到: 这一次任务还只在队列里. 如果 {@code InFlight} 只在拿到
     * {@code Future} 之后才认取消, 这条请求就会照常把工具跑一遍 —— 而那正是"已提交、未登记"
     * 这段窗口的形状。
     */
    @Test
    public void a_cancellation_that_arrives_before_the_task_starts_still_kills_it() throws Exception {
        McpProtocolHandler.RequestContext live = liveSession();
        McpSessionStore.McpSession.InFlight in = live.session.beginRequest("n:7");
        final AtomicBoolean ran = new AtomicBoolean();
        FutureTask<Object> task = new FutureTask<Object>(new Runnable() {
            @Override public void run() { ran.set(true); }
        }, new Object());

        in.attach(task);
        assertTrue(live.session.cancelRequest("n:7", "太早了"));
        task.run();                                    // 线程现在才开始跑这一格

        assertTrue(in.isCancelled());
        assertTrue(task.isCancelled());
        assertFalse("取消先落地, 工具一次都不该跑", ran.get());
    }

    /**
     * 取消一个不存在 / 形状不对 / 已经答完的 id: 只能是"什么都不发生", 且**不能**碰那条真的在飞的。
     * 猎物就是那条在飞的请求 —— 少一句这种断言, "打空不报错"和"闸坏了谁都打不断"共用同一个绿。
     */
    @Test
    public void a_cancel_that_hits_nothing_leaves_the_real_request_alone() throws Exception {
        final Gate gate = new Gate();
        slowTool("gated", gate);
        McpProtocolHandler.RequestContext live = liveSession();
        Running request = calls("gated", live, "5");
        assertTrue(gate.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));

        String[] miss = {
                "{\"requestId\":777}",                                     // 没有这个 id
                "{}",                                                      // 连 requestId 都没有
                "{\"requestId\":null}",
                "{\"requestId\":{\"a\":1}}",                               // 不是合法的 RequestId
                "{\"requestId\":\"5\",\"reason\":\"同值不同型\"}"            // 见下面那条专门的用例
        };
        for (String params : miss) {
            McpProtocolHandler.Reply ack = handler.handle(notify(CANCELLED, params), ctxFor(live.session));
            assertTrue("通知永远不该有响应体: " + params, ack.notification);
            assertNull("取消打空也不该回错误: " + params, ack.envelope);
        }
        assertEquals("四条打空加一条打错型, 都不该动到在飞那一条", 1, live.session.inFlightCount());

        gate.release.countDown();
        Map<String, Object> result = resultOf(finish(request));
        assertEquals("猎物必须照常答完: " + result, "late", firstText(result));
        assertFalse("它一次都没被打断", gate.interrupted);
    }

    /**
     * 请求 id 的**类型**也参与寻址: {@code 5} 与 {@code "5"} 是两个不同的请求.
     * 官方 TypeScript SDK 用 Map 挂在飞请求, 键天然带类型; 这里如果只按 {@code asText()} 归一,
     * 一个发 {@code requestId: 5} 的客户端就能取消掉另一条发 {@code requestId: "5"} 的请求。
     */
    @Test
    public void numeric_and_textual_ids_are_different_requests() throws Exception {
        final Gate textual = new Gate();
        slowTool("gated_text", textual);
        McpProtocolHandler.RequestContext live = liveSession();
        Running stringId = calls("gated_text", live, "\"5\"");   // 在飞的是**字符串** "5"
        assertTrue("猎物没在飞", textual.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));

        handler.handle(notify(CANCELLED, "{\"requestId\":5}"), ctxFor(live.session));
        assertFalse("数字 5 打不动字符串 \"5\"", textual.interrupted);
        assertEquals(1, live.session.inFlightCount());

        textual.release.countDown();
        assertEquals("没命中的取消不许影响那条请求", "late", firstText(resultOf(finish(stringId))));
        assertEquals(0, live.session.inFlightCount());

        // 换一枚 gate: 复用上面那颗的话, started 已经是数尽的闩, await 会立刻返回,
        // 于是"数字命中"这一半其实量的是一个还没开跑的请求。
        final Gate numeric = new Gate();
        slowTool("gated_num", numeric);
        Running byNumber = calls("gated_num", live, "5");
        assertTrue("猎物没在飞", numeric.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));

        handler.handle(notify(CANCELLED, "{\"requestId\":5}"), ctxFor(live.session));
        Map<String, Object> wire = errorOf(finish(byNumber));
        assertEquals("同型的数字 id 必须命中: " + wire,
                Integer.valueOf(McpException.REQUEST_CANCELLED), wire.get("code"));
        awaitInterrupted(numeric);
        assertTrue("命中就要真的中断", numeric.interrupted);
    }

    /** 别的会话拿同一个 id 来取消, 打不动. */
    @Test
    public void cancellation_does_not_cross_sessions() throws Exception {
        final Gate mine = new Gate();
        slowTool("gated", mine);
        McpProtocolHandler.RequestContext live = liveSession();
        McpProtocolHandler.RequestContext stranger = liveSession();

        Running request = calls("gated", live, "1");
        assertTrue(mine.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));

        McpProtocolHandler.Reply ack = handler.handle(
                notify(CANCELLED, "{\"requestId\":1}"), ctxFor(stranger.session));
        assertNull(ack.envelope);
        assertFalse("别的会话的取消不该送到这条工具线程", mine.interrupted);
        assertEquals(0, stranger.session.inFlightCount());
        assertEquals(1, live.session.inFlightCount());

        mine.release.countDown();
        finish(request);
        assertFalse("猎物必须照常答完", mine.interrupted);
    }

    /**
     * 取消来得比答案晚: 协议要求两侧都要受得住这个 race.
     * 这条钉的是"服务端不因为一条迟到的取消而二次作答、也不抛".
     */
    @Test
    public void a_cancel_that_arrives_after_the_answer_changes_nothing() throws Exception {
        final Gate gate = new Gate();
        slowTool("gated", gate);
        McpProtocolHandler.RequestContext live = liveSession();
        Running request = calls("gated", live, "9");
        assertTrue(gate.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));
        gate.release.countDown();
        Map<String, Object> answered = resultOf(finish(request));
        assertEquals("答完的那一条要给出正常结果: " + answered, "late", firstText(answered));

        McpProtocolHandler.Reply late = handler.handle(
                notify(CANCELLED, "{\"requestId\":9}"), ctxFor(live.session));
        assertTrue(late.notification);
        assertNull("迟到的取消不许再产生任何响应", late.envelope);
        assertEquals("答完就该从在飞表里摘掉", 0, live.session.inFlightCount());
    }

    /**
     * {@code tool-timeout-seconds=0} 时工具是在调用线程上直接跑的, 没有人能中断它.
     * 这一条钉住这条边界**剩下的那一半**: 中断不了可以, 但绝不能因此假装什么都没发生、
     * 把结果当成功发回去。
     */
    @Test
    public void a_tool_running_on_the_caller_thread_still_admits_it_was_cancelled() throws Exception {
        properties.setToolTimeoutSeconds(0);
        final Gate gate = new Gate();
        slowTool("gated", gate);
        McpProtocolHandler.RequestContext live = liveSession();

        Running request = calls("gated", live, "3");
        assertTrue(gate.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));
        handler.handle(notify(CANCELLED, "{\"requestId\":3,\"reason\":\"算了\"}"), ctxFor(live.session));
        gate.release.countDown();                       // 它不会被中断, 只能自己走出来

        Map<String, Object> wire = errorOf(finish(request));
        assertEquals("没有线程池也要承认这条被取消过: " + wire,
                Integer.valueOf(McpException.REQUEST_CANCELLED), wire.get("code"));
        assertTrue("工具确实跑完了(这一档中断不了任何人): " + gate.finishedWithoutInterrupt,
                gate.finishedWithoutInterrupt);
        assertEquals(0, live.session.inFlightCount());
    }

    /**
     * 会话都不存在时(无状态模式), 一条取消通知找不到任何可寻址的东西.
     * 它必须是安静地接受, 而不是 NPE —— 协议对通知只给了"接受"这一个出口。
     */
    @Test
    public void a_cancel_on_a_stateless_exchange_is_accepted_and_does_nothing() throws Exception {
        McpProtocolHandler.RequestContext headless = new McpProtocolHandler.RequestContext();
        headless.protocolVersion = McpSchema.LATEST_SUPPORTED;
        McpProtocolHandler.Reply ack = handler.handle(
                notify(CANCELLED, "{\"requestId\":1}"), headless);
        assertTrue(ack.notification);
        assertNull(ack.envelope);
    }

    /**
     * 每一轮过后在飞表都得是空的 —— 这张表按会话活着, 漏一次注销就是一个永远取消不掉的槽位,
     * 以及一个能被同 id 撞到的错位。
     */
    @Test
    public void the_in_flight_table_is_empty_after_requests_that_failed_or_hung() throws Exception {
        registry.registerBuiltin("gated_boom", "throws", "{\"type\":\"object\",\"properties\":{}}",
                args -> { throw new IllegalStateException("炸在半路"); });
        McpProtocolHandler.RequestContext live = liveSession();
        Map<String, Object> result = resultOf(finish(calls("gated_boom", live, "11")));
        assertEquals("执行失败仍按 isError 回, 不是协议错误: " + result,
                Boolean.TRUE, result.get("isError"));
        assertEquals("抛异常的这一条也要注销登记", 0, live.session.inFlightCount());

        final Gate gate = new Gate();
        slowTool("gated_hang", gate);
        Running hanging = calls("gated_hang", live, "12");
        assertTrue(gate.started.await(SETTLE_MS, TimeUnit.MILLISECONDS));
        gate.release.countDown();
        finish(hanging);
        assertEquals(0, live.session.inFlightCount());
    }

    // ------------------------------------------------------------------ 量具

    /** 一个"到了这里就停下"的工具体: {@code started} 由工具自己数, {@code release} 由测试按. */
    private static final class Gate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean interrupted;
        volatile boolean finishedWithoutInterrupt;

        void enter() throws InterruptedException {
            started.countDown();
            try {
                if (!release.await(SETTLE_MS, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("工具体等放行等超时了");
                }
                finishedWithoutInterrupt = true;
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            }
        }
    }

    /** 注册一个真的会挂住的工具: 没有它, "取消打不断任何东西"和"取消打空"长得一模一样. */
    private void slowTool(String name, final Gate gate) {
        registry.registerBuiltin(name, "blocks until released",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> {
                    gate.enter();
                    return "late";
                });
    }

    /**
     * 等一个由工具体自己置位的中断标志.
     *
     * <p>中断是**投递**给另一条线程的, 不是同步发生的: {@code future.cancel(true)} 一返回, 等回执的
     * 那条线程就能拿到 {@code CancellationException}, 而工具线程可能还没从 {@code await()} 里醒过来 ——
     * 于是"答案已经出去了、标志还没落下"这段窗口里直接 {@code assertTrue} 会在机器忙的时候偶发红
     * (GitHub runner 上量到过一次 0.011 秒的红)。这里换成有上限的等待, 判据本身不变:
     * 中断始终没送到就等满 {@code SETTLE_MS} 然后照样红 —— 摘成 {@code cancel(false)} 的注入探针
     * 走的正是这条路。
     */
    private static void awaitInterrupted(Gate gate) throws InterruptedException {
        long deadline = System.currentTimeMillis() + SETTLE_MS;
        while (!gate.interrupted && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    /** 一次在另一条线程上跑的 {@code tools/call}: 客户端发完请求就去发取消了. */
    private final class Running {
        final CountDownLatch finished = new CountDownLatch(1);
        final Thread thread;
        volatile McpProtocolHandler.Reply reply;
        volatile Throwable error;

        Running(final String tool, final McpProtocolHandler.RequestContext ctx, final String idJson) {
            final JsonNode request = call("tools/call",
                    "{\"name\":\"" + tool + "\",\"arguments\":{}}", idJson);
            this.thread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        reply = handler.handle(request, ctx);
                    } catch (Throwable t) {
                        error = t;
                    } finally {
                        finished.countDown();
                    }
                }
            }, "cancel-probe-request-" + idJson);
            this.thread.setDaemon(true);
        }
    }

    private Running calls(String tool, McpProtocolHandler.RequestContext ctx, String idJson) {
        Running running = new Running(tool, ctx, idJson);
        running.thread.start();
        return running;
    }

    private McpProtocolHandler.RequestContext liveSession() {
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        ctx.session = sessions.create(sessions.newSessionId(), McpSchema.LATEST_SUPPORTED);
        ctx.session.markInitialized();
        ctx.protocolVersion = McpSchema.LATEST_SUPPORTED;
        return ctx;
    }

    /** 同一个会话的第二条"接线": 真客户端就是从另一条 HTTP 请求上投取消的. */
    private McpProtocolHandler.RequestContext ctxFor(McpSessionStore.McpSession session) {
        McpProtocolHandler.RequestContext ctx = new McpProtocolHandler.RequestContext();
        ctx.session = session;
        ctx.protocolVersion = McpSchema.LATEST_SUPPORTED;
        return ctx;
    }

    private JsonNode notify(String method, String paramsJson) throws Exception {
        return mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}");
    }

    private McpProtocolHandler.Reply finish(Running running) throws Exception {
        assertTrue("请求线程 " + SETTLE_MS + " 毫秒内没答完, 取消没有真的收口",
                running.finished.await(SETTLE_MS, TimeUnit.MILLISECONDS));
        if (running.error != null) throw new AssertionError(running.error);
        return running.reply;
    }

    private JsonNode call(String method, String paramsJson, String idJson) {
        try {
            return mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"method\":\"" + method
                    + "\"" + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wireMap(Object value) {
        return value == null ? null : mapper.convertValue(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultOf(McpProtocolHandler.Reply reply) {
        Map<String, Object> wire = wireMap(reply.envelope.toWire());
        assertNull("意外的 JSON-RPC 错误: " + wire.get("error"), wire.get("error"));
        return (Map<String, Object>) wire.get("result");
    }

    /** {@code content} 是协议要求的类型化块数组, 取第一个文本块当"这条答了什么". */
    @SuppressWarnings("unchecked")
    private static Object firstText(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertTrue("结果里没有 content 块: " + result, content != null && !content.isEmpty());
        return content.get(0).get("text");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorOf(McpProtocolHandler.Reply reply) {
        Map<String, Object> wire = wireMap(reply.envelope.toWire());
        assertNull("意外的成功回执: " + wire.get("result"), wire.get("result"));
        Map<String, Object> error = (Map<String, Object>) wire.get("error");
        assertTrue("错误包络呢: " + wire, error != null);
        return error;
    }
}
