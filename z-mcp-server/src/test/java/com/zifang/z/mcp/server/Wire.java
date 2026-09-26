package com.zifang.z.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的裸 HTTP 客户端 —— 只用 JDK 自带的 {@link HttpURLConnection}.
 *
 * <p>不复用仓里的 {@code McpRemoteClient}: 那样等于"我们自己写的客户端连我们自己写的服务端",
 * 双向的错会互相抵消(比如两边都把某个头拼错). 这里手敲一遍线格式, 客户端姿势错了就红.
 */
final class Wire {

    static final String BOTH = "application/json, text/event-stream";
    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String host;
    private final int port;
    private final String bearer;
    private final ObjectMapper mapper = new ObjectMapper();

    private String session;
    private JsonNode initializeResult;
    private int nextId = 1;

    Wire(String host, int port, String bearer) {
        this.host = host;
        this.port = port;
        this.bearer = bearer;
    }

    static Wire of(int port) {
        return new Wire("127.0.0.1", port, null);
    }

    static Wire of(int port, String bearer) {
        return new Wire("127.0.0.1", port, bearer);
    }

    /** 握手完的客户端: 测试里几乎所有断言都发生在 initialize 之后. */
    static Wire client(int port) throws java.io.IOException {
        return of(port).handshake();
    }

    static Wire client(int port, String bearer) throws java.io.IOException {
        return of(port, bearer).handshake();
    }

    String session() {
        return session;
    }

    JsonNode initializeResult() {
        return initializeResult;
    }

    /**
     * 只发 initialize、不吃失败. 用来量"这把凭证开不开得了门" —— {@link #handshake()} 在非 200
     * 时抛异常, 而有些断言要判的恰恰是那个非 200 长什么样.
     */
    Response initializeAttempt() throws IOException {
        return post("/mcp", request("initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"z-mcp-server-test\",\"version\":\"0.2.0\"}}"),
                null, null);
    }

    /** initialize + notifications/initialized 的完整握手, 之后才允许普通请求. */
    Wire handshake() throws IOException {
        Response res = initializeAttempt();
        if (res.status != 200) throw new IllegalStateException("initialize got " + res.status
                + " " + res.body);
        session = res.header(SESSION_HEADER);
        if (session == null) throw new IllegalStateException("initialize 没发会话 id: " + res.body);
        initializeResult = mapper.readTree(res.body).path("result");
        Response ack = post("/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                session, null);
        if (ack.status != 202) throw new IllegalStateException("ack got " + ack.status + " " + ack.body);
        return this;
    }

    JsonNode result(String method, String params) throws IOException {
        Response res = post("/mcp", request(method, params), session, null);
        if (res.status != 200) {
            throw new IllegalStateException(method + " 期望 200, 实得 " + res.status + " " + res.body);
        }
        JsonNode node = mapper.readTree(res.body);
        if (node.has("error")) {
            throw new IllegalStateException(method + " 被服务端拒绝: " + node.get("error"));
        }
        return node.path("result");
    }

    JsonNode callTool(String name, String argumentsJson) throws IOException {
        return result("tools/call", "{\"name\":" + quote(name) + ",\"arguments\":"
                + (argumentsJson == null ? "{}" : argumentsJson) + "}");
    }

    /** tools/list 的工具名, 按服务端给的顺序. */
    List<String> toolNames() throws IOException {
        JsonNode tools = result("tools/list", "{}").path("tools");
        List<String> names = new java.util.ArrayList<String>();
        for (JsonNode t : tools) names.add(t.path("name").asText());
        return names;
    }

    Response post(String path, String body, String sessionId, String protocolVersion)
            throws IOException {
        return send("POST", path, body, sessionId, protocolVersion, null,
                bearer == null ? null : "Bearer " + bearer);
    }

    Response get(String path, String sessionId) throws IOException {
        return send("GET", path, null, sessionId, null, null,
                bearer == null ? null : "Bearer " + bearer);
    }

    Response send(String method, String path, String body, String sessionId,
                  String protocolVersion, String accept) throws IOException {
        return send(method, path, body, sessionId, protocolVersion, accept,
                bearer == null ? null : "Bearer " + bearer);
    }

    /**
     * 直接指定 Authorization 原值 —— 闸的三种 401 形状分别对应"没带头 / 带了但读不出令牌 /
     * 令牌不对", 只有能逐字写这个头才量得到中间那一种.
     */
    Response send(String method, String path, String body, String sessionId,
                  String protocolVersion, String accept, String authorization) throws IOException {
        HttpURLConnection conn =
                (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(false);
        if (accept != null || body != null) conn.setRequestProperty("Accept",
                accept == null ? BOTH : accept);
        if (authorization != null) conn.setRequestProperty("Authorization", authorization);
        if (sessionId != null) conn.setRequestProperty(SESSION_HEADER, sessionId);
        if (protocolVersion != null) conn.setRequestProperty(PROTOCOL_HEADER, protocolVersion);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.getBytes(UTF8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = in == null ? "" : readAll(in);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            headers.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue().get(0));
        }
        conn.disconnect();
        return new Response(status, text, headers);
    }

    String request(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + (nextId++) + ",\"method\":" + quote(method)
                + ",\"params\":" + params + "}";
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
        in.close();
        return new String(buf.toByteArray(), UTF8);
    }

    static final class Response {
        final int status;
        final String body;
        private final Map<String, String> headers;

        Response(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        @Override public String toString() {
            return status + " " + body;
        }
    }
}
