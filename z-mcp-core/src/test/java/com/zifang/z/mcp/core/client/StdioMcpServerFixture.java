package com.zifang.z.mcp.core.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * 测试用的最小 stdio MCP server —— 由 {@link StdioJsonRpcExchangeTest} 当真子进程拉起来.
 *
 * <p>故意只用 JDK: 这样启动 fixture 的 classpath 就是 test-classes 一个目录,
 * 不依赖 surefire 把 jackson 摆在 java.class.path 上(它可能只放一个 booter jar).
 *
 * <p>它演的是真实上游会做的那些"烦人但合法"的事: 在响应前插通知、插一个别的 id 的响应、
 * 把 stderr 写爆、直接退出、以及干脆不回答.
 *
 * <p>{@code push_then_wait} 演的是另一类: 上游反过来**请求**客户端. 协议里客户端要回话
 * (ping 回空 result, 做不了的回 -32601), 所以这里写完就**真的 readLine 等回执** ——
 * 客户端不回, 这个单线程的孩子就同时停住了读和写, 我们的请求也拿不到回答. 这不是
 * "礼貌性等待": 真实的本地 server(filesystem / git 那一类)就是这个形状.
 */
public final class StdioMcpServerFixture {

    /** 客户端回了一条"不是请求所能换来的东西"的帧 —— 只在 method 缺席时记账. */
    private static String spurious;

    /** 握手那一帧的原样字节 —— 管道那头到底收到了什么承诺, 由它作证. */
    private static String initializeFrame;

    public static void main(String[] argv) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, "UTF-8"));
        final PrintWriter out = new PrintWriter(
                new OutputStreamWriter(System.out, "UTF-8"), false);
        String line;
        while ((line = in.readLine()) != null) {
            String id = field(line, "id");
            String method = field(line, "method");
            if (method == null) {
                // 有 id 却没 method ⇒ 是一条响应. 客户端只有在回应"请求"时才该写这一行,
                // 而我们这一轮并没有向它发过任何请求 ⇒ 它答错了对象.
                if (id != null) spurious = line;
                continue;
            }
            if (id == null) continue;
            if ("initialize".equals(method)) {
                initializeFrame = line;
                out.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                        + "\"params\":{\"level\":\"info\",\"data\":\"fixture booting\"}}");
                out.flush();
                reply(out, id, "{\"protocolVersion\":\"2025-06-18\","
                        + "\"serverInfo\":{\"name\":\"stdio-fixture\",\"version\":\"1\"},"
                        + "\"capabilities\":{\"tools\":{}}}");
            } else if ("tools/list".equals(method)) {
                reply(out, id, "{\"tools\":["
                        + "{\"name\":\"fixture_echo\",\"description\":\"回声\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                        + "{\"text\":{\"type\":\"string\"}}}},"
                        + "{\"name\":\"get_time\",\"description\":\"和内置工具撞名\","
                        + "\"inputSchema\":{\"type\":\"object\"}}"
                        + "],\"nextCursor\":\"\"}");
            } else if ("tools/call".equals(method)) {
                String text = field(line, "text");
                reply(out, id, "{\"content\":[{\"type\":\"text\",\"text\":\"echo:"
                        + (text == null ? "?" : text) + "\"}]}");
            } else if ("stderr_flood".equals(method)) {
                // 管道缓冲通常只有 64KB: 没人读 stderr 就会在这里写死
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 6000; i++) {
                    sb.append("fixture startup log line, padding to overflow the pipe buffer\n");
                }
                System.err.print(sb.toString());
                System.err.flush();
                reply(out, id, "{\"content\":[]}");
            } else if ("out_of_order".equals(method)) {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":99990001,\"result\":{\"unsolicited\":true}}");
                out.flush();
                reply(out, id, "{\"content\":[]}");
            } else if ("push_then_wait".equals(method)) {
                // 反向请求: 推出去, 然后**真的等**客户端回话. 等不到就把孩子自己卡在这里 ——
                // 这正是单线程本地 server 的行为, 也是我们要抓住的那个病灶.
                String pushedId = field(line, "pushId");
                String pushedMethod = field(line, "pushMethod");
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + pushedId + ",\"method\":\""
                        + pushedMethod + "\"}");
                out.flush();
                String answer = in.readLine();
                reply(out, id, "{\"pushed\":\"" + pushedMethod + "\",\"answer\":"
                        + (answer == null ? "null" : answer) + "}");
            } else if ("report_spurious".equals(method)) {
                reply(out, id, "{\"spurious\":" + (spurious == null ? "null" : spurious) + "}");
            } else if ("report_initialize".equals(method)) {
                reply(out, id, "{\"initialize\":"
                        + (initializeFrame == null ? "null" : initializeFrame) + "}");
            } else if ("never_answers".equals(method)) {
                // 什么都不做: 让客户端自己超时
            } else if ("blind_stdout".equals(method)) {
                // 关掉自己的 stdout 但进程不退 —— 真实上游里这是"管道断了, 子进程还占着 PID".
                // 这件事只有 reader 看得见, reaper 要等进程真的退出才会动.
                System.out.close();
            } else if ("vanish".equals(method)) {
                System.exit(7);
            } else {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":"
                        + "{\"code\":-32601,\"message\":\"Method not found\"}}");
                out.flush();
            }
        }
    }

    private static void reply(PrintWriter out, String id, String result) {
        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
        out.flush();
    }

    /** 从"我们自己造的"单行 JSON 里取一个顶层标量字段的值. */
    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length() + 2);
        if (colon < 0) return null;
        int s = colon + 1;
        while (s < json.length() && json.charAt(s) == ' ') s++;
        if (s >= json.length()) return null;
        if (json.charAt(s) == '"') {
            int e = json.indexOf('"', s + 1);
            return e < 0 ? null : json.substring(s + 1, e);
        }
        int e = s;
        while (e < json.length() && ",}".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(s, e).trim();
    }
}
