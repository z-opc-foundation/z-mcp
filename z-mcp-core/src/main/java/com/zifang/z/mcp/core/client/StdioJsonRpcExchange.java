package com.zifang.z.mcp.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
public final class StdioJsonRpcExchange implements JsonRpcExchange, Closeable {

    private static final Logger log = LoggerFactory.getLogger(StdioJsonRpcExchange.class);
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final ObjectMapper mapper;
    private final long requestTimeoutMillis;
    private final Process process;
    private final BufferedWriter stdin;
    private final Map<Long, Awaiter> pending = new ConcurrentHashMap<Long, Awaiter>();
    private volatile boolean closed;
    private volatile String exitNote;

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
        JsonNode id = node.get("id");
        if (id == null || !id.canConvertToLong()) return;      // 服务端推的通知, 无人等
        Awaiter awaiter = pending.remove(id.asLong());
        if (awaiter == null) {
            log.debug("stdio server sent an unsolicited response id={}", id);
            return;
        }
        awaiter.complete(trimmed);
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
