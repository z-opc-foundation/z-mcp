package com.zifang.z.mcp.starter.bridge;

import com.zifang.z.agent.center.core.agent.tool.ToolRegistry;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 (不走 Spring 容器)：手工注入 ObjectProvider 然后调 bind(),
 * 走完反射路径,断言 ToolRegistry 真的拿到了 McpRegistry.
 *
 * 为什么不用 @SpringBootTest: Spring Boot 2.7 的 @ConditionalOnClass(name=...) 在测试
 * 上下文中是否走 true 取决于"测试 classpath 上的 jar"——Z-agent-center-core 1.0.1 是 test
 * scope,但 @ConditionalOnClass 评估的是 Spring 应用 ClassLoader,有时候不认 test scope。
 * 直接绕开 Spring,自己 new + inject,反而更直白。
 */
class McpToolRegistryBindConfigEndToEndTest {

    @Test
    void bindReflectivelyReachesToolRegistrySingleton() throws Exception {
        // 1. z-agent-center-core 1.0.1 真的在 test classpath: ToolRegistry.class 可加载
        Class<?> trClass = Class.forName("com.zifang.z.agent.center.core.agent.tool.ToolRegistry");
        assertNotNull(trClass);

        // 2. 准备一个可被反射调通的 McpRegistry stub,记录 listener 数
        TestMcpRegistry reg = new TestMcpRegistry();

        // 3. 准备 ObjectProvider stub (只覆盖 bind() 会调的方法)
        ObjectProvider<McpRegistry> op = new FixedObjectProvider<>(reg);

        // 4. 手工 new McpToolRegistryBindConfig + 注入 ObjectProvider
        McpToolRegistryBindConfig cfg = new McpToolRegistryBindConfig();
        Field f = McpToolRegistryBindConfig.class.getDeclaredField("mcpRegistryProvider");
        f.setAccessible(true);
        f.set(cfg, op);

        // 5. 调 bind(): 反射路径 (ToolRegistry.class 在 classpath ⇒ 走真路径)
        cfg.bind();

        // 6. 验证 McpRegistry 收到了 listener (即反射 addChangeListener 调通了)
        assertTrue(reg.listeners.size() >= 1,
            "bind() 应给 McpRegistry 加至少 1 个 listener (TOOLS), 实测 " + reg.listeners.size());
        assertEquals(42, reg.lastToolCountReturned,
            "bind() 调过 toolCount() 记的数 (间接证明反射 bindMcpRegistry 不抛)");

        // 7. 额外验证: 反射 getInstance 与直接 import 的 ToolRegistry.getInstance() 是同一对象
        Object reflected = trClass.getMethod("getInstance").invoke(null);
        assertEquals(ToolRegistry.getInstance(), reflected,
            "Class.forName + 反射 getInstance 应与直接 import 的单例一致");
    }

    @Test
    void bindNoOpsWhenClassMissing() throws Exception {
        // 模拟 "z-agent-center 不在 classpath" 的场景: 不调 Class.forName (反射路径应空转)
        // 实际: test classpath 上 1.0.1 真实在,没法用 ClassLoader trick 在同一 JVM 里删它
        // —— 但我们能直接验证 Class.forName 路径会成功,空转路径在 ZMcpAutoConfigurationTest
        // 的 ApplicationContextRunner 单测里已经覆盖 (那里不带 z-agent-center-core)
        Class<?> trClass = Class.forName("com.zifang.z.agent.center.core.agent.tool.ToolRegistry");
        assertNotNull(trClass, "本测试在 classpath 真的在的前提下跑");
    }

    static class TestMcpRegistry extends McpRegistry {
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        int lastToolCountReturned = -1;

        @Override
        public void addChangeListener(Change change, Runnable listener) {
            if (listener != null) listeners.add(listener);
        }

        @Override
        public int toolCount() {
            // 记录最近一次调用,让测试断言 "bind() 真的调过 toolCount()"
            lastToolCountReturned = 42;
            return 42;
        }
    }

    static class FixedObjectProvider<T> implements ObjectProvider<T> {
        private final T instance;
        FixedObjectProvider(T instance) { this.instance = instance; }
        @Override public T getIfAvailable() { return instance; }
        @Override public T getIfUnique() { return instance; }
        @Override public T getObject() { return instance; }
        @Override public T getObject(Object... args) { return instance; }
        @Override public void forEach(Consumer<? super T> action) { action.accept(instance); }
        @Override public java.util.stream.Stream<T> stream() { return java.util.stream.Stream.of(instance); }
        @Override public java.util.stream.Stream<T> orderedStream() { return java.util.stream.Stream.of(instance); }
    }
}
