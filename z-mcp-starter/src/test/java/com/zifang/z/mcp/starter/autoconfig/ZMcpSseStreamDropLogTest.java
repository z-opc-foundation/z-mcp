package com.zifang.z.mcp.starter.autoconfig;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 一条被写失败摘掉的 SSE 流, 必须在服务端留下一行**能归因的话**.
 *
 * <p>为什么这条用例在 z-mcp-starter 而不是 z-mcp-core: 判据要读的是日志, 而 core 的测试
 * classpath 上只有 {@code slf4j-api}、没有任何 binding(实测 {@code dependency:tree} 只有
 * {@code org.slf4j:slf4j-api:1.7.36:compile}), 挂在 NOP logger 上的 appender 永远收不到事件 ——
 * 那这条用例会以"绿"的方式什么都量不到. logback 是随 spring-boot 进到 starter 的测试域里的,
 * 所以观察点放这里; 被测对象仍是 core 的 {@link McpSessionStore}.
 *
 * <p>动因是 CI 上一条本机 0/8 复现不出的间歇红
 * ({@code ZMcpSseKeepAliveTest.heartbeats_do_not_move_the_position_a_client_resumes_from}
 * 在 jdk17 runner 上报 {@code java.io.IOException: Premature EOF} —— 拿到 200 与
 * {@code text/event-stream} 之后一个字节都没读到, 也就是**服务端**主动结束了那条流)。而当时
 * 服务端三条会掐掉客户端流的出口(广播写出失败、心跳写出失败、{@code attach()} 抛异常回 500)
 * 全部一个字都不写, 于是"客户端看到的症状"与"服务端做过的决定"之间没有任何可查的连接。
 * 这条用例钉住其中最常走的那条: 摘流必须同时留下会话 id 与失败原因.
 */
public class ZMcpSseStreamDropLogTest {

    /** 写下去就失败的一条流 —— 生产上对应"客户端已经跑掉、服务端还在往 socket 里写". */
    private static final class BrokenEmitter extends SseEmitter {
        boolean completed;

        @Override public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("wire went away");
        }

        @Override public synchronized void complete() {
            completed = true;
        }
    }

    @Test
    public void a_stream_dropped_by_a_failed_write_says_which_session_and_why() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(McpSessionStore.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        McpSessionStore sessions = new McpSessionStore(new McpProperties());
        String sessionId = "drop-log-probe";
        BrokenEmitter dead = new BrokenEmitter();
        try {
            McpSessionStore.McpSession session = sessions.create(sessionId, "2025-06-18");
            assertNotNull(session);
            session.addEmitter(dead);

            session.emit("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");

            // 这三样是"摘流"这个决定的全部后果, 分开钉: 少了 complete() 就是那条要挂到容器超时
            // 才回收的异步请求, 少了名单里的摘除就是往死流上一直写, 少了日志就是 CI 上那种
            // "客户端红了一行 Premature EOF、服务端什么都没说过".
            assertTrue("写失败的流要被 complete() 掉, 不能让它挂到容器超时", dead.completed);
            assertEquals("摘掉之后这条会话不该还挂着它", 0, session.emitterCount());

            List<String> warns = new ArrayList<String>();
            for (ILoggingEvent e : appender.list) {
                if (e.getLevel().isGreaterOrEqual(Level.WARN)) warns.add(e.getFormattedMessage());
            }
            String reason = null;
            for (String w : warns) {
                if (w.contains(sessionId) && w.contains("wire went away")) reason = w;
            }
            assertNotNull("一条被静默摘掉的流是排障时最难查的事件: 日志里既没有会话 id 也没有失败原因"
                    + "就没法把客户端的 Premature EOF 对回服务端. 实得 WARN: " + warns, reason);
            // 会话与原因之外还得说清"是摘流"这件事本身, 否则同一行日志会被读成"广播失败了".
            assertTrue("日志要说清这是摘掉了一条流: " + reason, reason.contains("stream"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
            sessions.close();
        }
    }
}
