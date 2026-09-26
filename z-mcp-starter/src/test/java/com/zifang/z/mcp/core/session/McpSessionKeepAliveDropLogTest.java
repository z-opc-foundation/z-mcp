package com.zifang.z.mcp.core.session;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zifang.z.mcp.core.properties.McpProperties;
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
 * 心跳写失败摘一条流, 后果必须与广播写失败**完全同形**.
 *
 * <p>为什么单独钉这一支: {@code McpSessionStore} 里有两个几乎逐字相同的 catch —— {@code emit()}
 * 的那个由 {@code ZMcpSseStreamDropLogTest} 钉住了, 而 {@code sendKeepAlive()} 这个在此之前
 * **一条用例都没碰过**(全仓 {@code grep sendKeepAlive} 只有它自己的声明与 {@code tick()} 的调用点)。
 * 偏偏"客户端在一条安静的流上跑掉了"是生产与 CI 上真出现过的症状: README 记的那条 jdk17 runner 上的
 * {@code Premature EOF}(拿到 200 + {@code text/event-stream} 后一字节未读到) 走的就是心跳这条路。
 *
 * <p>这个类为什么在 starter 模块里、却写着 core 的包名: 判据要读日志, 而 core 的测试 classpath 上
 * 只有 {@code slf4j-api} 没有任何 binding(与 {@code ZMcpSseStreamDropLogTest} 同一个理由),
 * 挂在 NOP logger 上的 appender 永远收不到事件 —— 那条用例会以"绿"的方式什么都量不到;
 * 同时 {@code tick()} 与 {@code sendKeepAlive()} 是包内可见的, 只有落在 core 的包里才叫得动,
 * 叫得动才不必靠"配 1 秒然后睡"来推 —— 那条类里已有的时间耦合断言在 CI 上红过一次(#37)。
 */
public class McpSessionKeepAliveDropLogTest {

    /** 写下去就失败的一条流 —— 生产上对应"客户端已经跑掉、心跳还在往 socket 里写". */
    private static final class BrokenEmitter extends SseEmitter {
        boolean completed;

        @Override public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("heartbeat hit a gone client");
        }

        @Override public synchronized void complete() {
            completed = true;
        }
    }

    @Test
    public void a_stream_dropped_by_a_failed_heartbeat_says_which_session_and_why() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(McpSessionStore.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        McpProperties props = new McpProperties();
        // 0/负数 = 不起调度器: 这一拍由下面那次 tick() 手动敲, 于是"写失败之后发生了什么"
        // 与"等多久才量到"完全分开, 用例不依赖任何时钟.
        props.getSse().setKeepAliveSeconds(0L);
        McpSessionStore sessions = new McpSessionStore(props);
        String sessionId = "keepalive-drop-probe";
        BrokenEmitter dead = new BrokenEmitter();
        try {
            McpSessionStore.McpSession session = sessions.create(sessionId, "2025-06-18");
            assertNotNull(session);
            session.addEmitter(dead);

            int sent = sessions.tick();

            assertEquals("这一拍心跳不该报成功: ", 0, sent);
            // 三样后果分开钉(与广播那支同一条理由): 少了 complete() 就是那条要挂到容器超时才回收的
            // 异步请求, 少了名单里的摘除就是往死流上一拍一拍写下去, 少了日志就是"客户端红一行
            // Premature EOF 而服务端什么都没说过".
            assertTrue("心跳写失败的流要被 complete() 掉, 不能让它挂到容器超时", dead.completed);
            assertEquals("摘掉之后这条会话不该还挂着它", 0, session.emitterCount());

            List<String> warns = new ArrayList<String>();
            for (ILoggingEvent e : appender.list) {
                if (e.getLevel().isGreaterOrEqual(Level.WARN)) warns.add(e.getFormattedMessage());
            }
            String reason = null;
            for (String w : warns) {
                if (w.contains(sessionId) && w.contains("heartbeat hit a gone client")) reason = w;
            }
            assertNotNull("一条被心跳摘掉的流留下会话 id 与失败原因了吗? 实得 WARN: " + warns, reason);
            assertTrue("日志要说清这是摘掉了一条流: " + reason, reason.contains("stream"));
            assertTrue("还得说清是**心跳**这一路摘的, 否则同一行会被读成广播失败: " + reason,
                    reason.contains("keep-alive"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
            sessions.close();
        }
    }
}
