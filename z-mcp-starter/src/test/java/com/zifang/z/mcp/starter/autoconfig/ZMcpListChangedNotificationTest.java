package com.zifang.z.mcp.starter.autoconfig;

import com.zifang.z.mcp.api.dto.ContentBlock;
import com.zifang.z.mcp.api.dto.McpPromptDto;
import com.zifang.z.mcp.api.dto.McpResourceDto;
import com.zifang.z.mcp.core.protocol.McpSchema;
import com.zifang.z.mcp.core.registry.McpRegistry;
import com.zifang.z.mcp.core.session.McpSessionStore;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code listChanged: true} 的兑现处.
 *
 * <p>initialize 时服务端替三类目录各自广告了 listChanged, 意思是"这一类的清单变了我会喊你".
 * 广告出去却不喊, 客户端就会把第一份清单永久缓存下去 —— 这和 0.1.x 那个无条件回 "UP" 的
 * 健康检查是同一类谎, 只是换到了通知侧. 所以这里验的是**接线**: 注册动作真的变成流上
 * 对应那一类的通知, 而且只变成那一类.
 */
public class ZMcpListChangedNotificationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, ZMcpAutoConfiguration.class));

    @Test
    public void a_prompt_change_is_announced_as_a_prompt_change() {
        runner.withPropertyValues("z.mcp.enabled=true").run(ctx -> {
            RecordingEmitter frames = streamingSession(ctx);
            ctx.getBean(McpRegistry.class).registerPrompt(
                    new McpPromptDto("p", "d", null, args -> Collections.<Map<String, Object>>emptyList()));

            assertEquals(frames.last(), 1, frames.methods.size());
            assertNotification(frames.last(), McpSchema.N_PROMPTS_LIST_CHANGED);
            assertFalse("把 prompt 注册播报成工具变更, 客户端会去重刷一份没动的工具表",
                    frames.last().contains("tools/list_changed"));
        });
    }

    @Test
    public void a_resource_change_is_announced_as_a_resource_change() {
        runner.withPropertyValues("z.mcp.enabled=true").run(ctx -> {
            RecordingEmitter frames = streamingSession(ctx);
            ctx.getBean(McpRegistry.class).registerResource(new McpResourceDto(
                    "z-mcp://test", "test", "d", "text/plain", args -> ContentBlock.text("hi")));

            assertEquals(frames.last(), 1, frames.methods.size());
            assertNotification(frames.last(), McpSchema.N_RESOURCES_LIST_CHANGED);
        });
    }

    @Test
    public void a_tool_change_is_announced_as_a_tool_change() {
        runner.withPropertyValues("z.mcp.enabled=true").run(ctx -> {
            RecordingEmitter frames = streamingSession(ctx);
            McpRegistry registry = ctx.getBean(McpRegistry.class);
            registry.registerBuiltin("listed", "d", "{}", args -> "x");
            assertNotification(frames.last(), McpSchema.N_TOOLS_LIST_CHANGED);
            registry.unregister("listed");
            assertEquals(2, frames.methods.size());
            assertNotification(frames.last(), McpSchema.N_TOOLS_LIST_CHANGED);
        });
    }

    /**
     * 会话没建 GET 流时推通知不该出事: 协议允许服务端只在有流时推(0.1.x 里这条路径
     * 会走到 emitter 为空的分支), 而线上绝大多数客户端其实一辈子只用 POST ——
     * 广播不能把它们变成异常.
     */
    @Test
    public void broadcasting_to_a_session_without_a_stream_is_a_no_op() {
        runner.withPropertyValues("z.mcp.enabled=true").run(ctx -> {
            McpSessionStore sessions = ctx.getBean(McpSessionStore.class);
            sessions.create(sessions.newSessionId(), McpSchema.LATEST_SUPPORTED);
            McpRegistry registry = ctx.getBean(McpRegistry.class);
            registry.registerBuiltin("quiet", "d", "{}", args -> "x");
            assertEquals(1, sessions.count());
        });
    }

    /**
     * 每条通知在流上必须各带一个递增的 event id. 全都标同一个 id 时, 按 id 去重的客户端
     * 会把第二条当成第一条的重放直接丢掉 —— 于是"广告了 listChanged"又变回空话。
     */
    @Test
    public void each_notification_gets_its_own_event_id() {
        runner.withPropertyValues("z.mcp.enabled=true").run(ctx -> {
            RecordingEmitter frames = streamingSession(ctx);
            McpRegistry registry = ctx.getBean(McpRegistry.class);
            registry.registerBuiltin("one", "d", "{}", args -> "x");
            registry.registerBuiltin("two", "d", "{}", args -> "x");

            assertEquals(2, frames.methods.size());
            long first = eventId(frames.methods.get(0));
            long second = eventId(frames.methods.get(1));
            assertTrue("event id 要递增, 实测 " + first + " -> " + second, second > first);
        });
    }

    /** 开一个会话并挂上记录用的流, 返回那个能报出收到什么帧的记录器. */
    private RecordingEmitter streamingSession(org.springframework.context.ApplicationContext ctx) {
        McpSessionStore sessions = ctx.getBean(McpSessionStore.class);
        McpSessionStore.McpSession session =
                sessions.create(sessions.newSessionId(), McpSchema.LATEST_SUPPORTED);
        RecordingEmitter frames = new RecordingEmitter();
        session.markInitialized();
        session.addEmitter(frames);
        return frames;
    }

    /**
     * 通知的形状: 有 method、**没有 id**(带 id 就成了请求, 客户端会等着回包)、
     * 而且是 jsonrpc 2.0 的一条独立帧.
     */
    private static void assertNotification(String frame, String method) {
        assertTrue(frame, frame.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(frame, frame.contains("\"method\":\"" + method + "\""));
        assertFalse("通知不能带 id: " + frame, frame.contains("\"id\""));
    }

    private static long eventId(String frame) {
        int at = frame.indexOf("id:");
        assertTrue("帧里没有 event id: " + frame, at >= 0);
        int from = at + 3;
        int to = from;
        while (to < frame.length() && Character.isDigit(frame.charAt(to))) to++;
        assertTrue("event id 不是数字: " + frame, to > from);
        return Long.parseLong(frame.substring(from, to));
    }

    /** 记录流上每一帧的 data, 用来把"广告了 listChanged"和"真的推了通知"这两件事对齐. */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> methods = new ArrayList<String>();

        RecordingEmitter() {
            super(Long.valueOf(Long.MAX_VALUE));
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            Set<DataWithMediaType> parts = builder.build();
            StringBuilder data = new StringBuilder();
            for (DataWithMediaType part : parts) {
                if (part.getData() != null) data.append(part.getData());
            }
            methods.add(data.toString());
        }

        List<String> methods() { return methods; }

        String last() {
            assertTrue("流上一帧都没有: 注册动作没变成通知", !methods.isEmpty());
            return methods.get(methods.size() - 1);
        }
    }
}
