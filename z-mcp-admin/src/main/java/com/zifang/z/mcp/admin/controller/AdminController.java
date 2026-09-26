package com.zifang.z.mcp.admin.controller;

import com.zifang.z.mcp.admin.service.AdminQueryService;
import com.zifang.z.mcp.core.security.TransportSecurityGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * z-mcp-admin 控制面 REST 端点(挂载在 {@code ${z.mcp.base-path}/mcp/admin/*},
 * 通过 z.mcp.expose-admin=true 开启).
 *
 * <p>前缀必须跟着 {@code base-path} 走: 这个字段存在的理由就是"把自己的路径让开"
 * (见 ZMcpAutoConfiguration 的 FEATURE066 注释 —— 与遗留的 z-agent-mcp-* 抢 /mcp).
 * 数据面搬到 /api/x/mcp 而控制面还钉在 /mcp/admin, 等于让开的那个名字又被占回去.
 * 默认 base-path 为空串, 因此挂载路径与 0.1.x 逐字一致.
 *
 * <p>鉴权走数据面同一把闸({@link TransportSecurityGuard}): 控制面读得到上游 endpoint、
 * {@code lastError} 和全部工具的 JSON Schema, 属于拓扑信息. 以前配了
 * {@code security.bearer-tokens} 只挡 {@code /mcp}, {@code /mcp/admin} 仍然匿名可读 ——
 * "配了锁"和"锁上了"是两件事。令牌列表为空(默认)时两扇门都照旧放行.
 */
@RestController
@RequestMapping("${z.mcp.base-path:}/mcp/admin")
public class AdminController {

    private final AdminQueryService service;
    private final TransportSecurityGuard guard;

    public AdminController(AdminQueryService service, TransportSecurityGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpServletRequest request) {
        ResponseEntity<?> denied = refuseIfViolating(request);
        return denied != null ? denied : ResponseEntity.ok(service.overview());
    }

    @GetMapping("/tools")
    public ResponseEntity<?> tools(HttpServletRequest request) {
        ResponseEntity<?> denied = refuseIfViolating(request);
        return denied != null ? denied : ResponseEntity.ok(service.listTools().stream().map(t -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("server", t.getServerDisplayName());
            m.put("schema", t.getInputSchemaJson());
            return m;
        }).collect(java.util.stream.Collectors.toList()));
    }

    @GetMapping("/servers")
    public ResponseEntity<?> servers(HttpServletRequest request) {
        ResponseEntity<?> denied = refuseIfViolating(request);
        return denied != null ? denied : ResponseEntity.ok(service.listServers().stream().map(s -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", s.getName());
            m.put("endpoint", s.getEndpoint());
            m.put("transport", s.getTransport());
            m.put("tools", s.getTools().size());
            return m;
        }).collect(java.util.stream.Collectors.toList()));
    }

    /**
     * 外部 server 的真实运行状态 —— 与 /servers 的区别是这份带连通性:
     * /servers 是"目录里现在广告了什么", 这里是"上游现在到底连没连上、为什么".
     */
    @GetMapping("/servers/state")
    public ResponseEntity<?> serverState(HttpServletRequest request) {
        ResponseEntity<?> denied = refuseIfViolating(request);
        return denied != null ? denied : ResponseEntity.ok(service.externalServers());
    }

    @GetMapping("/health")
    public ResponseEntity<?> health(HttpServletRequest request) {
        ResponseEntity<?> denied = refuseIfViolating(request);
        return denied != null ? denied : ResponseEntity.ok(service.health());
    }

    /** 违规就回对应状态码(401 带 WWW-Authenticate, 403/400 直接回), 没问题返回 null. */
    private ResponseEntity<?> refuseIfViolating(HttpServletRequest request) {
        TransportSecurityGuard.Violation v = guard.checkRequest(request);
        if (v == null) return null;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(v.httpStatus);
        if (v.wwwAuthenticate != null) builder.header(HttpHeaders.WWW_AUTHENTICATE, v.wwwAuthenticate);
        return builder.build();
    }
}
