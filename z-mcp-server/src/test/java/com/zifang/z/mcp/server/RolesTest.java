package com.zifang.z.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 三种角色各自"在 wire 上到底广告了什么、算得对不对".
 *
 * <p>判据一律走真 HTTP + 真 Spring profile, 不走 {@code registry.lookup()}: 这个模块交付的是一个
 * 挂到 250 上给别人用的进程, 别人看到的是 {@code tools/list} 的返回值和 {@code tools/call} 的回执,
 * 不是某个 bean 的字段.
 */
public class RolesTest {

    private final List<ConfigurableApplicationContext> opened =
            new ArrayList<ConfigurableApplicationContext>();

    @After public void closeEverything() {
        for (ConfigurableApplicationContext ctx : opened) ctx.close();
        opened.clear();
    }

    // ------------------------------------------------------------------ text 角色

    @Test public void the_text_role_advertises_exactly_its_own_two_tools() throws Exception {
        Wire wire = Wire.client(boot("text"));

        assertEquals(Arrays.asList("text_stats", "text_transform"), sorted(wire.toolNames()));
        // 自省资源也跟着 builtin 一起关了: 那三条资源描述的是"注册中心自己", 叶子服务没有中心可描述.
        JsonNode resources = wire.result("resources/list", "{}").path("resources");
        assertTrue("叶子服务不该有资源可读: " + resources,
                resources.isArray() && resources.size() == 0);
    }

    @Test public void text_stats_counts_are_the_ones_the_input_actually_has() throws Exception {
        Wire wire = Wire.client(boot("text"));
        // "héllo 世界\nbye": é 是 1 码点 2 字节, 汉字各 1 码点 3 字节.
        JsonNode out = Boot.json(call(wire, "text_stats", "text", "héllo 世界\nbye"));

        assertEquals(12, out.path("characters").asInt());
        assertEquals(3, out.path("words").asInt());
        assertEquals(2, out.path("lines").asInt());
        // 1+2+1+1+1+1+3+3+1+3 = 17: é 占 2 字节、两个汉字各占 3 字节, 其余每字符 1 字节.
        // (码点数 12 与字节数 17 分不开的话, "按字符算长度"这个卖点就没人验过.)
        assertEquals(17, out.path("utf8Bytes").asInt());
    }

    @Test public void text_stats_does_not_invent_a_line_for_a_trailing_newline() throws Exception {
        Wire wire = Wire.client(boot("text"));
        JsonNode out = Boot.json(call(wire, "text_stats", "text", "a\nb\n"));
        assertEquals("\"a\\nb\\n\" 是 2 行(行尾换行是终止符不是新行)", 2, out.path("lines").asInt());
        assertEquals(2, out.path("words").asInt());

        JsonNode empty = Boot.json(call(wire, "text_stats", "text", ""));
        assertEquals("空串是 0 行 0 词, 不是 1 行", 0, empty.path("lines").asInt());
        assertEquals(0, empty.path("characters").asInt());
    }

    @Test public void every_style_the_schema_advertises_actually_works() throws Exception {
        Wire wire = Wire.client(boot("text"));
        List<String> advertised = advertisedEnum(wire, "text_transform", "to");
        assertEquals("schema 与实现表对不上就是广告了一条做不到的取值: " + advertised,
                Arrays.asList("upper", "lower", "snake", "kebab", "title", "reverse"), advertised);

        assertEquals("ABC DEF", transform(wire, "abc def", "upper"));
        assertEquals("abc def", transform(wire, "ABC DEF", "lower"));
        assertEquals("hello_world_example", transform(wire, "helloWorldExample", "snake"));
        assertEquals("get-time-api", transform(wire, "get_time API", "kebab"));
        assertEquals("Hello World Example", transform(wire, "hello_world example", "title"));
        // reverse 按码点走, 所以 emoji(两个 UTF-16 单元)不会被劈成两个乱字符.
        assertEquals("c🙂ba", transform(wire, "ab🙂c", "reverse"));
    }

