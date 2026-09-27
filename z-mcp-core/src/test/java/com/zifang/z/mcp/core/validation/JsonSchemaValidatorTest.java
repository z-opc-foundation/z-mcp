package com.zifang.z.mcp.core.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 工具入参校验器. 协议要求服务端 MUST 校验, 且 2025-11-25 起校验失败要作为
 * 工具执行错误回给模型 —— 所以这里必须"该红的红、不该管的别管".
 */
public class JsonSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private void assertOk(String schema, String instance) throws Exception {
        List<String> errors = validator.validate(json(schema), json(instance));
        assertTrue("应通过但报了: " + errors, errors.isEmpty());
    }

    private List<String> errorsOf(String schema, String instance) throws Exception {
        return validator.validate(json(schema), json(instance));
    }

    /** 按消息内容数条数: 光数总数会把"一支关键字冒充另一支"读成通过. */
    private static int countMatches(List<String> messages, String needle) {
        int n = 0;
        for (String m : messages) if (m.contains(needle)) n++;
        return n;
    }

    @Test
    public void required_and_types() throws Exception {
        String s = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                + "\"required\":[\"text\"]}";
        assertOk(s, "{\"text\":\"hi\"}");
        assertEquals(1, errorsOf(s, "{}").size());
        assertTrue(errorsOf(s, "{}").get(0).contains("missing required property 'text'"));
        assertTrue(errorsOf(s, "{\"text\":5}").get(0).contains("expected type string"));
        assertTrue(errorsOf(s, "{\"text\":null}").get(0).contains("missing required property"));
    }

    @Test
    public void integer_accepts_integral_doubles_but_rejects_fraction() throws Exception {
        String s = "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}";
        assertOk(s, "{\"n\":3}");
        assertOk(s, "{\"n\":3.0}");
        assertFalse(errorsOf(s, "{\"n\":3.5}").isEmpty());
    }

    @Test
    public void enum_and_bounds() throws Exception {
        assertOk("{\"type\":\"string\",\"enum\":[\"iso\",\"epoch\"]}", "\"iso\"");
        assertFalse(errorsOf("{\"type\":\"string\",\"enum\":[\"iso\",\"epoch\"]}", "\"utc\"").isEmpty());
        assertFalse(errorsOf("{\"type\":\"number\",\"minimum\":1,\"maximum\":10}", "0.5").isEmpty());
        assertOk("{\"type\":\"number\",\"minimum\":1,\"maximum\":10}", "10");
        assertFalse(errorsOf("{\"type\":\"number\",\"minimum\":1,\"maximum\":10}", "11").isEmpty());
    }

    @Test
    public void string_constraints() throws Exception {
        assertFalse(errorsOf("{\"type\":\"string\",\"minLength\":3}", "\"ab\"").isEmpty());
        assertFalse(errorsOf("{\"type\":\"string\",\"maxLength\":3}", "\"abcd\"").isEmpty());
        assertOk("{\"type\":\"string\",\"pattern\":\"^[a-z]+$\"}", "\"abc\"");
        assertFalse(errorsOf("{\"type\":\"string\",\"pattern\":\"^[a-z]+$\"}", "\"ABC\"").isEmpty());
        // 坏 pattern 要报告, 不能静默放行
        assertFalse(errorsOf("{\"type\":\"string\",\"pattern\":\"[unclosed\"}", "\"x\"").isEmpty());
    }

    @Test
    public void nested_objects_arrays_and_additional_properties() throws Exception {
        String s = "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},"
                + "\"required\":[\"id\"]}},\"meta\":{\"type\":\"object\","
                + "\"properties\":{\"k\":{\"type\":\"string\"}},\"additionalProperties\":false}},"
                + "\"required\":[\"items\"]}";
        assertOk(s, "{\"items\":[{\"id\":1},{\"id\":2}],\"meta\":{\"k\":\"v\"}}");
        assertTrue(errorsOf(s, "{\"items\":[]}").isEmpty());
        assertFalse(errorsOf(s, "{\"items\":[{}]}").get(0).contains("$.items[1]"));
        assertFalse(errorsOf(s, "{\"items\":[{}]}").isEmpty());
        assertFalse(errorsOf(s, "{\"items\":[{\"id\":\"x\"}]}").isEmpty());
        assertFalse(errorsOf(s, "{\"items\":[],\"meta\":{\"bad\":1}}").isEmpty());
    }

    @Test
    public void union_types_and_unknown_keywords() throws Exception {
        assertOk("{\"type\":[\"string\",\"null\"]}", "\"x\"");
        assertFalse(errorsOf("{\"type\":[\"string\",\"null\"]}", "5").isEmpty());
        // JSON Schema 语义: 未知关键字不构成约束
        assertOk("{\"type\":\"string\",\"x-custom-thing\":{\"deep\":true}}", "\"ok\"");
        // 没有 schema 约束时什么都放行
        assertOk("{}", "{\"anything\":[1,2,3]}");
        assertOk("null", "5");
    }

    @Test
    public void missing_instance_is_reported_not_crashed() throws Exception {
        assertFalse(validator.validate(json("{\"type\":\"object\"}"), null).isEmpty());
    }

    // =====================================================================
    // #48: $ref / $defs / definitions / allOf / anyOf / oneOf / not.
    //
    // 参照系是盘上的真字节, 不是设想:
    //   ~/.cache/zmcp_prey/ref48_schema_probe.py 跑官方 python SDK 1.27.1 的 FastMCP,
    //     嵌套 pydantic 模型交出来的 inputSchema 就是
    //     {"$defs":{"Address":{...}}, "properties":{"a":{"$ref":"#/$defs/Address"}}};
    //     联合类型交成 anyOf; 自引用模型交成一条指回同一个 $defs 条目的 $ref.
    //   ~/.cache/zmcp_prey/tsclient/ref48_zod_probe.js 跑盘上那份 zod@4.6.5 自带的
    //     toJSONSchema: 递归模型在 draft-07 目标下默认交 {"allOf":[{"$ref":"#"}]} (指针指根),
    //     只有显式 reused:'ref' 才出现 definitions + "#/definitions/__schema0";
    //     默认不抽公共子模型 (原地内联). 联合交 anyOf, z.record 交 additionalProperties 为 schema.
    //   (这一格上一版探针是**坏的**: 它拿 zod-to-json-schema@3.25.2 去喂 zod@4.6.5,
    //    三格全交 {"$schema":...} 空壳而照样打印 —— 所以新探针带了一条"空壳即 FATAL"的对照.)
    // 修复前这些关键字**一个都不认识**, 全部走"未知关键字不作约束"那一支 ——
    // 也就是说官方 SDK 生成的主要约束被整段放行. 下面每一条红探针都对应上面的一份字节.

    /** python SDK 1.27.1 + pydantic 2 对 `def f(a: Address)` 的实测形状. */
    private static final String ADDRESS_2020 = "{\"$defs\":{\"Address\":{\"type\":\"object\","
            + "\"properties\":{\"city\":{\"type\":\"string\"},"
            + "\"zipcode\":{\"type\":\"string\",\"pattern\":\"^\\\\d{6}$\"}},"
            + "\"required\":[\"city\",\"zipcode\"]}},"
            + "\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/Address\"}},"
            + "\"required\":[\"a\"]}";

    /** 同一份 schema 换成 draft-07 的容器名 (zod 的 draft-7 目标就这么发). */
    private static final String ADDRESS_07 = "{\"definitions\":{\"Address\":{\"type\":\"object\","
            + "\"properties\":{\"city\":{\"type\":\"string\"},"
            + "\"zipcode\":{\"type\":\"string\",\"pattern\":\"^\\\\d{6}$\"}},"
            + "\"required\":[\"city\",\"zipcode\"]}},"
            + "\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/definitions/Address\"}},"
            + "\"required\":[\"a\"]}";

    /** `Mixed.v: Union[int, str]` 的实测形状: 约束整体住在 anyOf 里, 没有同级 type. */
    private static final String ANY_OF_MIXED = "{\"$defs\":{\"Mixed\":{\"type\":\"object\","
            + "\"properties\":{\"v\":{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"string\"}]}},"
            + "\"required\":[\"v\"]}},"
            + "\"type\":\"object\",\"properties\":{\"m\":{\"$ref\":\"#/$defs/Mixed\"}},"
            + "\"required\":[\"m\"]}";

    /** pydantic 自引用模型的实测形状 —— $defs/Tree 里又指回 #/$defs/Tree. */
    private static final String RECURSIVE_TREE = "{\"$defs\":{\"Tree\":{\"type\":\"object\","
            + "\"properties\":{\"child\":{\"anyOf\":[{\"$ref\":\"#/$defs/Tree\"},{\"type\":\"null\"}]},"
            + "\"value\":{\"type\":\"integer\"}},\"required\":[\"value\"]}},"
            + "\"type\":\"object\",\"properties\":{\"t\":{\"$ref\":\"#/$defs/Tree\"}},"
            + "\"required\":[\"t\"]}";

    @Test
    public void a_ref_into_defs_actually_constrains_the_instance() throws Exception {
        assertOk(ADDRESS_2020, "{\"a\":{\"city\":\"hz\",\"zipcode\":\"311100\"}}");
        // 修复前这三条全部静默通过: "$ref" 当时是未知关键字.
        List<String> missing = errorsOf(ADDRESS_2020, "{\"a\":{\"city\":\"hz\"}}");
        assertFalse("refs 里的 required 没生效: " + missing, missing.isEmpty());
        assertTrue("要报在解析后的路径上: " + missing,
                missing.get(0).contains("missing required property 'zipcode'"));
        List<String> pattern = errorsOf(ADDRESS_2020, "{\"a\":{\"city\":\"hz\",\"zipcode\":\"12345\"}}");
        assertFalse(pattern.toString(), pattern.isEmpty());
        assertTrue("路径要跟着钻进 refs: " + pattern, pattern.get(0).contains("$.a.zipcode"));
    }

    @Test
    public void a_ref_into_draft07_definitions_resolves_too() throws Exception {
        assertOk(ADDRESS_07, "{\"a\":{\"city\":\"hz\",\"zipcode\":\"311100\"}}");
        assertTrue(errorsOf(ADDRESS_07, "{\"a\":{\"city\":\"hz\"}}").get(0)
                .contains("missing required property 'zipcode'"));
        assertTrue(errorsOf(ADDRESS_07, "{\"a\":{\"city\":\"hz\",\"zipcode\":\"abc\"}}").get(0)
                .contains("$.a.zipcode"));
    }

    /** 纯 refs(不经组合子)的递归: 报的路径要跟着 refs 一路钻下去. */
    private static final String NESTED_LINK = "{\"$defs\":{\"L\":{\"type\":\"object\","
            + "\"properties\":{\"next\":{\"$ref\":\"#/$defs/L\"},\"v\":{\"type\":\"integer\"}}}},"
            + "\"type\":\"object\",\"properties\":{\"r\":{\"$ref\":\"#/$defs/L\"}}}";

    @Test
    public void a_ref_may_point_anywhere_in_the_document_by_json_pointer() throws Exception {
        // 指针可以指文档里任何位置, 并且允许 refs 串 refs (Alias -> 真正的叶子).
        String s = "{\"type\":\"object\",\"properties\":{\"inner\":{\"type\":\"object\","
                + "\"properties\":{\"leaf\":{\"type\":\"integer\",\"minimum\":5}}},"
                + "\"copy\":{\"$ref\":\"#/$defs/Alias\"}},"
                + "\"$defs\":{\"Alias\":{\"$ref\":\"#/properties/inner/properties/leaf\"}}}";
        assertOk(s, "{\"copy\":7}");
        List<String> e = errorsOf(s, "{\"copy\":1}");
        assertFalse("两层指针之后的约束没生效: " + e, e.isEmpty());
        assertTrue("要报在使用方这条路径上: " + e, e.get(0).contains("$.copy"));

        // ~1 / ~0 转义按 JSON Pointer 的规定走 —— 这三条的期望值不是推的, 是拿 ajv
        // (TS SDK validation/ajv-provider.js 里那个 validator) 对拍出来的,
        // 见 ~/.cache/zmcp_prey/tsclient/ref48_ajv_tilde.js -> ref48_ajv_tilde.log.
        assertOk("{\"$defs\":{\"a/b\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                + "{\"$ref\":\"#/$defs/a~1b\"}}}", "{\"p\":\"v\"}");
        assertFalse(errorsOf("{\"$defs\":{\"a/b\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                + "{\"$ref\":\"#/$defs/a~1b\"}}}", "{\"p\":5}").isEmpty());
        assertOk("{\"$defs\":{\"a~b\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                + "{\"$ref\":\"#/$defs/a~0b\"}}}", "{\"p\":\"v\"}");
        // 键名字面就是 "a~1b" 时, 同串指针指的是 "a/b" —— 指不到, 必须红 (ajv 同判)
        assertFalse(errorsOf("{\"$defs\":{\"a~1b\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                + "{\"$ref\":\"#/$defs/a~1b\"}}}", "{\"p\":\"v\"}").isEmpty());
        // URL 转义: pydantic 对含空格的模型名就是这么写指针的 (ajv 同样判 VALID)
        assertOk("{\"$defs\":{\"A B\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                + "{\"$ref\":\"#/$defs/A%20B\"}}}", "{\"p\":\"v\"}");
        assertFalse(errorsOf("{\"$defs\":{\"A B\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                + "{\"$ref\":\"#/$defs/A%20B\"}}}", "{\"p\":5}").isEmpty());

        // ~0 与 ~1 的**解序**也要钉住: RFC 6901 规定先解 ~1 再解 ~0, 所以指针里的 "~01"
        // 指的是字面键 "~1" (而不是 "/"). 两格期望值同为 ajv/jsonschema 对拍 (ref48_tilde01_*.log).
        assertOk("{\"$defs\":{\"~1\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                + "{\"$ref\":\"#/$defs/~01\"}}}", "{\"p\":\"v\"}");
        assertFalse("解序漂了会把 ~01 解成 /, 指到别处就算绿: 这格必须仍按 ~1 这个键约束",
                errorsOf("{\"$defs\":{\"~1\":{\"type\":\"string\"}},\"properties\":{\"p\":"
                        + "{\"$ref\":\"#/$defs/~01\"}}}", "{\"p\":5}").isEmpty());

        // 指针可以落在数组下标上 (zod 的 allOf:[{$ref}] 这类形状就带下标).
        // ajv 判 {"p":"x"} 违规、{"p":3} 通过 (ref48_tilde01_ajv.log 后两行).
        String pair = "{\"$defs\":{\"Pair\":[{\"type\":\"string\"},{\"type\":\"integer\"}]},"
                + "\"properties\":{\"p\":{\"$ref\":\"#/$defs/Pair/1\"}}}";
        assertOk(pair, "{\"p\":3}");
        assertFalse("数组下标指针没解出来: ", errorsOf(pair, "{\"p\":\"x\"}").isEmpty());
        // 越界下标 = 指不到东西, 按同一政策红 (ajv 在这里直接抛 MissingRefError).
        // 两格都要钉: 只钉 3 的话, "把越界夹成 0 号元素"那种退化实现照样绿.
        String badIdx = "{\"$defs\":{\"Pair\":[{\"type\":\"string\"}]},"
                + "\"properties\":{\"p\":{\"$ref\":\"#/$defs/Pair/7\"}}}";
        List<String> oob = errorsOf(badIdx, "{\"p\":3}");
        assertFalse("越界下标被当成解到了: " + oob, oob.isEmpty());
        assertTrue("要说清是哪条指针指不到: " + oob, oob.get(0).contains("#/$defs/Pair/7"));
        assertFalse("越界退化成按 0 号元素判 (而 0 号恰好合得上) 也算放行: ",
                errorsOf(badIdx, "{\"p\":\"s\"}").isEmpty());
    }

    @Test
    public void an_unresolvable_ref_fails_closed_instead_of_passing() throws Exception {
        String dangling = "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/Nope\"}},"
                + "\"required\":[\"a\"]}";
        List<String> e = errorsOf(dangling, "{\"a\":{\"x\":1}}");
        assertFalse("指针指不到东西不等于没有约束: " + e, e.isEmpty());
        assertTrue("要说清楚是哪个指针: " + e, e.get(0).contains("#/$defs/Nope"));

        // 另一条出口: 指针一路走到底, 落下来的东西是 JSON null ("$defs":{"Nope":null}).
        // 上面那条 dangling 走的是"容器就没这个键"的出口, 这一格才钉住"落到 null 值"那一支 ——
        // 少这一格, "把这支改成静默 return" 的变异没人抓 (实测 M02 一度如此).
        List<String> nullTarget = errorsOf("{\"$defs\":{\"Nope\":null},\"type\":\"object\","
                + "\"properties\":{\"a\":{\"$ref\":\"#/$defs/Nope\"}},\"required\":[\"a\"]}",
                "{\"a\":{\"x\":1}}");
        assertFalse("指针落到 null 值被当成没有约束放过了: " + nullTarget, nullTarget.isEmpty());
        assertTrue("要报的是指针而不是别的: " + nullTarget, nullTarget.get(0).contains("#/$defs/Nope"));

        // 阳性对照: 同一份 schema 只把目标补上, 就必须改成按目标约束,
        // 而不是"永远报指不到" —— 否则上一条红是尺坏不是实例红.
        String fixed = "{\"$defs\":{\"Nope\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},"
                + "\"required\":[\"x\"]}},\"type\":\"object\","
                + "\"properties\":{\"a\":{\"$ref\":\"#/$defs/Nope\"}},\"required\":[\"a\"]}";
        assertOk(fixed, "{\"a\":{\"x\":1}}");
        assertFalse(errorsOf(fixed, "{\"a\":{\"x\":\"s\"}}").isEmpty());

        // 跨文档/远程的 $ref 一律**不抓取** (抓就是把校验器变成 SSRF 的出口), 但也不放行.
        assertFalse(errorsOf("{\"type\":\"object\",\"properties\":{\"a\":"
                + "{\"$ref\":\"http://127.0.0.1:1/x#/A\"}}}", "{\"a\":1}").isEmpty());
        assertFalse(errorsOf("{\"properties\":{\"a\":{\"$ref\":5}}}", "{\"a\":1}").isEmpty());
    }

    @Test
    public void anyOf_accepts_a_matching_branch_and_rejects_the_rest() throws Exception {
        assertOk(ANY_OF_MIXED, "{\"m\":{\"v\":5}}");
        assertOk(ANY_OF_MIXED, "{\"m\":{\"v\":\"x\"}}");
        for (String bad : new String[]{"{\"m\":{\"v\":true}}", "{\"m\":{\"v\":[1]}}",
                "{\"m\":{\"v\":{\"a\":1}}}"}) {
            List<String> e = errorsOf(ANY_OF_MIXED, bad);
            assertFalse(bad + " 落在所有分支之外, 必须红", e.isEmpty());
            assertEquals("分支内部的违规不许各冒一条: " + e, 1, e.size());
        }
        assertTrue(errorsOf(ANY_OF_MIXED, "{\"m\":{\"v\":true}}").get(0).contains("$.m.v"));
        // required 住在 refs 背后一样要算
        assertFalse(errorsOf(ANY_OF_MIXED, "{\"m\":{}}").isEmpty());
        // 同级关键字与 anyOf 各自独立生效 (anyOf 不是"二选一的逃生门").
        // 这四格的期望值拿两份参照校验器对拍过: ~/.cache/zmcp_prey/ref48_sibling_oracle.log
        // (jsonschema 4.25.1 与 ajv 8.20 四格读数逐条相同)
        assertOk("{\"anyOf\":[{\"type\":\"string\"}],\"minLength\":2}", "\"ab\"");
        assertFalse(errorsOf("{\"anyOf\":[{\"type\":\"string\"}],\"minLength\":2}", "\"a\"").isEmpty());
        assertFalse(errorsOf("{\"anyOf\":[{\"type\":\"integer\"}],\"minimum\":10}", "5").isEmpty());
        // minLength 对非字符串**不生效**, 这一格两份参照都判 VALID —— 记下来防手滑
        assertOk("{\"anyOf\":[{\"type\":\"integer\"}],\"minLength\":2}", "5");
    }

    @Test
    public void allOf_requires_every_branch_including_the_ref_one() throws Exception {
        String s = "{\"definitions\":{\"Base\":{\"type\":\"object\","
                + "\"properties\":{\"value\":{\"type\":\"integer\"}},\"required\":[\"value\"]}},"
                + "\"allOf\":[{\"$ref\":\"#/definitions/Base\"},"
                + "{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\"}},"
                + "\"required\":[\"note\"]}]}";
        assertOk(s, "{\"value\":1,\"note\":\"n\"}");
        assertTrue(errorsOf(s, "{\"value\":1}").get(0).contains("'note'"));
        assertTrue(errorsOf(s, "{\"note\":\"n\"}").get(0).contains("'value'"));
        // 分支里的约束互相看不见才算漏: 两条各缺一半时要报两条
        assertEquals(2, errorsOf(s, "{}").size());
        // allOf 之外的同级关键字也要算
        assertFalse(errorsOf("{\"allOf\":[{\"type\":\"integer\"}],\"minimum\":10}", "5").isEmpty());
    }

    @Test
    public void oneOf_accepts_exactly_one_branch() throws Exception {
        // 两支都要求 integer, 只在区间上分开 —— "恰好一支"的四格真值表才点得满
        String s = "{\"oneOf\":[{\"type\":\"integer\",\"minimum\":0},"
                + "{\"type\":\"integer\",\"maximum\":5}]}";
        assertOk(s, "7");    // 只中第一支
        assertOk(s, "-2");   // 只中第二支
        List<String> both = errorsOf(s, "3");
        assertFalse("两支都中就不是 oneOf", both.isEmpty());
        assertEquals(1, both.size());
        List<String> none = errorsOf(s, "true");
        assertFalse("一支都不中必须红", none.isEmpty());
        assertEquals("分支内部的违规不许冒泡成好几条", 1, none.size());
        assertTrue("要说清是哪个关键字: " + none, none.get(0).contains("oneOf"));
    }

    @Test
    public void not_inverts_the_subschema() throws Exception {
        assertOk("{\"type\":\"string\",\"not\":{\"pattern\":\"^secret\"}}", "\"hello\"");
        assertTrue(errorsOf("{\"type\":\"string\",\"not\":{\"pattern\":\"^secret\"}}",
                "\"secret123\"").get(0).contains("not"));
        assertOk("{\"not\":{\"type\":\"string\"}}", "5");
        assertTrue(!errorsOf("{\"not\":{\"type\":\"string\"}}", "\"x\"").isEmpty());
        // not 里再套 $ref 不能把环打开
        assertFalse(errorsOf("{\"$defs\":{\"S\":{\"type\":\"string\"}},"
                + "\"not\":{\"$ref\":\"#/$defs/S\"}}", "\"x\"").isEmpty());
    }

    @Test(timeout = 30_000L)
    public void a_recursive_schema_terminates_and_still_checks_the_leaf() throws Exception {
        assertOk(RECURSIVE_TREE, "{\"t\":{\"value\":1}}");
        assertOk(RECURSIVE_TREE, "{\"t\":{\"value\":1,\"child\":{\"value\":2,"
                + "\"child\":{\"value\":3}}}}");
        List<String> deep = errorsOf(RECURSIVE_TREE, "{\"t\":{\"value\":1,\"child\":{\"value\":\"nope\"}}}");
        assertFalse("递归 refs 里的叶子约束没生效: " + deep, deep.isEmpty());
        assertTrue("要报在真实的深度上: " + deep, deep.get(0).contains("$.t.child"));
        // null 命中 anyOf 里的 {"type":"null"}
        assertOk(RECURSIVE_TREE, "{\"t\":{\"value\":1,\"child\":null}}");

        // 不经组合子的递归: 路径要一路跟着 refs 钻到真实的实例深度
        assertOk(NESTED_LINK, "{\"r\":{\"next\":{\"next\":{\"v\":1}}}}");
        assertTrue(errorsOf(NESTED_LINK, "{\"r\":{\"next\":{\"next\":{\"v\":\"x\"}}}}").get(0)
                .contains("$.r.next.next.v"));

        // 不推进的环 (同一实例位置自指) 必须**有界**地收口并报告, 而不是栈溢出或转到天荒地老。
        // 这一格是有意偏离参照实现的: ajv 在这里直接 RangeError 爆栈
        // (ref48_ajv_oracle.log 的 self cycle / mutual cycle 两行), 我们要的是一条判定.
        List<String> self = errorsOf("{\"$defs\":{\"A\":{\"$ref\":\"#\"}},\"$ref\":\"#/$defs/A\"}", "5");
        assertFalse("自指环没被拦住", self.isEmpty());
        assertTrue("要说明是被递归闸拦下的: " + self, self.get(0).contains("recurs"));
        List<String> mutual = errorsOf("{\"$defs\":{\"A\":{\"$ref\":\"#/$defs/B\"},"
                + "\"B\":{\"$ref\":\"#/$defs/A\"}},\"$ref\":\"#/$defs/A\"}", "{\"k\":1}");
        assertFalse("互指环没被拦住", mutual.isEmpty());
        assertTrue("要说明是被递归闸拦下的: " + mutual, mutual.get(0).contains("recurs"));
    }

    @Test(timeout = 30_000L)
    public void the_recursion_shape_zod_emits_by_default_is_enforced() throws Exception {
        // 盘上 zod@4.6.5 的 z.toJSONSchema(T, {target:'draft-07'}) 对
        // `child: optional(lazy(() => T))` 交出来的就是这一段 (ref48_zod_probe.log):
        // 一层 allOf 裹着指回**文档根**的 $ref —— 不是设想的 "#/$defs/T".
        // 参照两侧在这六格实例上读数一致 (ajv 与 jsonschema 各 6 格, 见 README 台账那一笔).
        String s = "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"},"
                + "\"child\":{\"allOf\":[{\"$ref\":\"#\"}]}},"
                + "\"required\":[\"value\"],\"additionalProperties\":false}";
        assertOk(s, "{\"value\":1}");
        assertOk(s, "{\"value\":1,\"child\":{\"value\":2,\"child\":{\"value\":3}}}");
        // 上面那格同时是闸的阳性对照: 只看 ref 串不看实例路径的实现会在这里误判成环.
        List<String> deep = errorsOf(s, "{\"value\":1,\"child\":{\"value\":\"x\"}}");
        assertFalse("根指针递归里的叶子约束没生效: " + deep, deep.isEmpty());
        assertTrue("要报在真实的实例深度上: " + deep, deep.get(0).contains("$.child.value"));
        assertFalse("child 为 null 该按对象判: ",
                errorsOf(s, "{\"value\":1,\"child\":null}").isEmpty());
        // 这两格才管"展开根 ref 之后同级关键字还算不算": 违规落在**递归层**上,
        // 只有 $ref 真把根 schema 摆到 $.child 处才红得出来
        // (参照两侧对这六格的读数逐格相同, ~/.cache/zmcp_prey/tsclient/ref48_zod_oracle.log).
        List<String> extra = errorsOf(s, "{\"value\":1,\"child\":{\"value\":2,\"extra\":1}}");
        assertFalse("递归层上的同级 additionalProperties 没生效: " + extra, extra.isEmpty());
        assertTrue("要报在递归层那一个节点上: " + extra, extra.get(0).startsWith("$.child:"));
        assertFalse("递归层上根 schema 的 required 也该照算: ",
                errorsOf(s, "{\"value\":1,\"child\":{}}").isEmpty());
    }

    @Test
    public void a_draft07_tuple_from_a_real_client_checks_every_element() throws Exception {
        // zod 4 的 z.tuple([z.string(), z.number()]) 交出来的就是下面这一段字节
        // (~/.cache/zmcp_prey/tsclient/ref48_zod_probe.log 的 tuple 两行 —— draft-07 与
        //  "draft 2020-12" 两种 target 下**同形**, 都是数组 items + additionalItems:false).
        // 官方 TS SDK 自己用的校验器是裸 draft-07 ajv
        // (node_modules/@modelcontextprotocol/sdk/dist/cjs/validation/ajv-provider.js:
        //  new Ajv({strict:false, validateFormats:true, validateSchema:false, allErrors:true})),
        // 它对这段字节的读数与 jsonschema 的 Draft7 逐格相同 (ref49_tuple_oracle.log / _py.log),
        // 而我们在此之前一格都不管 —— 元组的"第 i 个元素是什么类型"没人兜, 只兜住了长度.
        String t = "{\"type\":\"array\",\"items\":[{\"type\":\"string\"},{\"type\":\"number\"}],"
                + "\"additionalItems\":false,\"minItems\":2,\"maxItems\":2}";
        assertOk(t, "[\"a\",1]");
        List<String> wrong = errorsOf(t, "[\"a\",\"b\"]");
        assertFalse("第 2 个元素的类型没管住: " + wrong, wrong.isEmpty());
        assertTrue("要报在出问题那一格上: " + wrong, wrong.get(0).startsWith("$[1]:"));
        assertFalse("元组长度超了该红: ", errorsOf(t, "[\"a\",1,2]").isEmpty());
        // 单独判"是 additionalItems 在管尾巴"而不是 maxItems: 去掉长度那半条, 参照读数是
        // "07 additionalItems:false rejects extra" (ajv: must NOT have more than 2 items)
        List<String> longTail = errorsOf("{\"type\":\"array\",\"items\":[{\"type\":\"string\"},"
                + "{\"type\":\"number\"}],\"additionalItems\":false}", "[\"a\",1,2]");
        assertFalse("additionalItems:false 没拦住尾巴: " + longTail, longTail.isEmpty());
        assertTrue("尾巴要报在第 3 格上: " + longTail, longTail.get(0).startsWith("$[2]:"));

        // 阳性对照, 防的是"顺手管过头": 没有 additionalItems 时尾巴敞开、实例可以短于位置表,
        // 这两格两份 draft-07 参照都判 VALID ("07 items shorter ok" / "07 extra allowed by default")
        String open = "{\"type\":\"array\",\"items\":[{\"type\":\"string\"},{\"type\":\"number\"}]}";
        assertOk(open, "[\"a\"]");
        assertOk(open, "[\"a\",1,\"什么都能有\"]");

        // 尾巴也可以是**一份 schema** (ajv: /2 must be boolean; jsonschema: [2] 'x' is not of type)
        String tail = "{\"type\":\"array\",\"items\":[{\"type\":\"string\"}],"
                + "\"additionalItems\":{\"type\":\"boolean\"}}";
        assertOk(tail, "[\"a\",true,false]");
        assertFalse("尾巴那份 schema 没生效: " + errorsOf(tail, "[\"a\",1]"),
                errorsOf(tail, "[\"a\",1]").isEmpty());
        // 空位置表 + 关死尾巴 = 一个元素都不许有 (两份参照: "must NOT have more than 0 items")
        assertOk("{\"type\":\"array\",\"items\":[],\"additionalItems\":false}", "[]");
        assertFalse(errorsOf("{\"type\":\"array\",\"items\":[],\"additionalItems\":false}", "[1]").isEmpty());
    }

    @Test
    public void the_2020_12_prefixItems_spelling_gets_the_same_treatment() throws Exception {
        String p = "{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"number\"}]}";
        assertOk(p, "[\"a\",1]");
        List<String> wrong = errorsOf(p, "[\"a\",\"b\"]");
        assertFalse("prefixItems 的位置类型没管住: " + wrong, wrong.isEmpty());
        assertTrue("要报在出问题那一格上: " + wrong, wrong.get(0).startsWith("$[1]:"));
        // 短实例与敞开尾巴: 四份参照全判 VALID (ref49 两份日志的 "2020 prefixItems shorter/extra" 格)
        assertOk(p, "[\"a\"]");
        assertOk(p, "[\"a\",1,true]");

        // 2020-12 的尾巴归 items 而不是 additionalItems (ajv: must NOT have more than 2 items;
        // jsonschema: Expected at most 2 items but found 1 extra)
        String closed = "{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"},"
                + "{\"type\":\"number\"}],\"items\":false}";
        assertOk(closed, "[\"a\",1]");
        List<String> extra = errorsOf(closed, "[\"a\",1,true]");
        assertFalse("items:false 没拦住 2020-12 的尾巴: " + extra, extra.isEmpty());
        assertTrue("要报在第 3 格上: " + extra, extra.get(0).startsWith("$[2]:"));
        String tailSchema = "{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"}],"
                + "\"items\":{\"type\":\"boolean\"}}";
        assertOk(tailSchema, "[\"a\",true]");
        assertFalse("尾巴那份 schema 没生效: " + errorsOf(tailSchema, "[\"a\",1]"),
                errorsOf(tailSchema, "[\"a\",1]").isEmpty());

        // 位置项是布尔 schema 照判 (两份 2020-12 参照: "boolean schema is false" / "False schema does not allow 1")
        assertOk("{\"type\":\"array\",\"prefixItems\":[false]}", "[]");
        assertFalse(errorsOf("{\"type\":\"array\",\"prefixItems\":[false]}", "[1]").isEmpty());
        // 深一层的真实路径 (ajv: /0/1 must be string; jsonschema: [0, 1])
        List<String> deep = errorsOf("{\"type\":\"array\",\"prefixItems\":[{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}}]}", "[[\"x\",1]]");
        assertFalse("嵌套元组里层的元素没管住: " + deep, deep.isEmpty());
        assertTrue("要报在 $[0][1] 上: " + deep, deep.get(0).startsWith("$[0][1]:"));
        // 阳性对照: prefixItems 旁边挂 additionalItems **不作约束** (四份参照全判 VALID)
        assertOk("{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"}],"
                + "\"additionalItems\":false}", "[\"a\",1,2]");
    }

    @Test
    public void a_nested_value_that_is_not_a_schema_gets_a_verdict_not_silence() throws Exception {
        // 这几格**没有可照抄的参照**: 该放子 schema 的地方放了别的东西时, ajv 两种方言都判
        // VALID (SDK 那侧关了 validateSchema), jsonschema 两种方言都直接 AttributeError 崩掉
        // (ref49_tuple_oracle.log / ref49_tuple_oracle_py.log 的 bogus / policy 行).
        // 所以按顶层那一条政策办: 认得的位置必须给确定结论, 不能退成"这里没约束".
        List<String> inItems = errorsOf("{\"type\":\"array\",\"items\":[\"nope\"]}", "[\"a\"]");
        assertFalse("位置项不配当 schema 却静默放行: " + inItems, inItems.isEmpty());
        assertTrue("要说清为什么不判: " + inItems, inItems.get(0).contains("must be an object or a boolean"));
        List<String> inProps = errorsOf("{\"type\":\"object\",\"properties\":{\"a\":\"nope\"}}", "{\"a\":1}");
        assertFalse("properties 里不配当 schema 却静默放行: " + inProps, inProps.isEmpty());
        assertTrue("要报在那一个键上: " + inProps, inProps.get(0).startsWith("$.a:"));
        List<String> badRef = errorsOf("{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/X\"}},"
                + "\"$defs\":{\"X\":\"nope\"}}", "{\"a\":1}");
        assertFalse("$ref 落在非 schema 上却静默放行: " + badRef, badRef.isEmpty());
        assertTrue("要报在 ref 那一个位置: " + badRef, badRef.get(0).startsWith("$.a:"));

        // prefixItems 自己不是一份位置表 / 两种元组写法互相矛盾 / 尾巴关键字不是 schema —— 都要报出来
        List<String> badPrefix = errorsOf("{\"type\":\"array\",\"prefixItems\":{\"type\":\"string\"}}", "[\"a\"]");
        assertFalse("prefixItems 不是数组却静默放行: " + badPrefix, badPrefix.isEmpty());
        assertTrue("要点名 prefixItems: " + badPrefix, badPrefix.get(0).contains("prefixItems"));
        List<String> both = errorsOf("{\"type\":\"array\",\"items\":[{\"type\":\"string\"}],"
                + "\"prefixItems\":[{\"type\":\"number\"}]}", "[\"a\"]");
        assertFalse("两种元组写法打架却随便挑一边: " + both, both.isEmpty());
        assertTrue("要说清是哪两种写法: " + both, both.get(0).contains("prefixItems"));
        List<String> badTail = errorsOf("{\"type\":\"array\",\"items\":[{\"type\":\"string\"}],"
                + "\"additionalItems\":5}", "[\"a\",1]");
        assertFalse("尾巴关键字不配当 schema 却静默放行: " + badTail, badTail.isEmpty());
        assertTrue("要点名是哪个关键字: " + badTail, badTail.get(0).contains("additionalItems"));
    }

    @Test
    public void additional_properties_as_a_schema_constrains_map_values() throws Exception {
        // pydantic 的 `Dict[str, int]` 实测形状 (~/.cache/zmcp_prey/ref48_dict_probe.py):
        // 约束不住在 properties 里, 住在 additionalProperties 这份 **schema** 里.
        // 两份参照校验器都判 {"d":{"count":"one"}} 违规 (同文件 + ref48_ajv_oracle.log).
        String s = "{\"type\":\"object\",\"properties\":{\"d\":{\"type\":\"object\","
                + "\"additionalProperties\":{\"type\":\"integer\"}}},\"required\":[\"d\"]}";
        assertOk(s, "{\"d\":{\"count\":1}}");
        List<String> e = errorsOf(s, "{\"d\":{\"count\":\"one\"}}");
        assertFalse("map 的值类型没管住: " + e, e.isEmpty());
        assertTrue("要报在出问题那个键上: " + e, e.get(0).contains("$.d.count"));
        // 阳性对照: properties 点过名的键不该再被 additionalProperties 过一遍
        assertOk("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"additionalProperties\":{\"type\":\"integer\"}}", "{\"a\":\"s\"}");
        // additionalProperties: true 仍然什么都不管
        assertOk("{\"properties\":{\"a\":{\"type\":\"string\"}},\"additionalProperties\":true}",
                "{\"a\":\"s\",\"zz\":[1,2]}");
    }

    @Test
    public void a_boolean_schema_means_exactly_what_it_says() throws Exception {
        // 指针解析不认容器叫什么: jsonschema 对
        // {"definitions":{"X":false},"$ref":"#/definitions/X"} 判违规 (ref48_bool_schema.log),
        // 所以恒假 schema 经 $ref 进来一样要能否决一个实例.
        assertFalse("恒假 schema 被放过了",
                errorsOf("{\"$defs\":{\"Nope\":false},\"$ref\":\"#/$defs/Nope\"}", "5").isEmpty());
        assertOk("{\"$defs\":{\"Anything\":true},\"$ref\":\"#/$defs/Anything\"}", "5");
        List<String> items = errorsOf("{\"properties\":{\"t\":{\"type\":\"array\",\"items\":false}}}",
                "{\"t\":[1]}");
        assertFalse("items:false 被放过了", items.isEmpty());
        assertTrue("要报在下标上: " + items, items.get(0).contains("$.t[0]"));
        assertOk("{\"properties\":{\"t\":{\"type\":\"array\",\"items\":true}}}", "{\"t\":[1,\"x\"]}");
    }

    @Test
    public void a_ref_does_not_swallow_the_keywords_beside_it() throws Exception {
        // 2019-09 起 $ref 明确"像 allOf 一样", 同级关键字各自照算. 这四格的期望值不是推的:
        // ajv (~/.cache/zmcp_prey/tsclient/ref48_mut_probe.js -> ref48_mut_probe.log) 与
        // jsonschema 4.25.1 (ref48_mut_probe_py.log) 逐格相同.
        String s = "{\"$ref\":\"#/$defs/S\",\"minLength\":5,\"$defs\":{\"S\":{\"type\":\"string\"}}}";
        assertFalse("同级 minLength 被 $ref 吞掉了", errorsOf(s, "\"ab\"").isEmpty());
        assertOk(s, "\"abcde\"");
        // 同级 type 与 $ref 目标冲突时, 两支都要红 (不是"后面那条盖掉前面")
        String clash = "{\"$ref\":\"#/$defs/S\",\"type\":\"number\",\"$defs\":{\"S\":{\"type\":\"string\"}}}";
        assertFalse(clash, errorsOf(clash, "\"x\"").isEmpty());
        // 同级 required 住在 refs 背后一样要算
        String req = "{\"$ref\":\"#/$defs/P\",\"required\":[\"extra\"],"
                + "\"$defs\":{\"P\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}}}";
        List<String> e = errorsOf(req, "{\"a\":\"x\"}");
        assertFalse("$ref 之后的同级 required 没算: " + e, e.isEmpty());
        assertTrue("要报的正是同级那条: " + e, e.get(0).contains("'extra'"));
        assertOk(req, "{\"a\":\"x\",\"extra\":1}");
    }

    @Test
    public void a_schema_that_is_neither_object_nor_boolean_is_not_silently_accepted() throws Exception {
        // 顶层也是 schema: 恒假就是把一切入参拒掉. 两份参照都这么判
        // (false -> INVALID / true -> VALID, 见 ref48_mut_probe.log 与 ref48_mut_probe_py.log).
        // 这一格在 hub 上是真实可达的: 上游交什么 inputSchema 我们就按什么校验.
        assertFalse("恒假 schema 在顶层被放过了", errorsOf("false", "5").isEmpty());
        assertOk("true", "5");
        // 既不是对象也不是布尔的东西不配当 schema: 参照实现是直接抛
        // (ref48_bogus_schema_ajv.log: "schema must be object or boolean"), 我们要一条违规
        assertFalse("一个字符串被当成没有约束放行了", errorsOf("\"not a schema\"", "5").isEmpty());
        // 没有 schema (null) 仍然是"没声明约束", 与上面两种区分开
        assertOk("null", "5");
    }

    @Test
    public void the_def_container_itself_is_not_treated_as_instance_constraints() throws Exception {
        // $defs 只是容器: 里面的东西在没有被 $ref 指到之前不该参与判定.
        assertOk("{\"type\":\"object\",\"$defs\":{\"Unused\":{\"type\":\"object\","
                + "\"required\":[\"never\"]}},\"properties\":{\"a\":{\"type\":\"string\"}}}",
                "{\"a\":\"x\"}");
        // 同理 definitions 里的布尔 false 也不该凭空否决一个实例
        assertOk("{\"definitions\":{\"X\":false}}", "5");
    }

    // =====================================================================
    // #50: const.
    //
    // 病相不是设想的: 官方 python SDK 1.27.1 + pydantic 2.12.5 交 wire 时按 Literal 的元数分岔
    //   convert -> {"const":"celsius","default":"celsius","title":"Unit","type":"string"}
    //   pick    -> {"default":"up","enum":["up","down"],"title":"Direction","type":"string"}
    // 两种元数各量过一个 bool / int 版本, 四行读数在 ~/.cache/zmcp_prey/ref50_py_wire.log
    // (产出它的 ref50_py_wire.py 就在旁边; 走的是真 FastMCP 的 list_tools(), 不是推测).
    // 多值那一支早有 enum 兜着, 单值这一支一直落在"未知关键字不作约束"的政策里 ⇒ 一个广告
    // "单位只能是 celsius"的工具, 传 fahrenheit 也照样执行.
    //
    // 期望值来自盘上两份参照 × 各两种方言的逐格读数:
    //   ajv 8.20.0        tsclient/ref50_const_oracle.log (27 格, 含自检: 全 VALID 当场 FATAL)
    //   jsonschema 4.26.0 ref50_const_oracle_py.log       (31 格, 同一条闸)
    // 共有的 54 格 (跨实现、同方言, 27 × 两方言) 判类逐格相同: 对象与键序无关、数组与次序有关、
    // 1 不等于 true、0 不等于 False、1 与 1.0 同值 —— 也就是说与 enum 用的是同一套 JSON 相等.
    // 这个"54"不是手抄的: ref50_crossdiff.py 从上面两份日志现算, 打印
    // CROSS_IMPL_SAME_DIALECT shared=54 divergent=0, 分叉即 FATAL (拿一支改判类的假日志验过它会红).
    // py 那侧多出的 4 格是 JS 里量不出差别的 (1 与 1.0、False 与 0 在 JS 里就是同一个值).
    // 交集里唯一的分叉是 prefixItems 那一格, 而它是**方言差**不是实现差: draft-07 的两份参照都
    // 不认这个关键字 (VALID), 2020-12 的两份都认 (RED) ⇒ 与 draft-07 元组写法那处分叉同族,
    // #49 已记过政策 ("认得就管"), 这里不重复.
    // =====================================================================

    @Test
    public void a_const_from_a_real_client_actually_constrains_the_argument() throws Exception {
        // ref50_py_wire.log 里那一份 inputSchema 的字节, 一个字段都不改:
        String s = "{\"properties\":{\"unit\":{\"const\":\"celsius\",\"default\":\"celsius\","
                + "\"title\":\"Unit\",\"type\":\"string\"},\"value\":{\"title\":\"Value\","
                + "\"type\":\"integer\"}},\"required\":[\"value\"],\"title\":\"convertArguments\","
                + "\"type\":\"object\"}";
        assertOk(s, "{\"value\":1,\"unit\":\"celsius\"}");
        // 单位整个不提是合法的 (它不在 required 里) —— 参照两侧都判 VALID,
        // 见两份日志的 "const on an absent optional property" 两行. 这一格防的就是
        // "把缺失的键也顺手判成违规"那种实现.
        assertOk(s, "{\"value\":1}");
        List<String> e = errorsOf(s, "{\"value\":1,\"unit\":\"fahrenheit\"}");
        assertFalse("const 被当成了未知关键字放行: " + e, e.isEmpty());
        assertEquals("一条违规就够, 分支内部不许外溢: " + e, 1, e.size());
        assertTrue("要报在那一个参数上: " + e, e.get(0).startsWith("$.unit:"));
        assertTrue("要说清期望的是哪个常量: " + e, e.get(0).contains("celsius"));
        // 同一个 SDK 对 bool / int 的单值 Literal 也走 const (ref50_py_wire.log 的后两行).
        // 单列这两支是因为它们的常量不是字符串: false / 8 都是"类型合上而常量不合",
        // 与上面 celsius 那一格走的不是同一条相等分支.
        String flag = "{\"const\":true,\"default\":true,\"title\":\"Flag\",\"type\":\"boolean\"}";
        assertOk(flag, "true");
        assertFalse("布尔常量没管住: ", errorsOf(flag, "false").isEmpty());
        String seven = "{\"const\":7,\"default\":7,\"title\":\"N\",\"type\":\"integer\"}";
        assertOk(seven, "7");
        assertFalse("整型常量没管住: ", errorsOf(seven, "8").isEmpty());
    }

    @Test
    public void const_uses_json_equality_not_string_or_java_equality() throws Exception {
        // 对象: 键序无关而值的类型有关 (ajv 与 jsonschema 同判: key order VALID / value type INVALID)
        assertOk("{\"const\":{\"a\":1,\"b\":2}}", "{\"b\":2,\"a\":1}");
        assertFalse(errorsOf("{\"const\":{\"a\":1}}", "{\"a\":\"1\"}").isEmpty());
        // 数组: 次序参与相等
        assertOk("{\"const\":[1,2]}", "[1,2]");
        assertFalse(errorsOf("{\"const\":[1,2]}", "[2,1]").isEmpty());
        assertOk("{\"const\":[]}", "[]");
        // 布尔与数字互不相等, 两个方向都试 (jsonschema: "const number vs true" / "const true vs 1")
        assertFalse(errorsOf("{\"const\":1}", "true").isEmpty());
        assertFalse(errorsOf("{\"const\":true}", "1").isEmpty());
        assertFalse(errorsOf("{\"const\":0}", "false").isEmpty());
        assertFalse(errorsOf("{\"const\":false}", "0").isEmpty());
        // null 只与 null 相等
        assertOk("{\"const\":null}", "null");
        assertFalse(errorsOf("{\"const\":null}", "0").isEmpty());
        // 整值的浮点是同一个 JSON 数值 —— enum 早就是这么判的, const 不能另立一套
        assertOk("{\"const\":1}", "1.0");
        assertOk("{\"const\":1.0}", "1");
        assertFalse(errorsOf("{\"const\":1}", "1.5").isEmpty());
    }

    @Test
    public void const_and_type_each_report_their_own_violation() throws Exception {
        // 参照两侧在这格都交**两条**违规 (ajv: "must be string | must be equal to constant";
        // jsonschema: "'x' was expected | 1 is not of type 'string'") ⇒ const 不能排在 type
        // 那条提前 return 之后, 否则类型一错就再也问不到常量.
        List<String> both = errorsOf("{\"type\":\"string\",\"const\":\"x\"}", "1");
        assertEquals("两条违规该各报各的: " + both, 2, both.size());
        assertEquals("缺的是 const 那一条: " + both, 1, countMatches(both, "is not the constant"));
        assertEquals("缺的是 type 那一条: " + both, 1, countMatches(both, "expected type string"));
        assertOk("{\"type\":\"string\",\"const\":\"x\"}", "\"x\"");
        // const 与 enum 同时在场: 两支独立关键字, 各问各的. 这四格的期望值不是推的 ——
        // 是两份参照 × 双方言逐格量出来的 (ref50 两份日志的 "const+enum both violated" 四行:
        // both violated 交两条, 只合一支时只剩另一支那一条, both ok 全绿).
        // 按消息内容各数一遍而不是只数总数: 光看"2 条"会把"enum 一支冒充两支"读成通过.
        String bothKept = "{\"const\":\"a\",\"enum\":[\"b\",\"c\"]}";
        List<String> neither = errorsOf(bothKept, "\"d\"");
        assertEquals("两条独立关键字该各报一条: " + neither, 2, neither.size());
        assertEquals("const 那一条要在: " + neither, 1, countMatches(neither, "is not the constant"));
        assertEquals("enum 那一条不能丢: " + neither, 1, countMatches(neither, "not in enum"));
        List<String> onlyEnum = errorsOf(bothKept, "\"a\"");
        assertEquals("常量合上而枚举不合, 该只剩 enum 一条: " + onlyEnum, 1, onlyEnum.size());
        assertTrue(onlyEnum.get(0).contains("not in enum"));
        List<String> onlyConst = errorsOf(bothKept, "\"b\"");
        assertEquals("枚举合上而常量不合, 该只剩 const 一条: " + onlyConst, 1, onlyConst.size());
        assertTrue(onlyConst.get(0).contains("is not the constant"));
        assertOk("{\"const\":\"a\",\"enum\":[\"a\",\"c\"]}", "\"a\"");
    }

    @Test
    public void const_reaches_tuple_positions_refs_and_combinators() throws Exception {
        // 只在顶层认 const 等于没认: pydantic 的 const 永远住在 properties 里, 而嵌套模型
        // 住在 $ref 背后 (#48 那一批). 下面每一格的期望值都点着参照里的具体行.
        List<String> pos = errorsOf("{\"type\":\"array\",\"items\":[{\"const\":\"a\"}]}", "[\"b\"]");
        assertFalse("draft-07 元组位置上的 const 没生效: " + pos, pos.isEmpty());
        assertTrue("要报在下标上: " + pos, pos.get(0).startsWith("$[0]:"));
        assertFalse("prefixItems 位置上的 const 没生效: ",
                errorsOf("{\"type\":\"array\",\"prefixItems\":[{\"const\":\"a\"}]}", "[\"b\"]").isEmpty());
        assertOk("{\"type\":\"array\",\"prefixItems\":[{\"const\":\"a\"}]}", "[\"a\"]");
        // 尾巴那份 schema 里的 const (ajv: /1 must be equal to constant)
        String tail = "{\"type\":\"array\",\"items\":[{\"const\":\"a\"}],"
                + "\"additionalItems\":{\"const\":\"z\"}}";
        assertOk(tail, "[\"a\",\"z\"]");
        List<String> tailWrong = errorsOf(tail, "[\"a\",\"b\"]");
        assertFalse("尾巴上的 const 没生效: " + tailWrong, tailWrong.isEmpty());
        assertTrue("要报在尾巴那一格上: " + tailWrong, tailWrong.get(0).startsWith("$[1]:"));
        // $ref 背后 (ref50 两份日志的 "const inside $ref target" 格)
        List<String> viaRef = errorsOf("{\"$ref\":\"#/$defs/C\",\"$defs\":{\"C\":{\"const\":\"a\"}}}", "\"b\"");
        assertFalse("$ref 目标里的 const 没生效: " + viaRef, viaRef.isEmpty());
        // anyOf 分支里 (两份参照: "c is not valid under any of the given schemas")
        String any = "{\"anyOf\":[{\"const\":\"a\"},{\"const\":\"b\"}]}";
        assertOk(any, "\"b\"");
        assertFalse("anyOf 分支里的 const 没生效: ",
                errorsOf(any, "\"c\"").isEmpty());
        // map 的值 (additionalProperties 那份 schema) 里的 const
        assertFalse("additionalProperties 里的 const 没生效: ",
                errorsOf("{\"type\":\"object\",\"additionalProperties\":{\"const\":1}}",
                        "{\"k\":2}").isEmpty());
        assertOk("{\"type\":\"object\",\"additionalProperties\":{\"const\":1}}", "{\"k\":1,\"j\":1}");
    }

    // =====================================================================
    // #51: 数值约束里"不含等号的那一头"与"步长"
    //
    // minimum / maximum 一直是认的, 而 exclusiveMinimum / exclusiveMaximum / multipleOf 躺在
    // "未知关键字不作约束"的政策里 ⇒ 广告「必须 > 0」的参数传 0 照过, 广告「只能是 5 的倍数」的传 11 照过.
    // 期望值不来自规范文本, 来自两份参照 × 两方言的逐格读数:
    //   ajv 8.20.0        tsclient/ref51_num_oracle.js → .log (双方言各 47 格)
    //   jsonschema 4.26.0 ref51_num_oracle.py → _py.log       (双方言各 50 格)
    // "跨实现、同方言的 94 格里只有 draft-04 布尔写法那 2 格分叉"由 ref51_crossdiff.py 现算
    // (例外名单是双向闸: 名单外分叉即 FATAL, 名单内不再分叉也 FATAL).
    // 病相来自线上而不是设想: pydantic 2.12.5 的 Field(gt=0) 交 {"exclusiveMinimum":0}、
    // Field(multiple_of=5) 交 {"multipleOf":5}, 且 Optional 会把它包进 anyOf ——
    // 那一份 wire 字节现读自 ref51_py_wire.py (下面第一支用的就是它交的原样形状).
    // =====================================================================

    @Test
    public void a_strict_bound_from_a_real_client_actually_excludes_the_boundary() throws Exception {
        // 这三份 schema 是 ref51_py_wire.log 里 `scale` / `pick_range` / `sized` 的原样字节, 一个字段没改.
        String floor = "{\"exclusiveMinimum\":0,\"title\":\"Floor Gt\",\"type\":\"integer\"}";
        List<String> atZero = errorsOf(floor, "0");
        assertFalse("Field(gt=0) 的 0 照过", atZero.isEmpty());
        assertTrue("要报在 exclusiveMinimum 上: " + atZero,
                countMatches(atZero, "exclusiveMinimum") == 1);
        assertOk(floor, "1");
        assertFalse(errorsOf(floor, "-1").isEmpty());

        String span = "{\"exclusiveMaximum\":1.0,\"minimum\":0.0,\"title\":\"Lo\",\"type\":\"number\"}";
        assertFalse("Field(ge=0, lt=1) 的 1.0 照过", errorsOf(span, "1.0").isEmpty());
        assertOk(span, "0.0");   // 含等号的那一头仍然按 minimum 判
        assertOk(span, "0.5");

        // Optional 包出来的 anyOf: 严格下界住在分支里, 而 null 走另一支 —— 两条都要成立,
        // 只有一条的"认得"在真实客户端里等于没认.
        String opt = "{\"anyOf\":[{\"exclusiveMinimum\":100,\"type\":\"integer\"},{\"type\":\"null\"}],"
                + "\"default\":null,\"title\":\"Maybe Big\"}";
        assertFalse("anyOf 分支里的 exclusiveMinimum 没生效", errorsOf(opt, "100").isEmpty());
        assertOk(opt, "null");
        assertOk(opt, "101");
    }

    @Test
    public void strict_and_inclusive_bounds_each_report_their_own_violation() throws Exception {
        // 与 #50 的 const/enum 同一个道理: 两条是独立关键字, 各报各的. 断言按关键字分别数条数,
        // 不数总条数 —— 否则"一支冒充另一支"会被读成通过.
        // 取 " < minimum" 而不是 "minimum" 作针: 后者的字符串也出现在 "exclusiveMinimum" 里.
        String both = "{\"minimum\":0,\"exclusiveMinimum\":0}";
        List<String> onlyStrict = errorsOf(both, "0");
        assertEquals("0 只该被 exclusiveMinimum 拒一次: " + onlyStrict, 1, onlyStrict.size());
        assertEquals(1, countMatches(onlyStrict, "exclusiveMinimum"));
        assertEquals(0, countMatches(onlyStrict, " < minimum"));
        List<String> bothFire = errorsOf(both, "-1");
        assertEquals("-1 该被两条各拒一次: " + bothFire, 2, bothFire.size());
        assertEquals(1, countMatches(bothFire, " < minimum"));
        assertEquals(1, countMatches(bothFire, "exclusiveMinimum"));
        // 谁更严听各自的: minimum=5 时 5 过, 而 exclusiveMinimum=1 不参与把 5 拒掉
        assertOk("{\"minimum\":5,\"exclusiveMinimum\":1}", "5");
        assertOk("{\"maximum\":5,\"exclusiveMaximum\":9}", "5");
        // 互相矛盾的两条上界: 参照 (ajv allErrors) 交两条 ("must be < 3 | must be > 5"), 我们也交两条
        List<String> contradictory = errorsOf("{\"exclusiveMinimum\":5,\"exclusiveMaximum\":3}", "4");
        assertEquals(2, contradictory.size());
        assertEquals(1, countMatches(contradictory, "exclusiveMinimum"));
        assertEquals(1, countMatches(contradictory, "exclusiveMaximum"));
        // 非数值实例不参与 (两份参照同档: ajv/jsonschema 都判 VALID)
        assertOk("{\"exclusiveMinimum\":0}", "\"abc\"");
        assertOk("{\"exclusiveMinimum\":0}", "true");
        assertOk("{\"exclusiveMinimum\":0}", "null");
    }

    @Test
    public void multipleOf_asks_whether_it_divides_and_not_what_the_remainder_is() throws Exception {
        assertOk("{\"multipleOf\":5}", "10");
        assertOk("{\"multipleOf\":5}", "0");
        assertOk("{\"multipleOf\":5}", "-10");
        assertOk("{\"multipleOf\":-5}", "10");
        assertOk("{\"multipleOf\":0.25}", "0.75");
        assertFalse(errorsOf("{\"multipleOf\":5}", "11").isEmpty());
        assertFalse(errorsOf("{\"multipleOf\":0.25}", "0.1").isEmpty());
        // 浮点这三格是这一族的承重墙, 逐格抄自两份参照 (跨实现同方言判类相同):
        //   {multipleOf:0.1} 遇 0.2 => 过;   遇 0.3 => **不过** (两家都判红)
        // 所以参照做的不是"容忍浮点误差", 而是字面在问除得尽吗.
        assertOk("{\"multipleOf\":0.1}", "0.2");
        assertFalse("{multipleOf:0.1} 遇 0.3 被放过了", errorsOf("{\"multipleOf\":0.1}", "0.3").isEmpty());
        // 这一格是"商"与"余数"两种写法的分岔点: 两份参照都判**过**, 而 Java 里
        // 1.0 % 1e-8 = 9.99999997907744e-9 (非零) —— 写成余数形式会凭空多一条红.
        assertOk("{\"multipleOf\":1e-8}", "1");
        assertOk("{\"multipleOf\":1e21}", "1e22");
        // 除数为 0: ajv 判违规, jsonschema 直接 RAISED ZeroDivisionError (同一档: 给不出"过")
        assertFalse(errorsOf("{\"multipleOf\":0}", "5").isEmpty());
        // 非数值实例不参与 (参照同档)
        assertOk("{\"multipleOf\":5}", "\"10\"");
        assertOk("{\"multipleOf\":5}", "true");
    }

    @Test
    public void numeric_constraints_reach_tuple_positions_refs_and_combinators() throws Exception {
        // 只在文档根上认数值 = 没认: pydantic 的约束住在 properties 里, 嵌套模型住在 $ref 背后.
        String pos = "{\"type\":\"array\",\"items\":[{\"exclusiveMinimum\":0}]}";
        List<String> atPos = errorsOf(pos, "[0]");
        assertFalse("元组位置上的 exclusiveMinimum 没生效: " + atPos, atPos.isEmpty());
        assertTrue("要报在下标上: " + atPos, atPos.get(0).startsWith("$[0]:"));
        assertOk(pos, "[1]");
        assertFalse("prefixItems 位置上的 exclusiveMinimum 没生效",
                errorsOf("{\"type\":\"array\",\"prefixItems\":[{\"exclusiveMinimum\":0}]}", "[0]").isEmpty());
        String tail = "{\"type\":\"array\",\"items\":[{\"multipleOf\":5}],\"additionalItems\":{\"multipleOf\":5}}";
        assertOk(tail, "[10,15]");
        List<String> tailWrong = errorsOf(tail, "[10,11]");
        assertFalse("尾巴那份 schema 里的 multipleOf 没生效: " + tailWrong, tailWrong.isEmpty());
        assertTrue("要报在尾巴那一格上: " + tailWrong, tailWrong.get(0).startsWith("$[1]:"));
        List<String> viaRef = errorsOf("{\"$ref\":\"#/$defs/L\",\"$defs\":{\"L\":{\"exclusiveMinimum\":0}}}", "0");
        assertFalse("$ref 目标里的 exclusiveMinimum 没生效: " + viaRef, viaRef.isEmpty());
        String any = "{\"anyOf\":[{\"exclusiveMinimum\":0},{\"type\":\"integer\"}]}";
        // 参照那格 (ajv allErrors) 交的是"另一支合上⇒整条过": 0 是 integer, 所以 anyOf 过.
        assertOk(any, "0");
        assertFalse("anyOf 两支都不合时没判红",
                errorsOf("{\"anyOf\":[{\"exclusiveMinimum\":0},{\"exclusiveMaximum\":-5}]}", "0").isEmpty());
        assertFalse("additionalProperties 里的 exclusiveMinimum 没生效",
                errorsOf("{\"type\":\"object\",\"additionalProperties\":{\"exclusiveMinimum\":0}}",
                        "{\"k\":0}").isEmpty());
        // 具名属性上的路径 (properties 的键要出现在指针里, 否则运维拿到的红行没法定位)
        List<String> named = errorsOf(
                "{\"type\":\"object\",\"properties\":{\"floor_gt\":{\"exclusiveMinimum\":0}}}",
                "{\"floor_gt\":0}");
        assertFalse("properties 里的 exclusiveMinimum 没生效: " + named, named.isEmpty());
        assertTrue("路径要点到键名: " + named, named.get(0).startsWith("$.floor_gt:"));
        // 阳性对照: properties 点过名而实例里没这个键时, 子 schema 不参与求值 (参照两家同档)
        assertOk("{\"type\":\"object\",\"properties\":{\"n\":{\"exclusiveMinimum\":0}}}", "{}");
        assertEquals(1, countMatches(
                errorsOf("{\"type\":\"object\",\"properties\":{\"n\":{\"exclusiveMinimum\":0}},"
                        + "\"required\":[\"n\"]}", "{}"), "required"));
    }

    @Test
    public void a_bound_that_is_not_a_number_does_not_participate() throws Exception {
        // 这一支钉的是一个**有意的选择**, 不是一条漏掉的判据.
        // draft-04 把严格下界写成 {"minimum":5,"exclusiveMinimum":true}; 现代客户端不交这个形状
        // (pydantic 2.12.5 与 zod 4.6.5 都交数值写法), 而两份参照在这里自己分叉:
        //   ajv 8.20.0 在 compile 阶段抛 "exclusiveMinimum value must be number" —— 那是**拒绝整份
        //     schema**, 不是"这个实例违规", 与本方法返回的实例级清单不同类;
        //   jsonschema 4.26.0 把它当一个非数值键忽略 ⇒ 5 与 6 都过.
        // 我们站 jsonschema 那一侧, 并且与上面 minimum/maximum 用同一条 house rule (值不配当数值
        // ⇒ 这条不参与). 反过来"照 ajv 办"要把 schema 级拒绝塞进实例级违规里, 那会让每一个合法
        // 实例都变红 —— 那是比漏判更坏的方向.
        assertOk("{\"minimum\":5,\"exclusiveMinimum\":true}", "5");
        assertOk("{\"minimum\":5,\"exclusiveMinimum\":true}", "6");
        List<String> stillInclusive = errorsOf("{\"minimum\":5,\"exclusiveMinimum\":true}", "4");
        assertEquals(1, countMatches(stillInclusive, " < minimum"));
        assertEquals(0, countMatches(stillInclusive, "exclusiveMinimum"));
        assertOk("{\"exclusiveMinimum\":\"0\"}", "1");
        assertOk("{\"multipleOf\":\"5\"}", "10");
        // 上面两格的参照读数要抄准, 因为这里是"没有读数可用"而不是"参照与我们同见":
        //   exMin string value   ⇒ ajv THREW `exclusiveMinimum value must be ["number"]`
        //                          jsonschema RAISED TypeError: '<=' not supported between 'int' and 'str'
        //   multipleOf string value ⇒ ajv THREW `multipleOf value must be ["number"]`
        //                          jsonschema RAISED TypeError: unsupported operand type(s) for %: 'int' and 'str'
        // 两家在这两格都**给不出实例级判决** (一份是 schema 级拒绝, 一份是运行时炸), 所以没有
        // 可照抄的 VALID/INVALID ⇒ 只能沿用自己那条 house rule, 而不是"跟着参照走".
        //
        // 但"不参与"这件事本身要有牙: 光看上面那两格是量不出闸有没有被摘掉的, 因为把
        // `isNumber()` 摘掉之后, 字符串经 `asDouble()` 会被读成 0.0 或它自己的数值, 而实例 1 / 10
        // 在 `<= 0` / `1 % 1` 下**照样过** ⇒ 摘了闸也全绿 (与 #50 记过的"取样值恰好等于内置默认值"
        // 同一形状). 所以再交三格**取值错开**的: 一旦那半条闸不在, 这里就会多出一条红.
        assertOk("{\"exclusiveMinimum\":\"10\"}", "5");
        assertOk("{\"exclusiveMaximum\":\"0\"}", "5");
        assertOk("{\"multipleOf\":\"5\"}", "11");
        // 但"不参与"只针对那一格错类型的值, 不是整条关掉的借口: 同一个对象里换成数值写法,
        // 严格下界仍然要自己报一条.
        List<String> numericForm = errorsOf("{\"minimum\":5,\"exclusiveMinimum\":5.5}", "5.2");
        assertEquals(1, countMatches(numericForm, "exclusiveMinimum"));
        assertEquals(0, countMatches(numericForm, " < minimum"));
    }
}
