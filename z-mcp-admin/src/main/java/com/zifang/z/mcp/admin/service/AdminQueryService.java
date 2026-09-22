package com.zifang.z.mcp.admin.service;

import com.zifang.z.mcp.api.dto.McpServerDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * z-mcp-admin 查询服务 — 控制面数据汇总.
 */
@Service
public class AdminQueryService {

    private final McpRegistry registry;

    public AdminQueryService(McpRegistry registry) {
        this.registry = registry;
    }

    public Map<String, Object> overview() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("toolCount", registry.toolCount());
        data.put("serverCount", registry.serverCount());
        List<McpToolDto> builtin = registry.listBuiltinTools();
        data.put("builtinToolCount", builtin.size());
        data.put("externalToolCount", registry.toolCount() - builtin.size());
        data.put("platform", "z-mcp");
        data.put("version", "0.1.0");
        return data;
    }

    public List<McpToolDto> listTools() {
        return registry.listTools();
    }

    public List<McpServerDto> listServers() {
        return registry.listServers();
    }

    public Map<String, Object> health() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("status", "UP");
        data.put("registry", "OK");
        data.put("tools", registry.toolCount());
        return data;
    }
}