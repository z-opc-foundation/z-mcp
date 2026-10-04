package com.zifang.z.mcp.starter.autoconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code z.mcp.expose-admin} 在真 socket 上的行为。
 *
 * <p>0.1.x 里这个开关什么都关不掉，因为没有任何 pom 依赖 z-mcp-admin，
 * {@code @ComponentScan("com.zifang.z.mcp.admin")} 扫的是一个不在 classpath 上的包。
 * 现在依赖真的加了，风险反过来：控制面的类**一定**在 classpath 上，开关是唯一的那道门，
 * 所以这道门必须在真 HTTP 请求上验，而不是在ApplicationContextRunner 里验 bean 存在。
 *
 * <p>另外钉住"控制面跟着 base-path 一起搬"：把数据面让开 {@code /mcp} 的理由（与遗留
 * z-agent-mcp-* 抢路径）对控制面同样成立，只搬一半等于没让开。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpAdminEndpointTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 这批切片用例一个字节的 SQL 都不碰。本模块 pom 里 druid-spring-boot-starter /
                // spring-boot-starter-jdbc / mysql-connector-j 都是 provided 作用域,宿主拿不到,
                // 生产上没这个问题;但 provided 在**测试**类路径上,@EnableAutoConfiguration 会去
                // 激活 DruidDataSourceAutoConfigure,而容器里没有任何数据源配置,启动期直接死在
                // "Failed to determine a suitable driver class"。症状是整类用例集体 error
                // (9 个类 52 条),而不是某一条断言红。处置与 z-report 的 EndToEndFlowTest#TestApp
                // 一致(2026-10-04)。⚠ Boot 2.7 的 @SpringBootTest 没有 exclude 属性(javap 查过
                // 2.7.18 的字节码:只有 value/properties/args/classes/webEnvironment),所以只能走属性。
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure",
                "z.mcp.enabled=true",
                "z.mcp.expose-admin=true",
                "z.mcp.base-path=/api/tenant-admin"
        })
public class ZMcpAdminEndpointTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    private static final String ADMIN = "/api/tenant-admin/mcp/admin";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Test
    public void the_switch_mounts_the_control_plane_and_reports_real_numbers() throws Exception {
        JsonNode overview = get(ADMIN + "/overview");
        assertEquals("z-mcp", overview.path("platform").asText());
        assertTrue("概览报零工具 ⇒ 要么装配没起来要么在注水",
                overview.path("toolCount").asInt() > 0);
        assertTrue(overview.path("version").asText().length() > 0);
        assertTrue("协议版本矩阵必须是数组: " + overview.path("protocol"),
                overview.path("protocol").path("supported").isArray());
    }

    @Test
    public void the_control_plane_moves_with_base_path_instead_of_staying_on_the_bare_mcp_prefix() {
        ResponseEntity<String> bare = rest.exchange("http://127.0.0.1:" + port + "/mcp/admin/overview",
                HttpMethod.GET, null, String.class);
        assertEquals("base-path 已经把数据面搬到 /api/tenant-admin 了, 控制面不该还占着 /mcp/admin",
                HttpStatus.NOT_FOUND, bare.getStatusCode());
    }

    @Test
    public void the_tools_endpoint_lists_what_the_registry_actually_holds() throws Exception {
        JsonNode tools = get(ADMIN + "/tools");
        assertTrue(tools.isArray());
        assertTrue("控制面的工具清单必须含内置工具: " + tools, tools.toString().contains("get_time"));
        boolean hasSchema = false;
        boolean labelsBuiltins = false;
        for (JsonNode tool : tools) {
            assertTrue("每条都要标归属 server: " + tool, tool.hasNonNull("server"));
            labelsBuiltins = labelsBuiltins || "(builtin)".equals(tool.path("server").asText());
            hasSchema = hasSchema || tool.path("schema").asText().contains("properties");
        }
        assertTrue("JSON Schema 必须真的带出来, 不能只给个名字", hasSchema);
        // 内置工具没有来源 server, 但控制面里也不能留 null: 运维看到的是"这个字段坏了"还是
        // "这条是本机的", 完全没法区分。DTO 里早就有 getServerDisplayName(), 用它。
        assertTrue("内置工具必须显式标成 (builtin): " + tools, labelsBuiltins);
    }

    @Test
    public void health_is_up_when_no_upstream_is_configured() throws Exception {
        JsonNode health = get(ADMIN + "/health");
        assertEquals("UP", health.path("status").asText());
        assertTrue("没有配上游就不该编造 unreachableServers: " + health,
                !health.has("unreachableServers"));
        assertTrue(get(ADMIN + "/servers/state").isArray());
    }

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> res = rest.exchange("http://127.0.0.1:" + port + path,
                HttpMethod.GET, null, String.class);
        assertEquals(path + " -> " + res.getStatusCode() + " " + res.getBody(),
                HttpStatus.OK, res.getStatusCode());
        return mapper.readTree(res.getBody());
    }
}
