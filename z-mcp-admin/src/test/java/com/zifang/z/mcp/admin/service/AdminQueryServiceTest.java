package com.zifang.z.mcp.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.core.client.ExternalServerManager;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 控制面报出去的每一个字段都必须量得出来.
 *
 * <p>0.1.x 的 {@code overview()} 把版本硬编码成 "0.1.0"(和任何 jar 都对不上),
 * {@code health()} 无条件 "UP" —— 面板在最该说话的时候最客气. 这里把这两件事钉死.
 */
public class AdminQueryServiceTest {

    private final McpProperties properties = new McpProperties();
    private final McpRegistry registry = new McpRegistry();

    private AdminQueryService service(final ExternalServerManager manager) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        if (manager != null) bf.registerSingleton("externalServerManager", manager);
        ObjectProvider<ExternalServerManager> provider =
                bf.getBeanProvider(ExternalServerManager.class);
        return new AdminQueryService(registry, properties, provider);
    }

    private static ExternalServerManager manager(McpRegistry registry, McpProperties properties,
                                                 String serverName, String endpoint) {
        properties.setHealthCheckIntervalSeconds(0);
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName(serverName);
        config.setTransport("http");
        config.setEndpoint(endpoint);
        config.setConnectTimeoutMillis(300);
        config.setReadTimeoutMillis(300);
        properties.getServers().add(config);
        ExternalServerManager manager =
                new ExternalServerManager(registry, properties, new ObjectMapper());
        manager.syncAll();
        return manager;
    }

    private void registerBuiltin(final String name) {
        registry.registerBuiltin(name, "d", "{\"type\":\"object\"}",
                new McpRegistry.ToolExecutor() {
                    @Override public Object execute(Map<String, Object> a) {
                        return CallToolResult.text(name);
                    }
                });
    }

    // ------------------------------------------------------------------ 版本

    @Test
    public void overview_reports_the_version_that_is_actually_running() {
        properties.setServerVersion("0.2.0-test");
        Map<String, Object> overview = service(null).overview();

        assertEquals("0.2.0-test", overview.get("version"));
        assertFalse("不许再回硬编码的 0.1.0", "0.1.0".equals(overview.get("version")));
    }

    @Test
    public void overview_publishes_the_protocol_matrix_the_server_can_speak() {
        Map<?, ?> protocol = (Map<?, ?>) service(null).overview().get("protocol");

        assertEquals("2025-11-25", protocol.get("servedAs"));
        List<?> supported = (List<?>) protocol.get("supported");
        assertTrue(supported.toString(), supported.contains("2024-11-05"));
        assertTrue(supported.toString(), supported.contains("2025-06-18"));
        assertTrue(supported.toString(), supported.contains("2025-11-25"));
    }

    @Test
    public void counts_separate_builtin_from_aggregated_tools() {
        registerBuiltin("get_time");
        Map<String, Object> overview = service(null).overview();

        assertEquals(1, ((Number) overview.get("toolCount")).intValue());
        assertEquals(1, ((Number) overview.get("builtinToolCount")).intValue());
        assertEquals(0, ((Number) overview.get("externalToolCount")).intValue());
        assertEquals(0, ((Number) overview.get("serverCount")).intValue());
    }

    // ------------------------------------------------------------------ 健康

    @Test
    public void health_is_up_only_because_nothing_is_broken() {
        registerBuiltin("get_time");
        Map<String, Object> health = service(null).health();

        assertEquals("UP", health.get("status"));
        assertFalse(health.containsKey("unreachableServers"));
        assertEquals(1, ((Number) health.get("tools")).intValue());
    }

    @Test
    public void a_dead_upstream_makes_the_panel_say_degraded_and_names_it() {
        registerBuiltin("get_time");
        ExternalServerManager manager = manager(registry, properties, "acme",
                "http://127.0.0.1:1/mcp");

        Map<String, Object> health = service(manager).health();

        assertEquals("上游全掉了却报 UP 就是在骗人", "DEGRADED", health.get("status"));
        assertEquals(java.util.Collections.singletonList("acme"),
                health.get("unreachableServers"));
        Map<?, ?> servers = (Map<?, ?>) health.get("externalServers");
        assertEquals(1, ((Number) servers.get("configured")).intValue());
        assertEquals(1, ((Number) servers.get("failed")).intValue());
    }

    @Test
    public void a_broken_server_still_shows_which_endpoint_it_tried() {
        ExternalServerManager manager = manager(registry, properties, "acme",
                "http://127.0.0.1:1/mcp");

        List<Map<String, Object>> state = service(manager).externalServers();

        assertEquals(1, state.size());
        Map<String, Object> acme = state.get(0);
        assertEquals("acme", acme.get("name"));
        assertEquals("http://127.0.0.1:1/mcp", acme.get("endpoint"));
        assertEquals("http", acme.get("transport"));
        assertEquals(Boolean.FALSE, acme.get("connected"));
        assertEquals(0, ((Number) acme.get("toolCount")).intValue());
        assertNotNull("失败原因必须落在状态里, 不能只进日志", acme.get("lastError"));
        assertTrue(String.valueOf(acme.get("lastError")),
                String.valueOf(acme.get("lastError")).contains("ConnectException"));
    }

    @Test
    public void an_absent_manager_does_not_make_the_panel_lie_about_servers() {
        Map<String, Object> health = service(null).health();

        assertEquals("UP", health.get("status"));
        assertTrue(((Map<?, ?>) health.get("externalServers")).isEmpty());
        assertTrue(service(null).externalServers().isEmpty());
    }
}
