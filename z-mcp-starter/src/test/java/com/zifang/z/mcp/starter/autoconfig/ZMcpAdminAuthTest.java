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
                HttpStatus.FORBIDDEN.value(), rawStatus("Origin: http://evil.example",
                        "Authorization: Bearer control-plane-token"));
        assertEquals("同源(与 Host 一致)的读取照常放行",
                HttpStatus.OK.value(), rawStatus("Origin: http://127.0.0.1:" + port,
                        "Authorization: Bearer control-plane-token"));
    }

    @Test
    public void the_data_plane_and_the_control_plane_answer_the_same_way() {
        // 两扇门的凭证策略必须一致. 数据面这里用 DELETE: 它同样受闸, 但没有请求体 ——
        // HttpURLConnection 对"带体的 POST + 401"会去尝试认证并抛
        // "cannot retry due to server authentication, in streaming mode", 那读的是客户端不是闸.
        ResponseEntity<String> data = rest.exchange("http://127.0.0.1:" + port + "/mcp",
                HttpMethod.DELETE, new HttpEntity<String>(new HttpHeaders()), String.class);
        ResponseEntity<String> control = get("/mcp/admin/overview", null);
        assertEquals("同一个凭证策略下两扇门必须给出同一个答复",
                data.getStatusCode(), control.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, data.getStatusCode());
    }

    /** 不经任何 HTTP client, 直接写一行请求, 只取状态码. */
    private int rawStatus(String... headers) throws java.io.IOException {
        java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(10_000);
            java.io.OutputStream out = socket.getOutputStream();
            StringBuilder request = new StringBuilder("GET /mcp/admin/overview HTTP/1.1\r\n")
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
