package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.mcp.api.exception.McpException;
import com.zifang.z.mcp.core.protocol.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * stdio 传输 — 把上游 MCP server 当子进程拉起, 走 newline-delimited JSON-RPC.
 *
 * <p>本地 server(filesystem / git 那一类)只有这种接法, 所以注册中心必须支持它;
 * 又因为 z-mcp 是基建模块, 不能反向依赖应用层的 z-agent-kernel, 故自带实现.
 *
 * <p>读侧用独立线程**按 id 派发**, 而不是"顺序读到 id 匹配的那行为止": 上游会在两次响应
 * 之间插通知(notifications/*), 顺序读会把请求错配或直接读死.
 *
 * <p>读侧还有一条反过来的责任: 上游也可以向我们**发请求**(ping, 或任何它以为我们支持的方法 ——
 * 握手已不再广告 client 侧做不到的能力(#46), 但协议不允许我们假定对端因此就不问),
 * 而在这条链路上我们是 client —— 不该答的要闭嘴, 该答的一句都不能拖, 见 {@link #answerIfRequest}.
 * 上游推来的**通知**(没 id)既不能答也不该丢在地上: 交给 {@link JsonRpcExchange.PushCapable}
 * 的监听方, 由它决定"目录变了要不要现在去重取"(见 {@link #deliverNotification}).
 */
public final class StdioJsonRpcExchange
        implements JsonRpcExchange, JsonRpcExchange.PushCapable, Closeable {

    private static final Logger log = LoggerFactory.getLogger(StdioJsonRpcExchange.class);
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ObjectMapper mapper;
    private final long requestTimeoutMillis;
    private final Process process;
    private final BufferedWriter stdin;
    private final Map<Long, Awaiter> pending = new ConcurrentHashMap<Long, Awaiter>();
    private volatile boolean closed;
    private volatile String exitNote;
    /** 注册动作发生在读侧线程已经跑起来之后, 所以这个字段必须 volatile. */
    private volatile JsonRpcExchange.NotificationListener notificationListener;

    public StdioJsonRpcExchange(List<String> command, ObjectMapper mapper, long requestTimeoutMillis)
            throws IOException {
        if (command == null || command.isEmpty()
                || command.get(0) == null || command.get(0).trim().isEmpty()) {
            throw new IllegalArgumentException("stdio command required (z.mcp.servers[].command)");
        }
        this.mapper = mapper;
        this.requestTimeoutMillis = requestTimeoutMillis <= 0 ? 60_000L : requestTimeoutMillis;
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        this.process = builder.start();
        this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), UTF8));
        drainStderr();
        Thread reader = new Thread(this::pumpStdout, "z-mcp-stdio-reader");
        reader.setDaemon(true);
        reader.start();
        Thread reaper = new Thread(this::watchExit, "z-mcp-stdio-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    @Override
    public Response post(String body, Map<String, String> header) throws IOException {
        refuseIfDead();
        Long id = idOf(body);
        // 等待槽必须先挂上, 再写管道. 反过来做的窗口是: 本线程刚 flush 完就被调度走,
        // 上游一条已经热起来的进程立刻回答, reader 抢在 pending.put 之前 dispatch ——
        // 它找不到等待者, 只能把这行当"无人认领的响应"丢掉, 调用方随后白等一整个读超时.
        Awaiter awaiter = id == null ? null : new Awaiter();
        if (awaiter != null) pending.put(id, awaiter);
        try {
            writeln(body);
        } catch (IOException e) {
            if (awaiter != null) pending.remove(id);
            // 写不进去就等于对面没人了: reaper 线程可能还没观察到退出, 这里直接按已死收口,
            // 否则调用方拿到的是一个裸的 OS 字符串("Broken pipe"), 看不出是子进程退了.
            closed = true;
            throw new IOException("stdio mcp transport is closed (write failed: "
                    + e.getMessage() + ")" + note(), e);
        }
        if (id == null) {
            // 通知帧没有响应可等; 202 只是把"已送达"这个事实还给调用方
            return new Response(202, "application/json", "", Collections.<String, String>emptyMap());
        }
        try {
            String line;
            try {
                line = awaiter.await(requestTimeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for stdio id=" + id, e);
            }
            if (line == null) {
                throw new IOException("stdio mcp server did not answer id=" + id
                        + " within " + requestTimeoutMillis + "ms" + note());
            }
            return new Response(200, "application/json", line,
                    Collections.<String, String>emptyMap());
        } finally {
            pending.remove(id);
        }
    }

    private void writeln(String body) throws IOException {
        synchronized (stdin) {
            stdin.write(body);
            stdin.write('\n');
            stdin.flush();
        }
    }

    /**
     * 孩子都没了还往它的 stdin 写, 要么收到裸的 Broken pipe, 要么把请求写进无人读取的缓冲区
     * 然后白等一整个读超时 —— 两种都让调用方看不出现场.
     */
    private void refuseIfDead() throws IOException {
        if (closed || !process.isAlive()) {
            closed = true;
            throw new IOException("stdio mcp transport is closed" + note());
        }
    }

    /**
     * stderr 必须一直有人读, 否则上游写满管道缓冲后会在 write() 里挂住(经典子进程死锁).
     * 内容按 debug 记下来 —— 那是上游的启动日志, 排查连不上时最有用.
     */
    private void drainStderr() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), UTF8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        log.debug("mcp stdio stderr: {}", line);
                    }
                } catch (IOException ignored) {
                    // 进程退出时读到 EOF 或流被关, 都是正常收尾
                }
            }
        }, "z-mcp-stdio-stderr");
        t.setDaemon(true);
        t.start();
    }

    /** 子进程没 id 就没法配对, 所以只能拒绝而不是猜. */
    private Long idOf(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode id = node.get("id");
            if (id == null || id.isNull() || !id.canConvertToLong()) return null;
            return Long.valueOf(id.asLong());
        } catch (IOException e) {
            return null;
        }
    }

    private void pumpStdout() {
        InputStream in = process.getInputStream();
        byte[] buf = new byte[8192];
        StringBuilder line = new StringBuilder();
        try {
            int read;
            while ((read = in.read(buf)) >= 0) {
                for (int i = 0; i < read; i++) {
                    char c = (char) (buf[i] & 0xFF);
                    if (c == '\n') {
                        dispatch(line.toString());
                        line.setLength(0);
                    } else if (c != '\r') {
                        line.append(c);
                    }
                }
            }
            if (line.length() > 0) dispatch(line.toString());
        } catch (IOException e) {
            if (!closed) log.debug("stdio reader stopped: {}", e.getMessage());
        } finally {
            // stdout 一到 EOF, 这个传输就再也不可能收到任何响应了; 而进程可能还在退出的路上,
            // reaper 没观察到 ⇒ 只靠 reaper 会留一段"看着还活着、实际永远回不了话"的窗口,
            // 调用方在这段窗口里会把请求写进无人应答的管道再白等一整个读超时.
            closed = true;
            failAllPending("stdout closed" + note());
        }
    }

    @Override
    public void onUpstreamNotification(JsonRpcExchange.NotificationListener listener) {
        this.notificationListener = listener;
    }

    private void dispatch(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return;
        JsonNode node;
        try {
            node = mapper.readTree(trimmed);
        } catch (IOException e) {
            log.debug("discarding non-JSON line from stdio server: {}", trimmed);
            return;
        }
        // 先按"有没有 method"分流, 而不是先按"有没有在等的 id" —— 后者会把上游推来的**请求**
        // 一并归进"没人认领的响应"那一格, 于是这一条链路永远回不了话(见 answerIfRequest).
        JsonNode method = node.get("method");
        if (method != null && !method.isNull()) {
            JsonNode id = node.get("id");
            if (id == null || id.isNull()) deliverNotification(method.asText());
            else answerIfRequest(node, method.asText());
            return;
        }
        // 到这里已经没有通知了(有 method 的都在上面那支交出去了), 剩下的只有响应帧:
        // 配不上等待槽的那一种就是"没人认领的响应".
        JsonNode id = node.get("id");
        if (id == null || !id.canConvertToLong()) return;
        Awaiter awaiter = pending.remove(id.asLong());
        if (awaiter == null) {
            log.debug("stdio server sent an unsolicited response id={}", id);
            return;
        }
        awaiter.complete(trimmed);
    }

    /**
     * 上游推来一条通知(有 method、没有 id)—— 协议上它不该有回答, 也不该没人知道.
     *
     * <p>"没有回答"这一半 {@code StdioJsonRpcExchangeTest} 里有用具盯: 给一帧响应会把孩子
     * 的记录污染成"客户端答错了对象"(fixture 的 {@code report_spurious} 那一格).
     *
     * <p>回调里抛出来的异常必须在这里吃掉: 这条线程是这条传输**唯一**的收字人, 它一死,
     * 后面的每个请求都只能等到读超时, 而现场只留一行 reader stopped.
     */
    private void deliverNotification(String method) {
        JsonRpcExchange.NotificationListener listener = notificationListener;
        if (listener == null) {
            log.debug("stdio server pushed {} which nobody subscribed to", method);
            return;
        }
        try {
            listener.onNotification(method);
        } catch (RuntimeException e) {
            log.warn("stdio notification listener failed for {}: {}", method, e.toString());
        }
    }

    /**
     * 上游反过来向我们发请求. hub 在这条链路上是 client, 所以"接不接受得了"要由**我们**回答,
     * 不能靠沉默: 单线程的本地 server(filesystem / git 那一类)推完就阻塞在下一口读上,
     * 它不回答我们, 不是因为不想, 是因为它还在等我们.
     *
     * <ul>
     *   <li>{@code ping} —— 协议规定被 ping 的一方 MUST 回一个空 result.</li>
     *   <li>其余方法(例如已不再广告的 {@code roots} —— 上游问什么不由我们的 capabilities 决定,
     *       它随时有权问) ——
     *       回 {@code -32601 Method not found}, 与参照实现 {@code Protocol._onrequest} 的做法一致:
     *       对端当场就能继续干活, 而且知道是哪条路走不通, 而不是白等一整个它的超时.</li>
     *   <li>没有 id 的帧是通知 —— 那一支由调用方(dispatch)分给 {@link #deliverNotification},
     *       到这里的一定是有 id 的请求.</li>
     * </ul>
     *
     * <p>id 原样回显(数字或字符串都可能), 整个回执用 ObjectNode 组而不是拼字符串 ——
     * 方法名来自对端, 拼进 JSON 就是让人往管道里塞引号的机会.
     */
    private void answerIfRequest(JsonNode frame, String method) {
        JsonNode id = frame.get("id");
        ObjectNode reply = mapper.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", id);
        if (McpSchema.M_PING.equals(method)) {
            reply.putObject("result");
            log.debug("answered stdio server's ping (id={})", id);
        } else {
            ObjectNode error = reply.putObject("error");
            error.put("code", Integer.valueOf(McpException.METHOD_NOT_FOUND));
            error.put("message", "Method not found: the z-mcp client side does not implement " + method);
            log.info("stdio server asked for {} which this client does not implement; answered {}",
                    method, "JSON-RPC " + McpException.METHOD_NOT_FOUND);
        }
        try {
            writeln(mapper.writeValueAsString(reply));
        } catch (IOException e) {
            closed = true;
            log.debug("cannot answer stdio server's request {}: {}", method, e.getMessage());
        }
    }

    private void watchExit() {
        try {
            int code = process.waitFor();
            exitNote = " (exit code " + code + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitNote = " (interrupted)";
        } finally {
            closed = true;
            failAllPending("stdio process exited" + note());
        }
    }

    private String note() {
        return exitNote == null ? "" : exitNote;
    }

    private void failAllPending(String reason) {
        for (Awaiter a : pending.values()) a.fail(reason);
        pending.clear();
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
        try {
            synchronized (stdin) {
                stdin.close();
            }
        } catch (IOException ignore) {
            // 进程都要没了, 关不掉管道没什么可报告的
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    /** 一个等待响应的槽位. */
    private static final class Awaiter {
        private String value;
        private boolean done;

        synchronized void complete(String line) {
            value = line;
            done = true;
            notifyAll();
        }

        synchronized void fail(String reason) {
            if (!done) {
                value = null;
                done = true;
                notifyAll();
            }
        }

        synchronized String await(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!done) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                wait(left);
            }
            return value;
        }
    }
}
