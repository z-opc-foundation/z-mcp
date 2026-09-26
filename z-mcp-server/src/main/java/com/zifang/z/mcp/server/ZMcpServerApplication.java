package com.zifang.z.mcp.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * z-mcp 的<b>进程</b> —— 前四个模块(api/core/starter/admin)是库, 只有本模块能 {@code java -jar} 起来.
 *
 * <p>一个 fat jar 装三种角色, 由 {@code Z_MCP_ROLE}(即 {@code spring.profiles.active})选:
 * <ul>
 *   <li>{@code hub} —— 服务中心: 读 {@code z.mcp.servers[]} 把内网其它 MCP server 的工具聚合进同一张目录,
 *       外加 {@code find_tools}/{@code registry_state} 两条目录服务.</li>
 *   <li>{@code text} / {@code codec} —— 叶子服务: 只登记自己的领域工具
 *       ({@code z.mcp.builtin-tools-enabled=false}, 免得四份 {@code echo} 混进 hub 的目录).</li>
 * </ul>
 *
 * <p>刻意不放在默认包: {@code @SpringBootApplication} 在默认包会把整个 classpath 当扫描根,
 * 自动装配的条件求值会在 r2dbc 之类的 SPI 上炸 NoClassDefFoundError(本机实测).
 */
@SpringBootApplication
public class ZMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZMcpServerApplication.class, args);
    }
}
