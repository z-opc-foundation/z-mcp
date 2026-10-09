package com.zifang.z.mcp.starter.bridge;

import com.zifang.z.mcp.core.registry.McpRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;

/**
 * 把 z-mcp 的工具目录绑进 z-agent-center 的 ToolRegistry。
 * <p>
 * 遗留的 com.zifang:z-agent-mcp-starter 里 ToolRegistryIntegrationConfig 干的就是这件事；
 * MCP 独立成 z-mcp 之后没有对应物，绑定这一刀留在平台侧。
 * <p>
 * 两种"不在"都必须空转，不让整个应用起不来（2026-10-04 补上第二种）：
 * <ul>
 *   <li>{@code z.mcp.enabled=false} ⇒ 容器里没有 {@link McpRegistry}（下面那个 null 判断）。</li>
 *   <li>宿主没装 z-agent-center ⇒ {@code com.zifang.z.agent.center.core.agent.tool.ToolRegistry}
 *       不在类路径上。{@link ConditionalOnClass} 守一道（按类元数据判定，不加载类），
 *       类不在时整个 bean 定义都不注册；万一被其他方式强行实例化，
 *       {@link #bind()} 里 {@code Class.forName} 失败也照样空转。
 *       <b>这一条以前是崩的</b>：本类原本直接 import ToolRegistry + 静态调 {@code getInstance()}，
 *       {@code NoClassDefFoundError: com/zifang/z/agent/center/core/agent/tool/ToolRegistry}
 *       把整个上下文掀翻。pom 注释里"无 agent-center 时空转"那句话当时并不成立 ——
 *       那个 {@link ObjectProvider} 守的是 {@code McpRegistry}，不是 {@code ToolRegistry}。
 *       由 z-mcp-server（正是"没装 agent-center 的宿主"）的 22 条用例撞出来。</li>
 * </ul>
 *
 * <h2>2026-10-09 为什么改成反射</h2>
 * 原 pom 把 {@code z-agent-center-core} 钉成 {@code provided} 1.0.0-SNAPSHOT:
 * <ul>
 *   <li>SNAPSHOT 永远 404，本仓永远 publish 不出去——Maven Central 拒收 SNAPSHOT;</li>
 *   <li>{@code provided} 意味着用 z-mcp-starter 的下游也必须把 z-agent-center-core 列在
 *       自己 pom 的 DM 里，否则运行期缺类——库件不该替宿主做这件事;</li>
 *   <li>这个绑定本就是 opt-in(宿主有 agent-center 时才生效)，硬把版本号写进本仓 DM 是
 *       越权。</li>
 * </ul>
 * 改成反射: 编译期不再 import ToolRegistry，本模块零 z-agent-center 依赖;
 * 运行期按类元数据 (Class.forName) 探测,缺类时空转,行为不变。
 * 这是 SLF4J / Spring 等"门面库"的标准做法。
 */
@Component
@ConditionalOnClass(name = "com.zifang.z.agent.center.core.agent.tool.ToolRegistry")
public class McpToolRegistryBindConfig {

    private static final Logger log = LogManager.getLogger(McpToolRegistryBindConfig.class);

    private static final String TOOL_REGISTRY_CLASS = "com.zifang.z.agent.center.core.agent.tool.ToolRegistry";

    @Autowired
    private ObjectProvider<McpRegistry> mcpRegistryProvider;

    @PostConstruct
    public void bind() {
        McpRegistry registry = mcpRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.info("McpRegistry 不可用 (z.mcp.enabled=false?)，跳过 agent 工具表绑定");
            return;
        }

        try {
            Class<?> trClass = Class.forName(TOOL_REGISTRY_CLASS);
            Method getInstance = trClass.getMethod("getInstance");
            Object toolRegistry = getInstance.invoke(null);

            Method bindMcpRegistry = trClass.getMethod("bindMcpRegistry", McpRegistry.class);
            bindMcpRegistry.invoke(toolRegistry, registry);

            // 上游 server 是后台定时同步进来的，只在启动时绑一次的话，之后换目录 agent 就看不见了
            Method syncFromMcp = trClass.getMethod("syncFromMcp");
            registry.addChangeListener(McpRegistry.Change.TOOLS, new Runnable() {
                @Override public void run() {
                    try {
                        syncFromMcp.invoke(toolRegistry);
                    } catch (ReflectiveOperationException e) {
                        // listener 抛异常会进 MCP 内部的 listener 链; 这里只 log 不让一次抖动掀整次 bind
                        log.warn("ToolRegistry.syncFromMcp via reflection failed", e);
                    }
                }
            });
            log.info("ToolRegistry bound to z-mcp McpRegistry ({} tools)", Integer.valueOf(registry.toolCount()));
        } catch (ClassNotFoundException e) {
            log.info("ToolRegistry 类不在类路径上 (宿主未装 z-agent-center), 跳过绑定");
        } catch (ReflectiveOperationException e) {
            log.warn("反射调 ToolRegistry 失败 (agent-center 类路径异常?); 跳过绑定", e);
        }
    }
}
