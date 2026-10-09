package com.zifang.z.mcp.starter.bridge;

import com.zifang.z.agent.center.core.agent.tool.ToolRegistry;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 bridge 在 z-agent-center-core 真的在 classpath 时会按反射路径调通。
 * 本测试要求测试 classpath 上有 z-agent-center-core (= io.github.yuku123:z-agent-center-core:1.0.1)
 * —— 由 z-mcp-starter/pom.xml 的 test scope 依赖提供 (见 pom)。
 */
class McpToolRegistryBindConfigTest {

    @Test
    void bridgeReflectivelyReachesToolRegistryOnClasspath() {
        // ToolRegistry 类在 classpath (z-agent-center-core 1.0.1 test scope)
        // @ConditionalOnClass 应判定为真, 反射路径应能 Class.forName
        try {
            Class<?> trClass = Class.forName("com.zifang.z.agent.center.core.agent.tool.ToolRegistry");
            Object instance = trClass.getMethod("getInstance").invoke(null);
            assertNotNull(instance);
            assertSame(ToolRegistry.getInstance(), instance, "反射 getInstance 应与直接 import 一致");
        } catch (Exception e) {
            fail("反射调 ToolRegistry 应成功, 实测: " + e);
        }
    }

    @Test
    void toolRegistryHasExpectedMethods() {
        // 我们反射要调的三个方法必须存在且签名匹配
        Class<?> trClass = ToolRegistry.class;
        try {
            trClass.getMethod("getInstance");
            trClass.getMethod("bindMcpRegistry", McpRegistry.class);
            trClass.getMethod("syncFromMcp");
        } catch (NoSuchMethodException e) {
            fail("ToolRegistry 缺方法 (与 bridge 反射路径不匹配): " + e);
        }
    }
}
