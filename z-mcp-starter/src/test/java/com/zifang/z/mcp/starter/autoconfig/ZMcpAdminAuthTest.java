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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 控制面与数据面共用同一把安全闸 —— 配了 {@code security.bearer-tokens} 之后，
 * {@code /mcp/admin/*} 不能再匿名读。
 *
 * <p>这条以前是不成立的：闸只装在 {@code JsonRpcController} 上，而控制面会吐出上游
 * endpoint、{@code lastError}、全部工具的 JSON Schema —— 那是拓扑信息，比工具清单更值得挡。
 * 更糟的是"配了 token"给人已经上了锁的错觉，实际上另一扇门一直开着。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = ZMcpAdminAuthTest.App.class,
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
                "z.mcp.security.bearer-tokens=control-plane-token"
        })
public class ZMcpAdminAuthTest {

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Test
    public void an_anonymous_read_of_the_control_plane_is_401_once_a_token_is_configured() {
        ResponseEntity<String> res = get("/mcp/admin/overview", null);
        assertEquals("配了 bearer-tokens 却让匿名读控制面 ⇒ 这个配置项在骗人",
                HttpStatus.UNAUTHORIZED, res.getStatusCode());
        String challenge = res.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        assertNotNull("401 必须带 WWW-Authenticate, 否则客户端不知道该怎么带上凭证", challenge);
        assertTrue(challenge, challenge.startsWith("Bearer"));
    }

    @Test
    public void the_configured_token_opens_the_control_plane() throws Exception {
        ResponseEntity<String> res = get("/mcp/admin/tools", "Bearer control-plane-token");
        assertEquals(res.getBody(), HttpStatus.OK, res.getStatusCode());
        JsonNode tools = mapper.readTree(res.getBody());
        assertTrue(tools.isArray());
        assertTrue(tools.toString().contains("get_time"));
    }

    @Test
    public void a_wrong_token_is_401_not_200_with_a_shorter_body() {
        ResponseEntity<String> res = get("/mcp/admin/health", "Bearer almost-right");
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    @Test
    public void a_cross_origin_read_of_the_control_plane_is_403_even_with_a_valid_token()
            throws Exception {
        // 凭证对了不等于谁都能读: 浏览器带着别的站点来的 Origin 打本机控制面,
        // 正是 allowed-origins 空白名单 = 同源策略要挡的那一路.
        //
        // 这里不能用 TestRestTemplate: JDK 的 HttpURLConnection 把 Origin 列为受限请求头,
        // 客户端会把它静默丢掉 —— 第一版就是这么读到"200, 闸没生效"的. 裸 socket 才是那把尺.
        assertEquals("带凭证的跨站请求必须被同源策略挡下",
                HttpStatus.FORBIDDEN.value(), rawStatus("/mcp/admin/overview",
                        "Origin: http://evil.example",
                        "Authorization: Bearer control-plane-token"));
        assertEquals("同源(与 Host 一致)的读取照常放行",
                HttpStatus.OK.value(), rawStatus("/mcp/admin/overview",
                        "Origin: http://127.0.0.1:" + port,
                        "Authorization: Bearer control-plane-token"));
    }

    @Test
    public void the_data_plane_and_the_control_plane_answer_the_same_way() {
        // 两扇门的凭证策略必须一致. 数据面这里用 DELETE: 它同样受闸, 但没有请求体 ——
        // HttpURLConnection 对"带体的 POST + 401"会去尝试认证并抛
        // "cannot retry due to server authentication, in streaming mode", 那读的是客户端不是闸.
        ResponseEntity<String> data = rest.exchange("http://127.0.0.1:" + port + "/mcp",
                HttpMethod.DELETE, new HttpEntity<String>(new HttpHeaders()), String.class);
        ResponseEntity<String> stream = rest.exchange("http://127.0.0.1:" + port + "/mcp",
                HttpMethod.GET, new HttpEntity<String>(new HttpHeaders()), String.class);
        ResponseEntity<String> control = get("/mcp/admin/overview", null);
        assertEquals("同一个凭证策略下两扇门必须给出同一个答复",
                data.getStatusCode(), control.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, data.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, stream.getStatusCode());

        // 只比状态码的那一版量不到"头"这一维: 修之前 GET/DELETE 回的是裸 401(没挑战),
        // 而控制面带挑战 —— 从流那一路进来的客户端因此拿不到"该怎么带凭证"的任何指示.
        String challenge = control.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        assertNotNull("401 必须带 WWW-Authenticate(RFC 6750)", challenge);
        assertEquals("Bearer realm=\"z-mcp\"", challenge);
        assertEquals("数据面 DELETE 的挑战与控制面不同形",
                challenge, data.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("数据面 GET(流)的挑战与控制面不同形",
                challenge, stream.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
    }

    /**
     * 自检路径 {@code /mcp/info} 也在同一把闸后面.
     *
     * <p>它报工具/资源/会话计数和服务端版本 —— 拓扑指纹, 与刚被收口的控制面同类。0.2.0 那次
     * 只把闸装到了 {@code z-mcp-admin} 的那组映射上, 而这条方法连 {@code HttpServletRequest}
     * 都不接, 于是"配了 token 就锁上了"这句话在它身上不成立: 匿名可读、跨站 Origin 也可读,
     * 而 README 教的恰恰是"探针用 curl 直接读它"。
     */
    @Test
    public void the_liveness_probe_is_behind_the_same_gate() throws Exception {
        ResponseEntity<String> anon = get("/mcp/info", null);
        assertEquals("同一个凭证策略下 /mcp/info 不许比另外两扇门松",
                HttpStatus.UNAUTHORIZED, anon.getStatusCode());
        assertEquals("挑战形状必须与数据面、控制面同形",
                "Bearer realm=\"z-mcp\"", anon.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

        assertEquals("凭证对了也不等于谁都能读: 跨站 Origin 同样要挡",
                HttpStatus.FORBIDDEN.value(), rawStatus("/mcp/info",
                        "Origin: http://evil.example", "Authorization: Bearer control-plane-token"));

        ResponseEntity<String> ok = get("/mcp/info", "Bearer control-plane-token");
        assertEquals("带对了凭证的自检必须照常可用(否则这条闸是把它整个关死了)",
                HttpStatus.OK, ok.getStatusCode());
        JsonNode info = mapper.readTree(ok.getBody());
        assertTrue("探针报的工具数必须是注册表里的真数: " + ok.getBody(),
                info.path("tools").asInt() > 0);
    }

    /** 不经任何 HTTP client, 直接写一行请求, 只取状态码. */
    private int rawStatus(String path, String... headers) throws java.io.IOException {
        if (!path.startsWith("/")) {
            // 第一个参数是路径. 少写一个参数时编译器不会拦(还有 varargs), 于是"Origin: xxx"
            // 会被当请求路径打出去, 读回来的 400/404 看着像闸生效了 —— 量具自己得先拒绝这种读法.
            throw new IllegalArgumentException("path must start with '/': " + path);
        }
        java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(10_000);
            java.io.OutputStream out = socket.getOutputStream();
            StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n");
            for (String header : headers) request.append(header).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            out.write(request.toString().getBytes("US-ASCII"));
            out.flush();
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), "US-ASCII"));
            String statusLine = in.readLine();
            if (statusLine == null) {
                throw new java.io.IOException("server closed without a status line");
            }
            String[] parts = statusLine.split(" ");
            if (parts.length < 2) throw new java.io.IOException(statusLine);
            return Integer.parseInt(parts[1]);
        } finally {
            socket.close();
        }
    }

    private ResponseEntity<String> get(String path, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                new HttpEntity<String>(headers), String.class);
    }
}