    @Test public void an_argument_the_schema_rejects_comes_back_as_is_error_not_a_500()
            throws Exception {
        Wire wire = Wire.client(boot("text"));
        JsonNode missing = wire.callTool("text_transform", Boot.jsonArgs("text", "x"));
        assertTrue("少给必填的 to 应该是 isError 回执, 让模型有机会补参数: " + missing,
                missing.path("isError").asBoolean());
        assertTrue(missing.toString(), missing.toString().contains("to"));

        JsonNode extra = wire.callTool("text_stats",
                "{\"text\":\"x\",\"junk\":1}");
        assertTrue("additionalProperties:false 要真的挡住多给的键: " + extra,
                extra.path("isError").asBoolean());

        JsonNode outsideEnum = wire.callTool("text_transform",
                Boot.jsonArgs("text", "x", "to", "qot"));
        assertTrue("enum 之外的风格该被拒: " + outsideEnum,
                outsideEnum.path("isError").asBoolean());
        assertTrue("拒绝的理由里要列出合法取值: " + outsideEnum,
                outsideEnum.toString().contains("snake"));
    }

    // ---------------------------------------------------------------- codec 角色

    @Test public void the_codec_role_advertises_exactly_its_own_two_tools() throws Exception {
        Wire wire = Wire.client(boot("codec"));
        assertEquals(Arrays.asList("base64_codec", "hash_digest"), sorted(wire.toolNames()));
    }

    @Test public void base64_matches_the_published_vectors_in_both_alphabets() throws Exception {
        Wire wire = Wire.client(boot("codec"));
        // 下面每一个串都是独立算出来的(base64 命令行/python), 不是拿本实现算一遍再和自己比 ——
        // 那样两套字母表写反了也照样绿.
        assertEquals("aGVsbG8=", call(wire, "base64_codec", "text", "hello", "mode", "encode"));
        assertEquals("hello", call(wire, "base64_codec", "text", "aGVsbG8=", "mode", "decode"));

        // U+FFFF 的 UTF-8 是 EF BF BF, 是能把两套字母表真正分歧的那两位 —— 62 与 63 —— 一次
        // 全产出的最短样本: 标准表在那里给 '+' 和 '/', URL 安全表给 '-' 和 '_', 其余 62 个字符相同.
        // 拿 "hello" 这种样本量字母表, 写串了也不会红.
        String last = String.valueOf((char) 0xFFFF);
        assertEquals("77+/", call(wire, "base64_codec", "text", last, "mode", "encode"));
        assertEquals("77-_", call(wire, "base64_codec", "text", last, "mode", "encode",
                "urlSafe", true));
        assertEquals(last, call(wire, "base64_codec", "text", "77-_", "mode", "decode",
                "urlSafe", true));

        // 两种垫位形状: 余 2 字节 → 一个 '=', 余 1 字节 → 两个 '='. 少认一种就是解码端默默吃下坏数据.
        assertEquals("w7vDvw==", call(wire, "base64_codec", "text", "ûÿ", "mode", "encode"));
        assertEquals("ûÿ", call(wire, "base64_codec", "text", "w7vDvw==", "mode", "decode"));
    }

    @Test public void decoding_something_that_is_not_base64_is_an_execution_error()
            throws Exception {
        Wire wire = Wire.client(boot("codec"));
        JsonNode bad = wire.callTool("base64_codec", Boot.jsonArgs("text", "!!!", "mode", "decode"));
        assertTrue(bad.toString(), bad.path("isError").asBoolean());
        assertTrue(bad.toString(), Boot.firstText(bad).contains("not valid"));
    }

