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
}
