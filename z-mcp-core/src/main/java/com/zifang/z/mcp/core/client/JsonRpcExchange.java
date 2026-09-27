package com.zifang.z.mcp.core.client;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * 一次 HTTP 往返的抽象 — 把"怎么发"和"协议说什么"分开, 让客户端可离线测.
 *
 * <p>只有 status / 头 / 体这三样, 因为 Streamable HTTP 的合规性判断全依赖它们
 * (404=会话失效, 202=通知已收, text/event-stream=响应以 SSE 帧承载).
 */
public interface JsonRpcExchange {

    /**
     * @param body   完整 JSON-RPC 请求文本
     * @param header 需要带的请求头(协议头/鉴权头)
     */
    Response post(String body, Map<String, String> header) throws IOException;

    void close();

    /**
     * 这条传输还能不能再收一次响应.
     *
     * <p>HTTP 每次 post 都新开户, 所以恒为 true; stdio 覆写成"子进程还在、管道还没断".
     * 状态视图必须问这个而不是问一个握手时置位的 boolean —— 否则本地 server 的子进程
     * 已经退了, {@code /mcp/admin} 还在报 connected=true.
     */
    default boolean isAlive() {
        return true;
    }

    /**
     * 上游可以**反过来推帧进来**的传输 —— 与 {@code McpRemoteClient.DeleteCapable} 同样是
     * "传输能力由实现方声明、调用方 instanceof 发现".
     *
     * <p>只有双向管道(stdio)做得到: HTTP 那一路一次 POST 配一次响应, 除此之外没有任何常驻读流
     * (hub 也不为上游开 GET 流), 结构上没有一条能让上游随时开口的通道. 所以"上游推了目录变更"
     * 这件事在 http 上游那里根本无法发生, 不是实现漏了.
     */
    interface PushCapable {
        /** 之后每一帧"有 method、无 id"都会交给这个监听方. 覆盖旧值, 传 null 等于取消. */
        void onUpstreamNotification(NotificationListener listener);
    }

    /**
     * 收到上游推送.
     *
     * <p><b>实现方会在传输的读侧线程上被调用</b>, 那里一停全家停: 阻塞在这条线程上的
     * 每一毫秒里, 所有在等的响应都拿不到回答. 只能记账, 活交给别的线程去干.
     */
    interface NotificationListener {
        void onNotification(String method);
    }

    /** 响应. headers 的 key 一律小写, 因为 HTTP 头大小写不敏感. */
    final class Response {
        private final int status;
        private final String contentType;
        private final String body;
        private final Map<String, String> headers;

        public Response(int status, String contentType, String body, Map<String, String> headers) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.headers = lower(headers);
        }

        /**
         * HTTP 头大小写不敏感, 而 {@code Mcp-Session-Id} 取不到就等于会话每次都重建.
         * 所以小写化由本类保证, 不要求每个传输自己记得做 —— 漏做的症状是"能连上但每次都掉线".
         */
        private static Map<String, String> lower(Map<String, String> in) {
            if (in == null || in.isEmpty()) return Collections.emptyMap();
            Map<String, String> out = new java.util.LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : in.entrySet()) {
                if (e.getKey() != null) out.put(e.getKey().toLowerCase(), e.getValue());
            }
            return out;
        }

        public int status() { return status; }

        public String contentType() { return contentType; }

        public String body() { return body; }

        public String header(String name) {
            return name == null ? null : headers.get(name.toLowerCase());
        }

        public Map<String, String> headers() { return headers; }

        public boolean is2xx() { return status >= 200 && status < 300; }
    }
}