    @Test public void digests_match_the_reference_values_published_with_each_algorithm()
            throws Exception {
        Wire wire = Wire.client(boot("codec"));
        List<String> advertised = advertisedEnum(wire, "hash_digest", "algorithm");
        assertEquals(Arrays.asList("md5", "sha-1", "sha-256", "sha-512"), advertised);

        // 这四个串是各算法标准文档里的对答案, 不是本实现自己算一遍自己比.
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                digestHex(wire, "abc", "md5"));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                digestHex(wire, "abc", "sha-1"));
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                digestHex(wire, "abc", "sha-512"));
        JsonNode defaulted = Boot.json(call(wire, "hash_digest", "text", ""));
        assertEquals("sha-256", defaulted.path("algorithm").asText());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                defaulted.path("hex").asText());
    }

    // ------------------------------------------------------------------ hub 角色

    @Test public void the_default_role_is_hub_and_it_points_at_the_two_leaf_ports() throws Exception {
        // 什么都不给就起来: 交付时那台机器上的最省姿势(只有一个 jar).
        ConfigurableApplicationContext ctx = open();
        assertEquals("默认角色必须是 hub", Collections.singletonList("hub"),
                Arrays.asList(ctx.getEnvironment().getActiveProfiles()));
        assertEquals("http://127.0.0.1:18096/mcp",
                ctx.getEnvironment().getProperty("z.mcp.servers[0].endpoint"));
        assertEquals("http://127.0.0.1:18097/mcp",
                ctx.getEnvironment().getProperty("z.mcp.servers[1].endpoint"));
        String authHeader =
                ctx.getEnvironment().getProperty("z.mcp.servers[0].headers.Authorization");
        assertNotNull("hub 侧连上游要带 Authorization 占位, 否则上游配了令牌就永远连不上", authHeader);
        assertTrue(authHeader, authHeader.startsWith("Bearer "));

        List<String> names = sorted(Wire.client(Boot.port(ctx)).toolNames());
        assertTrue("hub 自己不注册 builtin 就没有调试工具可用: " + names,
                names.containsAll(Arrays.asList("echo", "find_tools", "registry_state",
                        "get_time", "generate_uuid", "system_info")));
        assertFalse("hub 没配可达的上游时不该凭空多出叶子工具: " + names,
                names.contains("text_stats"));
    }

    @Test public void each_role_says_who_it_is_in_the_initialize_result() throws Exception {
        for (String role : Arrays.asList("hub", "text", "codec")) {
            JsonNode info = Wire.client(boot(role)).initializeResult();
            assertEquals("角色 " + role + " 的 serverInfo.name",
                    "hub".equals(role) ? "z-mcp" : "z-mcp-" + role,
                    info.path("serverInfo").path("name").asText());
            assertTrue("instructions 会进 LLM 上下文, 每个角色都得有自己的那句话: " + info,
                    info.path("instructions").asText().length() > 10);
        }
    }

    // ---------------------------------------------------------------- 内部件

    private int boot(String role) {
        return Boot.port(open("--spring.profiles.active=" + role));
    }

    private ConfigurableApplicationContext open(String... overrides) {
        ConfigurableApplicationContext ctx = Boot.app(overrides);
        opened.add(ctx);
        return ctx;
    }

    private static JsonNode schemaOf(Wire wire, String tool) throws Exception {
        for (JsonNode t : wire.result("tools/list", "{}").path("tools")) {
            if (tool.equals(t.path("name").asText())) return t.path("inputSchema");
        }
        throw new IllegalStateException("目录里没有 " + tool);
    }

    /** 某个参数广告出去的取值: enum 挂在 {@code inputSchema.properties.<参数>.enum}, 不在根上. */
    private static List<String> advertisedEnum(Wire wire, String tool, String property)
            throws Exception {
        List<String> out = new ArrayList<String>();
        for (JsonNode v : schemaOf(wire, tool).path("properties").path(property).path("enum")) {
            out.add(v.asText());
        }
        return out;
    }

    private static String transform(Wire wire, String text, String to) throws Exception {
        return call(wire, "text_transform", "text", text, "to", to);
    }

    private static String digestHex(Wire wire, String text, String algorithm) throws Exception {
        return Boot.json(call(wire, "hash_digest", "text", text, "algorithm", algorithm))
                .path("hex").asText();
    }

    private static String call(Wire wire, String tool, Object... keysAndValues) throws Exception {
        JsonNode result = wire.callTool(tool, Boot.jsonArgs(keysAndValues));
        assertFalse("调用失败: " + result, result.path("isError").asBoolean());
        return Boot.firstText(result);
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<String>(in);
        Collections.sort(out);
        return out;
    }
}
