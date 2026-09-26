package com.zifang.z.mcp.starter.autoconfig;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zifang.z.mcp.core.controller.JsonRpcController;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 把服务端在这几条流上做过的决定收到内存里, 供判红时一起打出来.
 *
 * <p>为什么需要它: CI 上反复出现 {@code java.io.IOException: Premature EOF}, 已观测两次
 * (jdk17 runner), 分别红在 {@code ZMcpSseKeepAliveTest} 和 {@code ZMcpSseStreamTest} 的
 * {@code frame()} 上 —— 同一种读法, 不同用例. 本机 8 遍 0 复现, 而红消息里只有客户端的症状:
 * 服务端是"摘掉了一条流"、"把这条会话的流全关了"还是"压根没找到这个会话", 当时没有任何线索.
 *
 * <p>两个用例类共用一份, 是因为它们必须**打成同一个样子**; 各写一份的话, 下一次读到红的
 * 人没法确定这两处给出的是同一种线索.
 *
 * <p>只在测试里用, 且 {@code stop()} 会把级别原样还回去: 这两个 logger 是 logback 全局的,
 * 忘了还就会污染同一个 JVM 里后续所有用例的日志量.
 */
final class ServerSideLogCapture {

    private static final ListAppender<ILoggingEvent> EVENTS = new ListAppender<ILoggingEvent>();

    /** 装 appender 之前这两个 logger 各自的级别, {@link #stop()} 原样还回去. */
    private static Level previousStoreLevel;
    private static Level previousControllerLevel;

    private ServerSideLogCapture() {
    }

    /**
     * 级别也得在这里钉死, 不能跟着环境的有效级别漂: 判据之一是"服务端确实留了一行话",
     * 而本项目已经有过两次"本机全绿而 CI 红"其实是量具随环境变的教训.
     */
    static void start() {
        Logger store = store();
        Logger controller = controller();
        previousStoreLevel = store.getLevel();
        previousControllerLevel = controller.getLevel();
        store.setLevel(Level.DEBUG);
        controller.setLevel(Level.DEBUG);
        EVENTS.start();
        store.addAppender(EVENTS);
        controller.addAppender(EVENTS);
    }

    /** 摘干净: 下一个用例不该读到上一个用例留下的行, 别处的用例也不该被这个 appender 拖累. */
    static void stop() {
        Logger store = store();
        Logger controller = controller();
        store.detachAppender(EVENTS);
        controller.detachAppender(EVENTS);
        store.setLevel(previousStoreLevel);
        controller.setLevel(previousControllerLevel);
        EVENTS.stop();
        EVENTS.list.clear();
    }

    /** 服务端说过的话, 按先后顺序, 只要正文(带级别的话 {@link #rendered()} 就没法逐行对). */
    static List<String> messages() {
        List<String> out = new ArrayList<String>(EVENTS.list.size());
        for (ILoggingEvent e : EVENTS.list) out.add(e.getFormattedMessage());
        return out;
    }

    /** 拼进判红消息的样子. 一行都没有时要把"没有"说明白, 别留个空括号让人以为看过了. */
    static String rendered() {
        StringBuilder out = new StringBuilder(" [服务端同期日志: ");
        if (EVENTS.list.isEmpty()) return out.append("(这一路上服务端一句话都没说)]").toString();
        for (ILoggingEvent e : EVENTS.list) {
            out.append(e.getLevel()).append(' ').append(e.getFormattedMessage()).append(" | ");
        }
        return out.append(']').toString();
    }

    /**
     * 服务端有没有同时说到过这几样(任意一行正文同时含全部 needle 即命中), 命中就返回那一行原文.
     *
     * <p>为什么按"同一行里同时出现"判, 而不是"消息里含会话 id": 实测只判 id 会假绿 ——
     * 同一会话的 "stream opened" 那行里本来就带着 id, 于是把真正的收流日志整行删掉,
     * 断言照样通过, 守卫看着有牙实际咬的是别处.
     *
     * <p>返回原文而不是 boolean, 是因为调用方还要拿这一行去核对判红消息有没有把它带出来;
     * 那样核对必须在**同一次读取**上做, 隔一句再读"最后一行"会随别的线程又写了一行而漂.
     */
    static String lineContaining(String... needles) {
        for (String m : messages()) {
            boolean all = true;
            for (String needle : needles) {
                if (!m.contains(needle)) {
                    all = false;
                    break;
                }
            }
            if (all) return m;
        }
        return null;
    }

    private static Logger store() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        return ctx.getLogger(McpSessionStore.class.getName());
    }

    private static Logger controller() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        return ctx.getLogger(JsonRpcController.class.getName());
    }
}
