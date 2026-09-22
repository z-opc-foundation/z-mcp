package com.zifang.z.mcp.admin.controller;

import com.zifang.z.mcp.admin.service.AdminQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * z-mcp-admin 控制面 REST 端点(挂载在 /mcp/admin/*, 通过 z.mcp.exposeAdmin=true 开启).
 */
@RestController
@RequestMapping("/mcp/admin")
public class AdminController {

    private final AdminQueryService service;

    public AdminController(AdminQueryService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return service.listTools().stream().map(t -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("server", t.getServerName());
            m.put("schema", t.getInputSchemaJson());
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    @GetMapping("/servers")
    public List<Map<String, Object>> servers() {
        return service.listServers().stream().map(s -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", s.getName());
            m.put("endpoint", s.getEndpoint());
            m.put("transport", s.getTransport());
            m.put("tools", s.getTools().size());
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return service.health();
    }
}