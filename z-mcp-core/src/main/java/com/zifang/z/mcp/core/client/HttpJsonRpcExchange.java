package com.zifang.z.mcp.core.client;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
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
 * JDK 自带的 {@link HttpURLConnection} 实现的 HTTP 往返 — 不引入 HttpClient/OkHttp,
 * 因为 z-mcp 必须跑在 Java 8 上(java.net.http 是 11+).
 *
 * <p>不做重试: 重试属于协议层(会话失效要重新 initialize), 见
 * {@link McpRemoteClient}.
 */
public final class HttpJsonRpcExchange implements JsonRpcExchange, McpRemoteClient.DeleteCapable {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private final URL endpoint;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public HttpJsonRpcExchange(String endpoint, int connectTimeoutMillis, int readTimeoutMillis)
            throws IOException {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("server endpoint required");
        }
        this.endpoint = new URL(endpoint.trim());
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public Response post(String body, Map<String, String> header) throws IOException {
        return send("POST", body, header);
    }

    @Override
    public void delete(Map<String, String> header) throws IOException {
        send("DELETE", null, header);
    }

    private Response send(String method, String body, Map<String, String> header) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
        if (conn instanceof HttpsURLConnection && isIpv4Literal(endpoint.getHost())) {
            // 上游常用自签证书服务本机地址; 主机名是 IP 字面量时 SNI 无意义, 退化成按对端地址校验
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setHostnameVerifier(IPv4_ONLY);
        }
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMillis);
        conn.setReadTimeout(readTimeoutMillis);
        if (header != null) {
            for (Map.Entry<String, String> h : header.entrySet()) {
                if (h.getValue() != null) conn.setRequestProperty(h.getKey(), h.getValue());
            }
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
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
        String text = in == null ? "" : readCapped(in);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
        }
        String contentType = headers.get("content-type");
        conn.disconnect();
        return new Response(status, contentType, text, headers);
    }

    @Override
    public void close() {
        // HttpURLConnection 没有需要显式释放的持久连接
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        int total = 0;
        while ((read = in.read(chunk)) >= 0) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                in.close();
                throw new IOException("upstream response exceeds " + MAX_BODY_BYTES + " bytes");
            }
            buf.write(chunk, 0, read);
        }
        in.close();
        return new String(buf.toByteArray(), UTF8);
    }

    private static boolean isIpv4Literal(String host) {
        return host != null && host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    private static final HostnameVerifier IPv4_ONLY = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return isIpv4Literal(hostname) && hostname.equals(session.getPeerHost());
        }
    };
}
