package com.zifang.z.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 起真的 {@link ZMcpServerApplication} 上下文(真 Tomcat、真 profile), 端口一律交给系统. */
final class Boot {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Boot() {}

    static ConfigurableApplicationContext app(String... overrides) {
        List<String> args = new ArrayList<String>(Arrays.asList(overrides));
        // 端口 0 而不是 yml 里的 18095/18096/18097: 测试不能赌这台机器上谁占着哪个端口,
        // 也不能因为别人在跑同一套服务就红. 命令行参数是覆盖 yml 的唯一可靠通道.
        // 但调用方自己钉了端口就不能再补一个 —— 两个同名命令行参数会被绑成 "1234,0" 而启动失败.
        for (String a : args) {
            if (a.startsWith("--server.port=")) return run(args);
        }
        args.add("--server.port=0");
        return run(args);
    }

    private static ConfigurableApplicationContext run(List<String> args) {
        return new SpringApplicationBuilder(ZMcpServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .run(args.toArray(new String[0]));
    }

    static int port(ConfigurableApplicationContext ctx) {
        String p = ctx.getEnvironment().getProperty("local.server.port");
        if (p == null) throw new IllegalStateException("上下文没起来: local.server.port 为空");
        return Integer.parseInt(p);
    }

    /** tools/call 的 result → 第一个 text 块的内容. */
    static String firstText(JsonNode callResult) {
        JsonNode content = callResult.path("content");
        if (!content.isArray() || content.size() == 0) {
            throw new IllegalStateException("结果里没有 content 块: " + callResult);
        }
        return content.get(0).path("text").asText();
    }

    /** 参数串交给 Jackson 生成, 不在测试里手逃义(一个中文/引号就能把红点挪到测试自己身上). */
    static String jsonArgs(Object... keysAndValues) {
        com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            String key = String.valueOf(keysAndValues[i]);
            Object v = keysAndValues[i + 1];
            if (v instanceof Boolean) node.put(key, ((Boolean) v).booleanValue());
            else if (v instanceof Integer) node.put(key, ((Integer) v).intValue());
            else if (v == null) node.putNull(key);
            else node.put(key, String.valueOf(v));
        }
        return node.toString();
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("不是 JSON: " + text, e);
        }
    }

    /** 轮询到某个条件成立为止; 不成立就把最后一次观察值抛出去(而不是"睡两秒再赌"). */
    static void until(long deadlineMillis, String what, Probe probe) throws Exception {
        Exception last = null;
        String observed = "(还没跑过)";
        while (System.currentTimeMillis() < deadlineMillis) {
            try {
                observed = probe.inspect();
                return;
            } catch (ExpectationPending e) {
                last = e;
                observed = e.getMessage();
                Thread.sleep(100L);
            } catch (Exception e) {
                last = e;
                observed = e.toString();
                Thread.sleep(100L);
            }
        }
        throw new AssertionError("等不到 " + what + ", 截止前最后一次观察: " + observed
                + (last == null ? "" : (" / " + last)));
    }

    /** 条件还没成立时抛这个, {@link #until} 才知道要重试而不是判失败. */
    static final class ExpectationPending extends RuntimeException {
        ExpectationPending(String message) {
            super(message);
        }
    }

    interface Probe {
        /** @return 观察到的值; 条件未成立时抛 {@link ExpectationPending}. */
        String inspect() throws Exception;
    }
}
