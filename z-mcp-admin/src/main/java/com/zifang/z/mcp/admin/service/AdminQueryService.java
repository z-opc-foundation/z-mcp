package com.zifang.z.mcp.admin.service;

import com.zifang.z.mcp.api.dto.McpServerDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.core.client.ExternalServerManager;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.protocol.McpSchema;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * z-mcp-admin 查询服务 —— 控制面数据汇总.
 *
 * <p>这里报出去的每一个字段都必须来自某个真实对象: 0.1.x 的 {@code version} 是硬编码的
 * {@code "0.1.0"}, {@code health} 无条件返回 {@code "UP"}, 于是"控制面一切正常"和
 * "上游全挂了"在面板上长得一模一样.
 */
@Service
public class AdminQueryService {

    private final McpRegistry registry;
    private final McpProperties properties;
    /** 装配可能不存在(例如只引 z-mcp-core 的场景), 所以是可选依赖. */
    private final ObjectProvider<ExternalServerManager> servers;

    public AdminQueryService(McpRegistry registry, McpProperties properties,
                            ObjectProvider<ExternalServerManager> servers) {
        this.registry = registry;
        this.properties = properties;
        this.servers = servers;
    }

    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        List<McpToolDto> builtin = registry.listBuiltinTools();
        data.put("platform", "z-mcp");
        data.put("version", properties.getServerVersion());
        data.put("protocol", protocolView());
        data.put("toolCount", Integer.valueOf(registry.toolCount()));
        data.put("builtinToolCount", Integer.valueOf(builtin.size()));
        data.put("externalToolCount", Integer.valueOf(registry.toolCount() - builtin.size()));
        data.put("serverCount", Integer.valueOf(registry.serverCount()));
        data.put("resourceCount", Integer.valueOf(registry.resourceCount()));
        data.put("promptCount", Integer.valueOf(registry.promptCount()));
        data.put("externalServers", serverHealth());
        return data;
    }

    private Map<String, Object> protocolView() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("servedAs", McpSchema.LATEST_SUPPORTED);
        p.put("supported", new ArrayList<String>(McpSchema.SUPPORTED_VERSIONS));
        return p;
    }

    public List<McpToolDto> listTools() {
        return registry.listTools();
    }

    public List<McpServerDto> listServers() {
        return registry.listServers();
    }

    /** 逐个外部 server 的真实状态: 连没连上、协议版本、当前广告了几个工具、上次为什么失败. */
    public List<Map<String, Object>> externalServers() {
        ExternalServerManager manager = servers.getIfAvailable();
        return manager == null ? Collections.<Map<String, Object>>emptyList() : manager.state();
    }

    private Map<String, Object> serverHealth() {
        ExternalServerManager manager = servers.getIfAvailable();
        return manager == null ? new LinkedHashMap<String, Object>() : manager.health();
    }

    /**
     * 注册中心自己活着就是 UP; 外部 server 掉了算 DEGRADED 并在同一份响应里点名,
     * 不会假装一切正常, 也不会因为别人的故障把自己报成 DOWN.
     */
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> detail = externalServers();
        List<String> failing = new ArrayList<String>();
        for (Map<String, Object> s : detail) {
            if (!Boolean.TRUE.equals(s.get("connected"))) failing.add(String.valueOf(s.get("name")));
        }
        data.put("status", failing.isEmpty() ? "UP" : "DEGRADED");
        data.put("registry", "OK");
        data.put("tools", Integer.valueOf(registry.toolCount()));
        data.put("externalServers", serverHealth());
        if (!failing.isEmpty()) data.put("unreachableServers", failing);
        return data;
    }
}
