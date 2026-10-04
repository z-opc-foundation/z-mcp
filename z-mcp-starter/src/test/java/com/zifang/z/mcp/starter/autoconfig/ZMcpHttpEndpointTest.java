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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真 socket 上的 Streamable HTTP 端点 —— 起真的 Tomcat，发真的 HTTP 请求。
 *
 * <p>core 里那批 {@code MockHttpServletRequest} 用例能证明"控制器见到某个头就回某个状态码"，
 * 但有两件事只有真容器能证明，而它们恰恰是下游会踩的：
 * <ol>
 *   <li>{@code z.mcp.base-path} 到底把 URL 挂到了哪里。这个字段没有任何 getter 调用点，
 *       是 Spring 自己绑定到 {@code @RequestMapping} 占位符上的 ——
 *       {@code ApplicationContextRunner} 只看得到 bean 在不在，看不到请求进不来。</li>
 *   <li>Boot 2 靠 {@code META-INF/spring.factories} 发现装配。这里没有任何 {@code @Import}，
 *       整条链路能不能起来全押在发现文件上，文件写错这里就全红。</li>
 * </ol>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpHttpEndpointTest.App.class,
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
                "z.mcp.base-path=/api/tenant-demo",
                "z.mcp.server-name=starter-e2e"
        })
public class ZMcpHttpEndpointTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    private static final String BASE = "/api/tenant-demo";
    private static final String MCP = BASE + "/mcp";
    private static final String SESSION = "Mcp-Session-Id";
    private static final String PROTOCOL = "MCP-Protocol-Version";
    /** 协议要求客户端 MUST 同时可收 application/json 与 text/event-stream. */
    private static final String BOTH = "application/json, text/event-stream";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    // ------------------------------------------------------------------ 挂载位置

    @Test
    public void the_endpoint_lives_under_the_configured_base_path() throws Exception {
        ResponseEntity<String> res = post(MCP, initialize(1), BOTH, null, null);
        assertEquals("initialize 应该打在 " + MCP + " 上: " + res.getBody(),
                HttpStatus.OK, res.getStatusCode());
        JsonNode answer = mapper.readTree(res.getBody());
        assertEquals("2025-06-18", answer.path("result").path("protocolVersion").asText());
        assertEquals("starter-e2e", answer.path("result").path("serverInfo").path("name").asText());
        assertNotNull("200 却不发会话 id, 客户端没有下一条请求可用的东西",
                res.getHeaders().getFirst(SESSION));
    }

    @Test
    public void the_unprefixed_path_is_not_mounted() {
        ResponseEntity<String> res = post("/mcp", initialize(2), BOTH, null, null);
        assertEquals("base-path 生效后 /mcp 不该还有 handler(否则这个配置字段等于没读)",
                HttpStatus.NOT_FOUND, res.getStatusCode());
        ResponseEntity<String> probe = exchange(HttpMethod.GET, "/mcp/info", null, null, null);
        assertEquals(HttpStatus.NOT_FOUND, probe.getStatusCode());
    }

    @Test
    public void the_liveness_probe_is_also_relocated_and_reports_the_real_catalog() throws Exception {
        ResponseEntity<String> res = exchange(HttpMethod.GET, BASE + "/mcp/info", BOTH, null, null);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        JsonNode info = mapper.readTree(res.getBody());
        assertEquals("streamable-http", info.path("transport").asText());
        assertTrue("探针报的工具数必须是注册表里的真数", info.path("tools").asInt() > 0);
    }

    // ------------------------------------------------------------------ 会话生命周期

    @Test
    public void a_request_without_a_session_id_is_rejected() {
        ResponseEntity<String> res = post(MCP, request(3, "tools/list", "{}"), BOTH, null, null);
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertTrue("400 要说清缺的是哪个头: " + res.getBody(),
                res.getBody().contains(SESSION));
    }

    /**
     * 415 这一维要有真容器那层: 单测里的 Content-Type 是我们自己塞进 MockHttpServletRequest 的,
     * 只有走过 servlet 栈才知道客户端实际送出的那个头判不判得掉.
     *
     * <p>四格是两对对照, 每对只差 Content-Type:
     * ① {@code initialize} 是本来会 200 的一条(它不需要会话), 换 text/plain 变 415 ⇒ 闸长在
     * 会话闸之前, 而且握手那一步也不例外; ② {@code tools/list} 缺会话 id 本来就该 400,
     * 换 text/plain 读出 415 ⇒ 两道闸都活着且顺序对. 只测 415 的话"永远 415"也能过.
     */
    @Test
    public void non_json_content_type_is_refused_by_the_real_container() {
        ResponseEntity<String> plainInit = postWithContentType("text/plain", initialize(11));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, plainInit.getStatusCode());
        assertTrue("415 要指出该带什么: " + plainInit.getBody(),
                plainInit.getBody().contains("application/json"));
        assertEquals("同一形体 application/json 必须仍然 200(否则这道闸是在挡客户端正常用法)",
                HttpStatus.OK, postWithContentType("application/json", initialize(12)).getStatusCode());

        String listNoSession = request(13, "tools/list", "{}");
        assertEquals(HttpStatus.BAD_REQUEST,
                postWithContentType("application/json", listNoSession).getStatusCode());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                postWithContentType("text/plain", listNoSession).getStatusCode());
    }

    @Test
    public void a_session_the_server_does_not_know_is_a_404_so_the_client_reinitializes() {
        ResponseEntity<String> res = post(MCP, request(4, "tools/list", "{}"), BOTH,
                "never-minted", null);
        assertEquals("不认识/已过期的会话必须是 404(客户端据此重新 initialize), 不是 400",
                HttpStatus.NOT_FOUND, res.getStatusCode());
        // 光看 404 不够: 路径没挂上也是 404. 必须验到"服务端确实读到了这个头并拒绝它".
        assertTrue("404 得是会话拒绝, 不是路由没命中: " + res.getBody(),
                res.getBody().contains("unknown or expired session"));
    }

    @Test
    public void initialize_must_not_carry_a_session_id() {
        ResponseEntity<String> res = post(MCP, initialize(5), BOTH, sessionId(), null);
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertTrue(res.getBody().contains("must not carry"));
    }

    @Test
    public void one_session_drives_tools_list_and_a_real_call() throws Exception {
        String session = sessionId();

        JsonNode listed = mapper.readTree(
                post(MCP, request(6, "tools/list", "{}"), BOTH, session, null).getBody());
        assertTrue("tools/list 里必须有内置工具: " + listed,
                listed.path("result").path("tools").toString().contains("get_time"));

        JsonNode called = mapper.readTree(post(MCP, request(7, "tools/call",
                "{\"name\":\"get_time\",\"arguments\":{}}"), BOTH, session, null).getBody());
        assertEquals(called.toString(), 7, called.path("id").asInt());
        assertTrue("工具结果必须是 content 块数组: " + called,
                called.path("result").path("content").isArray());
        assertTrue(called.path("result").path("content").get(0).path("text").asText().length() > 0);
        assertFalse("工具成功时不能带 isError=true", called.path("result").path("isError").asBoolean());
    }

    @Test
    public void a_protocol_version_header_that_disagrees_with_the_negotiation_is_rejected() {
        ResponseEntity<String> res = post(MCP, request(8, "tools/list", "{}"), BOTH,
                sessionId(), "2025-03-26");
        assertEquals("会话里谈定 2025-06-18, 请求头却写 2025-03-26 ⇒ 不能装作什么都没发生",
                HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertTrue(res.getBody().contains("2025-03-26"));
    }

    @Test
    public void a_half_handshake_gets_no_catalog() {
        // 只做 initialize、不发 notifications/initialized 的客户端是真实存在的(半截握手).
        String half = post(MCP, initialize(11), BOTH, null, null).getHeaders().getFirst(SESSION);
        assertNotNull(half);
        ResponseEntity<String> res = post(MCP, request(12, "tools/list", "{}"), BOTH, half, null);
        assertEquals("JSON-RPC 层拒绝 ⇒ 传输层仍是 200", HttpStatus.OK, res.getStatusCode());
        assertTrue("半截握手不该拿到工具目录: " + res.getBody(),
                res.getBody().contains("notifications/initialized"));
    }

    @Test
    public void an_accept_header_without_event_stream_is_406_and_the_body_is_still_json() {
        ResponseEntity<String> res = post(MCP, request(9, "tools/list", "{}"),
                "application/json", sessionId(), null);
        assertEquals(HttpStatus.NOT_ACCEPTABLE, res.getStatusCode());
        assertTrue("406 也要给可读的 JSON-RPC error: " + res.getBody(),
                res.getBody().contains("text/event-stream"));
    }

    @Test
    public void a_notification_is_accepted_with_202_and_no_body() {
        ResponseEntity<String> res = post(MCP,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                BOTH, sessionId(), null);
        assertEquals(HttpStatus.ACCEPTED, res.getStatusCode());
        assertTrue("通知不能有响应体(协议: 服务端 MUST NOT 回 JSON-RPC 响应对象)",
                res.getBody() == null || res.getBody().isEmpty());
    }

    @Test
    public void terminating_a_session_makes_every_later_request_a_404() {
        String session = sessionId();
        assertEquals(HttpStatus.OK,
                exchange(HttpMethod.DELETE, MCP, BOTH, session, null).getStatusCode());
        ResponseEntity<String> after = post(MCP, request(10, "tools/list", "{}"), BOTH, session, null);
        assertEquals("DELETE 之后这个 id 必须立刻失效: " + after.getBody(),
                HttpStatus.NOT_FOUND, after.getStatusCode());
    }

    // -------------------------------------------------------------------- 控制面默认关

    @Test
    public void the_control_plane_stays_closed_without_the_switch_even_though_it_is_on_the_classpath() {
        // z-mcp-starter 现在直接依赖 z-mcp-admin ⇒ 控制面的类一定在 classpath 上,
        // 唯一还拦着它的是 @ConditionalOnProperty. 这条必须钉死, 否则"引入 starter 就送出一个
        // 无鉴权的工具清单端点"会变成默认行为。
        ResponseEntity<String> res = exchange(HttpMethod.GET, "/mcp/admin/overview", BOTH, null, null);
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        ResponseEntity<String> prefixed =
                exchange(HttpMethod.GET, BASE + "/mcp/admin/overview", BOTH, null, null);
        assertEquals(HttpStatus.NOT_FOUND, prefixed.getStatusCode());
    }

    // --------------------------------------------------------------------- 内部件

    private String sessionId() {
        ResponseEntity<String> res = post(MCP, initialize(0), BOTH, null, null);
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        String id = res.getHeaders().getFirst(SESSION);
        assertNotNull(res.getBody(), id);
        // 协议: 客户端 MUST 在 initialize 成功后发 notifications/initialized, 之后才允许请求.
        // 少这一步的话服务端回 -32600 "server is not initialized" —— 那是对的, 不能惯着.
        ResponseEntity<String> ack = post(MCP,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", BOTH, id, null);
        assertEquals(ack.getBody(), HttpStatus.ACCEPTED, ack.getStatusCode());
        return id;
    }

    private static String initialize(int id) {
        return request(id, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"starter-e2e\",\"version\":\"0.2.0\"}}");
    }

    private static String request(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\","
                + "\"params\":" + params + "}";
    }

    /** 只换 Content-Type、其余照旧的一条 POST —— 415 那格要的就是"别的都不变". */
    private ResponseEntity<String> postWithContentType(String contentType, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, BOTH);
        headers.setContentType(MediaType.parseMediaType(contentType));
        return rest.exchange("http://127.0.0.1:" + port + MCP, HttpMethod.POST,
                new HttpEntity<String>(body, headers), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String accept,
                                        String session, String protocolVersion) {
        return exchange(HttpMethod.POST, path, body, accept, session, protocolVersion);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String accept,
                                           String session, String protocolVersion) {
        return exchange(method, path, null, accept, session, protocolVersion);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body,
                                           String accept, String session, String protocolVersion) {
        HttpHeaders headers = new HttpHeaders();
        if (accept != null) headers.set(HttpHeaders.ACCEPT, accept);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) headers.set(SESSION, session);
        if (protocolVersion != null) headers.set(PROTOCOL, protocolVersion);
        return rest.exchange("http://127.0.0.1:" + port + path, method,
                new HttpEntity<String>(body, headers), String.class);
    }
}
