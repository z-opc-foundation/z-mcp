package com.zifang.z.mcp.core.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JSON Schema 子集校验器 — 协议要求服务端 MUST 校验工具入参.
 *
 * <p>覆盖 MCP 工具 schema 实际会用到的关键字: type / required / properties /
 * items (单份 schema、布尔、**数组形式**三种) / prefixItems / additionalItems /
 * enum / const / additionalProperties / patternProperties / propertyNames /
 * minimum / maximum / exclusiveMinimum /
 * exclusiveMaximum / multipleOf / uniqueItems / minLength / maxLength /
 * pattern / minItems / maxItems / minProperties / maxProperties / nullable,
 * 外加 refs 与组合子: $ref / $defs /
 * definitions / allOf / anyOf / oneOf / not.
 *
 * <p>对象侧原本只有 required / properties / additionalProperties / minProperties 四条, 而
 * "additionalProperties 的适用范围"这一条写漏了一半: 规范里 "additional" 指的是既不在 properties
 * 里、也没被 patternProperties 盖住的键, 只查 properties 的话, 一份
 * {"patternProperties":{"^k_":...},"additionalProperties":false} 的 schema 会把**每一个**合法键
 * 判成 "unknown property" —— 那是把合法调用拒掉, 比"约束没生效"更糟. 这一族的形状两侧生产者都
 * 交得出来, 但是两条不同的路: pydantic 2.12.5 走 patternProperties (Dict[Annotated[str,
 * StringConstraints(pattern='^k_')], int]) 与 propertyNames (Dict[Literal[...], int] /
 * Dict[枚举, int] / Dict[date, int]), zod 4.6.5 **压根不交 patternProperties** 而是把约束键 record
 * 表达成 {"propertyNames":{"type":"string","pattern":"^k_"},"additionalProperties":{值 schema}}
 * (读数: ref53_py_wire.log / ref53b_py_wire.log / tsclient/ref53_zod_wire.log).
 * 四份参照 × 双方言 71 格同名 (ref53_object_oracle.py 与 tsclient/ref53_object_oracle.js),
 * 逐格对拍由 ref53_crossdiff.py 现算, 三桶账由 cmp53.py 现算, 别混着引:
 *   61 格四家**都给得出判决且同判** —— 这一族才有"照抄"可言, 我们逐格对上 61/61;
 *    5 格四家**分叉** —— 3 格是正则方言 (ECMA-262 与 Python re / java.util.regex 在 `a$`
 *    吃不吃结尾换行、`\z`、`(?i)` 三点上不同), 1 格是子 schema 位置长了字符串 (ajv 静默忽略、
 *    jsonschema 内部崩), 1 格是 draft-07 的 $ref 优先级;
 *    5 格四家**都给不出判决** (ajv 编译期抛 `... value must be ["object","boolean"]` /
 *    jsonschema RAISED AttributeError、TypeError) —— 其中 3 格我们给一条点名路径的判定
 *    (patternProperties 整条不配当对象、模式串本身编译不过、propertyNames 不配当 schema),
 *    2 格按 #51 那条 isNumber 政策让整条不参与 (min/maxProperties 的值是字符串).
 * 粗口径把最后这 5 格也算"同判" (两边都不是 VALID) 于是得到 66 —— 引用 66 之前先想清楚它不等于
 * "66 格都有可照抄的判决". 立场逐条写在 checkObject 的注释与 ref53_crossdiff.py 头部.
 *
 * <p>数组的"元素互异"这一族原本是整条躺在"未知关键字不作约束"里: 广告「tags 只能是互异的
 * 一组值」的参数, 传 ["a","a"] 照过. 这一族同样是真生产者交上来的形状 —— python SDK 1.27.1 +
 * pydantic 2.12.5 的 set[str] / set[int] / frozenset[str] / set[tuple[int,int]] /
 * Optional[set[str]] 在真 list_tools() 的 inputSchema 里一律带 "uniqueItems": true
 * (ref52_py_wire.py → .log, UNIQUE_ITEMS_ON_WIRE=5 正是这五处, 同页对照 list[int] 不带).
 * TS 那侧是反的: zod 4.6.5 认为 set 不可在 JSON Schema 里表示 (ref52_zod_wire.log 的
 * STRICT_MODE 行抛 "Set cannot be represented in JSON Schema", 带 unrepresentable:'any' 时
 * 退化成 {} 即无任何约束) ⇒ UNIQUE_ON_WIRE_TS=0,
 * 所以这一族今天只从 python 生产者与手写 schema 的服务端上来.
 * 判据的两份参照 × 双方言读数在 tsclient/ref52_unique_oracle.js 与 ref52_unique_oracle.py
 * (各 53 格同名), 逐格对拍由 ref52_crossdiff.py 现算: 跨实现、同方言的 106 格里只有 uniqueItems
 * 配了非布尔值那一族 (3 格) 分叉 —— ajv 在编译期抛, jsonschema 按 Python 真值决定它参与.
 * 立场 (两家都不照抄) 写在 checkArray 的注释里.
 *
 * <p>数值那一族原本只有含等号的半条 (minimum / maximum), 不含等号的那一头与步长一起躺在
 * "未知关键字不作约束"里 ⇒ 广告「必须 &gt; 0」的参数, 传 0 照过; 广告「只能是 5 的倍数」的传 11 照过.
 * 这一族同样是真客户端交上来的形状, 而且两家参照都判它: tsclient/ref51_num_oracle.js (ajv 8.20.0
 * × 双方言 47 格) 与 ref51_num_oracle.py (jsonschema 4.26.0 × 双方言 50 格), 逐格对拍由
 * ref51_crossdiff.py 现算 —— 跨实现、同方言的 94 格里只有 draft-04 布尔写法那 2 格分叉,
 * 而那 2 格是"参照自己不同意"的地方, 立场写在 checkNumber 的注释里.
 *
 * <p>长度的"数法"比关键字本身更容易悄悄错: minLength / maxLength 原先拿 {@code String.length()}
 * 量, 那是 UTF-16 **码元**数而不是字符数, 于是一个 emoji 算 2 个. 规范两版措辞一致地指向 RFC 8259
 * 的"字符", 而其 ABNF 里一个字符取值到 %x10FFFF = 一个码点 (§7 另注明 BMP 外的字符写成一对转义代理项)
 * ⇒ 该数码点. 四份参照在这一点上**逐格同判** (56 格网格 ref54_cells.json, 判决
 * ref54_len_oracle_py.log 与 tsclient/ref54_len_oracle.log, 对拍 ref54_crossdiff.py: 50 格共识 +
 * 只有 1 格跨实现分叉 + 5 格无判决), 所以这一条是照抄共识而不是选边. 改前后同一把尺的读数:
 * 与共识同判从 <b>27/50</b> 抬到 <b>50/50</b>, 23 格判定移动 —— 15 格假红 (把生产者自己收得下的载荷
 * 拒掉) + 8 格假绿, 红格总数 26 → 19. 同一轮把 minItems / maxItems 的 {@code isInt()} 闸放宽到
 * {@code isNumber()}, 与 #53 在 min/maxProperties 上定的口径拉平 (四份参照对 {"maxItems":1.5} 配
 * [1,2] 同判红, 老实现在那一格静默). 生产者侧的分量: ref54_len_wire.log 里 maxLength 在 13 个真实
 * FastMCP 形状中出现 7 次、minLength 3 次, 而<b>没有任何</b>生产者交得出非整数界
 * (NON_INTEGER_BOUND_ON_WIRE=0 —— pydantic 对 2.5 / 3.5 / "2" / -1 一律 SchemaError), 所以放宽闸是
 * "与参照同判"而不是"给畸形 schema 开后门"; 值不配当数字时仍整条不参与 (#51 那条政策).
 *
 * <p>后一组不是"顺手做的完备性": 两份官方 SDK 生成的 schema 主要约束就住在里面.
 * python SDK 1.27.1 + pydantic 2 把嵌套模型交成
 * {"$defs":{"M":{...}}, "properties":{"p":{"$ref":"#/$defs/M"}}}、把联合类型交成 anyOf
 * (~/.cache/zmcp_prey/ref48_schema_probe.py 量的是盘上那份包的真字节).
 * TS 那侧量的是盘上 zod 4.6.5 自带的 toJSONSchema (ref48_zod_probe.js → .log, 默认与
 * reused:'ref' 两种都量过): 递归模型在 draft-07 目标下交出 {"allOf":[{"$ref":"#"}]} ——
 * 指针指向**文档根**, 2020-12 目标是裸 {"$ref":"#"}; 抽公共子模型时才出现
 * {"definitions":{"__schema0":{...}}, "properties":{"a":{"$ref":"#/definitions/__schema0"}}},
 * 默认则原地内联. "zod 一定会发 definitions" 是没量过的说法, 所以按容器名认识它不够,
 * 指针本身要能落到根上、落到非标准容器里.
 * 在认识这些关键字之前, 它们携带的每一条实质约束都被"未知关键字不作约束"这条政策放掉了.
 *
 * <p>目标是"够用且不假通过": 认不得的关键字一律忽略(按 JSON Schema 语义, 未知关键字不作约束),
 * 但一旦某个关键字被支持, 它一定给出确定结论 —— 所以**指不到的 $ref 是一次违规, 不是放行**.
 * 只跟文档内的 JSON 指针 ("#" 开头); 跨文档/远程的 $ref 一律**不抓取** —— 抓一次就把一个
 * 校验器变成了 SSRF 的出口. 这一条与参照实现同形: ajv 遇到解不开的 ref 是抛异常
 * (ref48_ajv_oracle.log), 它也从不去取远程 ref.
 *
 * <p>不引第三方校验库的硬约束: z-mcp 全线 Java 8 + Spring Boot 2.7, 官方 MCP Java SDK
 * 及主流 schema 校验器都要求 Java 11/17.
 */
public class JsonSchemaValidator {

    /** @return 违规描述列表, 空表示通过. */
    public List<String> validate(JsonNode schema, JsonNode instance) {
        List<String> errors = new ArrayList<String>();
        // 没有 schema (上游压根没声明) 与"声明了一个不配当 schema 的东西"是两回事:
        // 前者不作约束, 后者按"认得就必须给确定结论"的政策一律红 —— ajv 在这一步是直接抛
        // ("schema must be object or boolean"), jsonschema 同样拒绝 (ref48_bogus_schema_*.log).
        // 这一条不再在这里重复判: walk() 顶层与嵌套同用一枚闸.
        if (schema == null || schema.isNull()) return errors;
        walk(schema, instance, "$", new Ctx(schema), errors);
        return errors;
    }

    public boolean isValid(JsonNode schema, JsonNode instance) {
        return validate(schema, instance).isEmpty();
    }

    /**
     * 一次校验的上下文. 这个类是个共享 bean, 所以状态**必须**待在这里而不是字段上 ——
     * 两条并发请求不能看见彼此的 ref 展开栈.
     */
    private static final class Ctx {
        final JsonNode root;
        /** 当前分支上正在展开的 "$ref 串 @ 实例路径"; 再次撞见同一个组合就是原地打转. */
        final Set<String> expanding = new LinkedHashSet<String>();

        Ctx(JsonNode root) {
            this.root = root;
        }
    }

    private void walk(JsonNode schema, JsonNode node, String path, Ctx ctx, List<String> errors) {
        // 一份 schema 只能是对象或布尔, 顶层与**任何一层**都同这一条闸: 嵌套位置原先没人管,
        // 于是 {"items":["nope"]} 这种"该是 schema 的地方长了别的东西"会一路走到元素检查里
        // 什么关键字都取不到 ⇒ 静默放行. 四份参照在这格上互不一致 (ref49_tuple_oracle.log 与
        // ref49_tuple_oracle_py.log: 两份 draft-07 参照 VALID, ajv-2020 编译期抛,
        // jsonschema-2020 直接 AttributeError 崩) ⇒ 没有"照抄参照"这条路, 按政策 fail closed.
        if (!schema.isBoolean() && !schema.isObject()) {
            errors.add(path + ": a schema must be an object or a boolean but is "
                    + jsonType(schema) + " " + literal(schema) + ", refusing to treat it as no constraint");
            return;
        }
        // 布尔 schema 是最小的那一种约束: true 恒过, false 恒不过.
        // 两份参照校验器都认它 (ref48_bool_schema.log: #/definitions/X 这种非标准容器
        // 也照样按 JSON Pointer 解出来 —— 指针解析与关键字叫什么无关).
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) {
                errors.add(path + ": value " + literal(node) + " rejected by an always-false schema");
            }
            return;
        }

        JsonNode ref = schema.get("$ref");
        if (ref != null) {
            JsonNode target = resolveRef(ref, ctx, path, errors);
            if (target == null) return;
            String key = ref.asText() + "@" + path;
            if (!ctx.expanding.add(key)) {
                // 实例没往前走而 schema 又回到同一处: 参照实现在这里直接爆栈, 我们要一条判定
                errors.add(path + ": $ref " + ref.asText() + " recurs onto the same instance position"
                        + " without consuming it, refusing to expand further");
                return;
            }
            try {
                walk(target, node, path, ctx, errors);
            } finally {
                ctx.expanding.remove(key);
            }
            // 2026-06 起 $ref 明确"像 allOf 一样、不吞同级关键字"; 实测 ajv 也是同级照算
        }

        if (schema.has("enum") && !inEnum(schema.get("enum"), node)) {
            errors.add(path + ": value " + literal(node) + " not in enum " + schema.get("enum"));
        }
        // const 与 enum 是两条独立关键字, 用的是同一套 JSON 相等 (1 与 1.0 同值, 而 1 不等于 true、
        // 0 不等于 False, 对象与键序无关而数组与次序有关). 真值: ajv 8.20.0 的 27 格 × 双方言
        // 与 jsonschema 4.26.0 的 31 格 × 双方言 (tsclient/ref50_const_oracle.log 与
        // ref50_const_oracle_py.log). "跨实现、同方言"那 54 对判类逐格相同这一句有自己的尺:
        // ref50_crossdiff.py 现算并打印 CROSS_IMPL_SAME_DIALECT shared=54 divergent=0, 一旦分叉
        // 它当场 FATAL —— 文档里的手写数字没有尺读过就不算读数.
        // 四方向交集里唯一的分叉是 prefixItems 那一格 (draft-07 不认这个关键字 ⇒ VALID; 2020-12 认
        // ⇒ RED): 那是**方言差**不是实现差, 我们按 #49 立下的"认得就管"站 2020-12 那一侧.
        // 排在 type 那条提前 return **之前**: 参照在两支都不合时交两条违规 ("must be string |
        // must be equal to constant"), 排后面就再也问不到常量. {"const":null} 也照样参与判定 ——
        // 实测 has() 对显式 null 返回 true, 所以这里取 get() 不为绕 has(), 只是少一次查找.
        JsonNode constant = schema.get("const");
        if (constant != null && !jsonEquals(constant, node)) {
            errors.add(path + ": value " + literal(node) + " is not the constant " + literal(constant));
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null) {
            if (typeNode.isArray()) {
                boolean any = false;
                for (JsonNode t : typeNode) if (matchesType(t.asText(), node)) { any = true; break; }
                if (!any) {
                    errors.add(path + ": expected one of " + typeNode + " but got " + jsonType(node));
                    return;
                }
            } else if (!matchesType(typeNode.asText(), node)) {
                errors.add(path + ": expected type " + typeNode.asText() + " but got " + jsonType(node));
                return;
            }
        }

        if (schema.has("nullable") && schema.get("nullable").isBoolean()
                && schema.get("nullable").asBoolean() && node != null && node.isNull()) {
            return;
        }

        if (node != null && node.isObject()) {
            checkObject(schema, node, path, ctx, errors);
        } else if (node != null && node.isArray()) {
            checkArray(schema, node, path, ctx, errors);
        } else if (node != null && node.isTextual()) {
            checkString(schema, node, path, errors);
        } else if (node != null && node.isNumber()) {
            checkNumber(schema, node, path, errors);
        }

        checkCombinators(schema, node, path, ctx, errors);
    }

    /** allOf 逐条把违规直接记进主列表; anyOf/oneOf/not 只看"匹配得上吗", 分支内部不外溢. */
    private void checkCombinators(JsonNode schema, JsonNode node, String path, Ctx ctx,
                                  List<String> errors) {
        JsonNode all = schema.get("allOf");
        if (all != null && all.isArray()) {
            for (JsonNode branch : all) walk(branch, node, path, ctx, errors);
        }
        JsonNode any = schema.get("anyOf");
        if (any != null && any.isArray()) {
            int matched = countMatches(any, node, path, ctx);
            if (matched == 0) {
                errors.add(path + ": value " + literal(node) + " does not match any subschema of anyOf"
                        + " (" + any.size() + " branches)");
            }
        }
        JsonNode one = schema.get("oneOf");
        if (one != null && one.isArray()) {
            int matched = countMatches(one, node, path, ctx);
            if (matched != 1) {
                errors.add(path + ": value " + literal(node) + " matches " + matched + " of " + one.size()
                        + " subschemas but oneOf requires exactly 1");
            }
        }
        JsonNode neg = schema.get("not");
        if (neg != null && !neg.isNull() && matches(neg, node, path, ctx)) {
            errors.add(path + ": value " + literal(node) + " is not allowed by the not subschema");
        }
    }

    private int countMatches(JsonNode branches, JsonNode node, String path, Ctx ctx) {
        int matched = 0;
        for (JsonNode branch : branches) if (matches(branch, node, path, ctx)) matched++;
        return matched;
    }

    /** 分支只回答"合不合得上", 不合时它内部那几条码在何处由调用方决定. */
    private boolean matches(JsonNode sub, JsonNode node, String path, Ctx ctx) {
        List<String> scratch = new ArrayList<String>();
        walk(sub, node, path, ctx, scratch);
        return scratch.isEmpty();
    }

    /**
     * 解析文档内指针. 返回 null 表示"这条 ref 给不出结论", 此时**已经**记了一条违规 ——
     * 解不开的 ref 不等于没有约束.
     */
    private JsonNode resolveRef(JsonNode ref, Ctx ctx, String path, List<String> errors) {
        if (!ref.isTextual()) {
            errors.add(path + ": $ref must be a string but is " + literal(ref));
            return null;
        }
        String uri = ref.asText();
        if (!uri.startsWith("#")) {
            errors.add(path + ": cannot resolve $ref " + uri + " — only in-document JSON pointers"
                    + " are followed, remote refs are never fetched");
            return null;
        }
        String pointer = uri.substring(1);
        JsonNode target = ctx.root;
        if (!pointer.isEmpty()) {
            if (!pointer.startsWith("/")) {
                errors.add(path + ": cannot resolve $ref " + uri
                        + " — a JSON pointer must be empty or start with /");
                return null;
            }
            for (String segment : pointer.substring(1).split("/", -1)) {
                String key = unescapeSegment(percentDecode(segment));
                if (target == null) return reportMissing(ref, path, errors);
                if (target.isObject()) {
                    target = target.get(key);
                } else if (target.isArray()) {
                    Integer idx = index(key);
                    target = idx == null || idx < 0 || idx >= target.size() ? null : target.get(idx);
                } else {
                    return reportMissing(ref, path, errors);
                }
            }
        }
        if (target == null || target.isNull()) return reportMissing(ref, path, errors);
        return target;
    }

    private JsonNode reportMissing(JsonNode ref, String path, List<String> errors) {
        errors.add(path + ": $ref " + ref.asText() + " resolves to nothing in this schema");
        return null;
    }

    private static Integer index(String key) {
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** RFC 6901: ~1 是 "/", ~0 是 "~", 且必须先解 ~1 再解 ~0. */
    private static String unescapeSegment(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    /** 指针里的 %XX 按 UTF-8 解; pydantic 对含空格/中文的模型名就是这么写出来的. */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((byte) (hi * 16 + lo));
                    i += 3;
                    continue;
                }
            }
            // 不是转义就按 UTF-8 原样落, 代理对要整体走 (中文模型名可能就直接写在指针里)
            int cp = s.codePointAt(i);
            byte[] raw = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            bytes.write(raw, 0, raw.length);
            i += Character.charCount(cp);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private void checkObject(JsonNode schema, JsonNode node, String path, Ctx ctx,
                             List<String> errors) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                String key = r.asText();
                JsonNode child = node.get(key);
                if (child == null || child.isNull()) {
                    errors.add(path + ": missing required property '" + key + "'");
                }
            }
        }
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            Iterator<String> it = props.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                if (node.has(key)) walk(props.get(key), node.get(key), path + "." + key, ctx, errors);
            }
        }
        // 被任一 pattern 盖住的键. 它有两个用处: patternProperties 自己的判定, 以及**下一支**
        // additionalProperties 那两条的适用范围 —— 规范里 "additional" 指的是既不在 properties 里、
        // 也没被 patternProperties 盖住的那些键, 漏掉后半句就会把合法调用判成非法 (见下面的注释).
        Set<String> patternCovered = new LinkedHashSet<String>();
        JsonNode patterns = schema.get("patternProperties");
        if (patterns != null && !patterns.isNull()) {
            // 显式 null 当作没写, 与上面 items / 下面 additionalItems 那几支同一口径 (那儿的
            // "关键字配了不配当值的类型"一格四份参照互不一致, 所以走政策而不是照抄某一家).
            if (!patterns.isObject()) {
                errors.add(path + ": patternProperties must be an object of schemas but is "
                        + jsonType(patterns) + " " + literal(patterns) + ", refusing to guess");
            } else {
                Iterator<String> pit = patterns.fieldNames();
                while (pit.hasNext()) {
                    String regex = pit.next();
                    Pattern compiled;
                    try {
                        compiled = Pattern.compile(regex);
                    } catch (PatternSyntaxException e) {
                        errors.add(path + ": malformed pattern in schema: " + e.getMessage());
                        continue;
                    }
                    // find() 而不是 matches(): patternProperties 的 pattern 是**不锚定**的, 与 checkString
                    // 里那支 pattern 同一个口径. 四份参照同判 (ref53 两份日志的 unanchored pattern
                    // substring key: 键 "xk_a" 落在 /k_/ 里 ⇒ 该键受约束 ⇒ RED).
                    Iterator<String> kit = node.fieldNames();
                    while (kit.hasNext()) {
                        String key = kit.next();
                        if (compiled.matcher(key).find()) {
                            patternCovered.add(key);
                            walk(patterns.get(regex), node.get(key), path + "." + key, ctx, errors);
                        }
                    }
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                if ((props == null || !props.has(key)) && !patternCovered.contains(key)) {
                    errors.add(path + ": unknown property '" + key + "' not allowed");
                }
            }
        } else if (additional != null && additional.isObject()) {
            // pydantic 的 Dict[str, X] 就是这个形状: 约束不在 properties 里, 在
            // additionalProperties 这份 **schema** 里 (两份参照校验器都判它有效,
            // 见 ref48_dict_probe.py 与 ref48_ajv_oracle.log).
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                if ((props == null || !props.has(key)) && !patternCovered.contains(key)) {
                    walk(additional, node.get(key), path + "." + key, ctx, errors);
                }
            }
        }
        JsonNode names = schema.get("propertyNames");
        if (names != null && !names.isNull()) {
            // 键本身当字符串实例再过一遍这份子 schema. 这一族**两侧生产者都交得出来**, 而且是两条
            // 不同的路: python 那侧 pydantic 2.12.5 的 Dict[Literal['a','b'], int] 交
            // {"propertyNames":{"enum":["a","b"]}}, Dict[Color,int] 交 {"propertyNames":{"$ref":...}}
            // (枚举被抽进 $defs ⇒ 键约束里可以嵌指针), Dict[date,int] / Dict[UUID4,int] 交
            // {"propertyNames":{"format":...}} (ref53b_py_wire.py 现跑: propertyNames=4);
            // TS 那侧 zod 4.6.5 **压根不交 patternProperties** (ref53_zod_wire.log:
            // ON_WIRE patternProperties=0 / propertyNames=5), 它把约束键 record 表达成
            // {"propertyNames":{"type":"string","pattern":"^k_"},"additionalProperties":{值 schema}}
            // ⇒ 这一族里 propertyNames 是唯一两侧都上得了线的关键字.
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                walk(names, TextNode.valueOf(key), path + "." + key, ctx, errors);
            }
        }
        JsonNode minProps = schema.get("minProperties");
        // 值不配当数字时不参与 (与 #51 在 strict*Bound 上定的政策同一条): 这一格四份参照都是
        // "没法给判决" —— ajv 与 jsonschema 都在 compile/check_schema 阶段拒掉整份 schema
        // (ref53 两份日志的 maxProperties string value 那行). 不比 #52 那三格更糟, 也不照抄.
        //
        // 但**是数字就得参与**, 包括非整数: 上一版这里是 isInt(), 于是 {"minProperties":1.5} 遇
        // {"a":1} 被判成"不参与"⇒ 绿, 而四份参照在这格上同判红 (minProperties float value:
        // ajv 与 jsonschema 都按 1 < 1.5 算). 这一族的参照用的是数值比较而不是整数比较, 所以闸放成
        // isNumber() + 比 double. 当时"旁边的 minItems / maxLength 那一族没量过, 不跟着改"这句
        // 只写了半天 —— #54 把那一族量了 (56 格网格) 并且**同样**放宽了闸, 因为四份参照在
        // {"maxItems":1.5} 配 [1,2] 上也是同判红; 见 checkArray / checkString.
        if (minProps != null && minProps.isNumber() && node.size() < minProps.asDouble()) {
            errors.add(path + ": expected at least " + literal(minProps) + " properties");
        }
        JsonNode maxProps = schema.get("maxProperties");
        if (maxProps != null && maxProps.isNumber() && node.size() > maxProps.asDouble()) {
            errors.add(path + ": expected at most " + literal(maxProps) + " properties");
        }
    }

    private void checkArray(JsonNode schema, JsonNode node, String path, Ctx ctx,
                            List<String> errors) {
        JsonNode minItems = schema.get("minItems");
        // 与 #53 在 min/maxProperties 上定的口径拉平: 这一族两支上一版写的都是 isInt(), 于是
        // {"maxItems":1.5} 遇 [1,2] 被判成"值不配当整数所以不参与"⇒ 绿, 而四份参照在这一格同判红
        // (网格第 29 格 maxItems float value violated side: ajv 与 jsonschema 都按数值比较算 2 > 1.5)。
        // 两支一起放宽是因为它们是同一条政策的对称面; 但要写清楚**量到的边界**: 56 格里带小数的
        // 下界只有 minItems 的"合上侧" (第 30 格 VALID) 与 minLength 的"违例侧" (第 28 格),
        // 而 {"minItems":2.5} 遇 [1,2] 这一格没进网格 ⇒ 那一支的判定是我们的立场, 不是照抄.
        // 值不配当数字时仍不参与 (#51 那条政策; 生产者交不出这种界, 见 ref54_len_wire.log B 段)。
        if (minItems != null && minItems.isNumber() && node.size() < minItems.asDouble()) {
            errors.add(path + ": expected at least " + literal(minItems) + " items");
        }
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems != null && maxItems.isNumber() && node.size() > maxItems.asDouble()) {
            errors.add(path + ": expected at most " + literal(maxItems) + " items");
        }
        JsonNode uniqueItems = schema.get("uniqueItems");
        // 放在 items / prefixItems 那几支的 return **之前**: 互异与位置约束无关。"位置子 schema 里
        // 也有互异"那两格 (ref52 两份日志的 inside items schema / over tuple-shaped items) 四份参照
        // 同判重复; 数组形式 items 本身当位置用的那一格 (inside tuple items array) 只有 draft-07 那
        // 两家判重复, 2020-12 那两家一份崩一份拒 (jsonschema RAISED / ajv THREW) —— 那是方言差不是
        // 实现差, 按 #49 定的"认得就管"照 draft-07 那侧办。
        //
        // 只有布尔 true 起约束。值不配当布尔时两家参照互相矛盾, 而且各错一半 ——
        // ajv 编译期抛 `uniqueItems value must be ["boolean"]` (整份 schema 被拒), jsonschema 按
        // Python 真值让它参与 (于是 {"uniqueItems":"false"} 那种明显不想要约束的写法反而要求互异).
        // 这三格是 #52 里唯一"跨实现、同方言"分叉的一族 (ref52_crossdiff.py 现算: 共有 106 格里
        // 只有这 3 格分叉), 没有可照抄的一致读数, 所以沿用 #51 在同一类问题上定的政策:
        // 值不配当这个关键字的类型 ⇒ 不参与, 既不抛也不当真值.
        if (uniqueItems != null && uniqueItems.isBoolean() && uniqueItems.asBoolean()) {
            int[] dup = firstDuplicate(node);
            if (dup != null) {
                errors.add(path + ": items ## " + dup[0] + " and " + dup[1]
                        + " are identical but uniqueItems is true");
            }
        }
        JsonNode items = schema.get("items");
        JsonNode prefix = schema.get("prefixItems");
        JsonNode additional = schema.get("additionalItems");

        if (prefix != null && !prefix.isNull()) {
            // 2020-12 的元组写法: 位置约束住在 prefixItems 里, 尾巴归 items.
            // prefixItems 不是数组时两份 2020-12 参照一个抛一个崩, 两份 draft-07 参照看不见它而放行
            // ⇒ 没有可照抄的读数, 按政策给确定结论 (ref49_tuple_oracle.log / _py.log 的 policy 行).
            if (!prefix.isArray()) {
                errors.add(path + ": prefixItems must be an array of schemas but is "
                        + jsonType(prefix) + " " + literal(prefix) + ", refusing to guess");
                return;
            }
            if (items != null && items.isArray()) {
                // 两种元组写法同时出现时它们互相矛盾 (谁管尾巴都不成立), 参照之间也各说各话.
                errors.add(path + ": prefixItems and an array-form items are both present,"
                        + " refusing to guess which tuple spelling governs");
                return;
            }
            checkPositions(prefix, node, path, ctx, errors);
            // 四份参照对 "prefixItems + additionalItems" 一律 VALID: 尾巴只由 items 管.
            checkTail(items, node, path, prefix.size(), ctx, errors, "items");
            return;
        }
        if (items != null && items.isArray()) {
            // draft-07 的元组写法, 也是**真客户端实际交来的那一种**: 官方 TS SDK 自己的校验器
            // 是裸 draft-07 ajv (node_modules/@modelcontextprotocol/sdk/dist/cjs/
            // validation/ajv-provider.js: new Ajv({strict:false, validateFormats:true,
            // validateSchema:false, allErrors:true})), 而 zod 4 的 z.tuple() 在两种 target 下交的
            // 都是 {"items":[...],"additionalItems":false,"minItems":n,"maxItems":n}
            // (ref48_zod_probe.log 的 tuple 两行) —— 连 target 选 2020-12 时也是这一种, 而那一种
            // 字节在真 2020-12 校验器里编译不过. 所以这里不按方言分派, 认得就管.
            checkPositions(items, node, path, ctx, errors);
            checkTail(additional, node, path, items.size(), ctx, errors, "additionalItems");
            return;
        }
        if (items != null && !items.isNull()) {
            // 一份 schema 管所有元素. 布尔子 schema 两份参照都认 (ref48_bool_schema.log
            // 量了顶层、properties 里、$ref 指进去三个位置), 所以交给 walk() 的布尔分支.
            // 此时并行的 additionalItems 不作约束 —— 四份参照对 "items 是单份 schema 而另有
            // additionalItems" 全部判 VALID (ref49 两份日志最后一条 policy 格).
            for (int i = 0; i < node.size(); i++) {
                walk(items, node.get(i), path + "[" + i + "]", ctx, errors);
            }
        }
    }

    /** 元组前 N 格各自过一份子 schema; 实例短于 N 就只比已有的那些. */
    private void checkPositions(JsonNode positional, JsonNode node, String path, Ctx ctx,
                                List<String> errors) {
        int n = Math.min(node.size(), positional.size());
        for (int i = 0; i < n; i++) {
            walk(positional.get(i), node.get(i), path + "[" + i + "]", ctx, errors);
        }
    }

    /** 元组尾巴: 第 from 个之后的元素过一遍 tail; `false` 就等于"不许有尾巴". */
    private void checkTail(JsonNode tail, JsonNode node, String path, int from, Ctx ctx,
                           List<String> errors, String keyword) {
        if (tail == null || tail.isNull()) return;
        if (!tail.isBoolean() && !tail.isObject()) {
            errors.add(path + ": " + keyword + " must be an object or a boolean but is "
                    + jsonType(tail) + " " + literal(tail) + ", refusing to treat it as no constraint");
            return;
        }
        for (int i = from; i < node.size(); i++) {
            walk(tail, node.get(i), path + "[" + i + "]", ctx, errors);
        }
    }

    private void checkString(JsonNode schema, JsonNode node, String path, List<String> errors) {
        String s = node.asText();
        // 数的是**码点**, 不是 java.lang.String.length() 那个 UTF-16 码元数 (理由与四份参照的逐格
        // 同判见 codePointLength 的注释)。
        int len = codePointLength(s);
        JsonNode minLen = schema.get("minLength");
        if (minLen != null && minLen.isNumber() && len < minLen.asDouble()) {
            errors.add(path + ": string shorter than minLength " + literal(minLen));
        }
        JsonNode maxLen = schema.get("maxLength");
        if (maxLen != null && maxLen.isNumber() && len > maxLen.asDouble()) {
            errors.add(path + ": string longer than maxLength " + literal(maxLen));
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null && pattern.isTextual()) {
            try {
                if (!Pattern.compile(pattern.asText()).matcher(s).find()) {
                    errors.add(path + ": does not match pattern " + pattern.asText());
                }
            } catch (PatternSyntaxException e) {
                errors.add(path + ": malformed pattern in schema: " + e.getMessage());
            }
        }
    }

    private void checkNumber(JsonNode schema, JsonNode node, String path, List<String> errors) {
        double v = node.asDouble();
        JsonNode min = schema.get("minimum");
        if (min != null && min.isNumber() && v < min.asDouble()) {
            errors.add(path + ": " + literal(node) + " < minimum " + min.asDouble());
        }
        JsonNode max = schema.get("maximum");
        if (max != null && max.isNumber() && v > max.asDouble()) {
            errors.add(path + ": " + literal(node) + " > maximum " + max.asDouble());
        }
        // exclusiveMinimum / exclusiveMaximum 是同一族里**不含等号**的那一头: 旧版只做了上面两条,
        // 于是广告「必须 > 0」的参数, 0 照过. 真生产者就交这个形状 —— 官方 python SDK 1.27.1 +
        // pydantic 2.12.5 的 `Field(gt=0)` 交 {"exclusiveMinimum":0}, zod 4.6.5 的 `z.number().gt(0)`
        // 在 draft-07 与 2020-12 两个 target 下都交同一份数值写法 (ref51_py_wire.log 现读;
        // tsclient/ref51_num_oracle.js 头部记着 zod 那侧的逐格输出).
        // 真值: ajv 8.20.0 × 两方言 与 jsonschema 4.26.0 × 两方言, 跨实现同方言共有的 94 格里
        // 只有 draft-04 的布尔写法那 2 格分叉 (ref51_crossdiff.py 现算并加双向闸).
        // 那 2 格我们站 jsonschema 一侧: 值不配当数值 ⇒ 这条不参与, 与上面 minimum/maximum 用的
        // `isNumber()` 同一条 house rule —— ajv 是在 compile 阶段直接把整份 schema 抛掉的,
        // 那是"拒绝这份 schema"而不是"这个实例违规", 与本方法的返回形状(实例级违规清单)不同类.
        JsonNode exMin = schema.get("exclusiveMinimum");
        if (exMin != null && exMin.isNumber() && v <= exMin.asDouble()) {
            errors.add(path + ": " + literal(node) + " is not > exclusiveMinimum " + exMin.asDouble());
        }
        JsonNode exMax = schema.get("exclusiveMaximum");
        if (exMax != null && exMax.isNumber() && v >= exMax.asDouble()) {
            errors.add(path + ": " + literal(node) + " is not < exclusiveMaximum " + exMax.asDouble());
        }
        // multipleOf 取"商的小数部分"而不是"余数", 因为参照两家都是这么判的: {multipleOf:1e-8} 遇 1,
        // 两份参照都说**过**, 而 `1.0 % 1e-8` 在 IEEE double 上是 9.99999997907744e-9 (非零) ⇒
        // 写成余数形式会凭空多判一条红. 反过来 {multipleOf:0.1} 遇 0.3 两家都判红,
        // `(0.3/0.1)%1` = 0.9999999999999996 正是非零 —— 所以这一格不是"参照容忍了浮点误差",
        // 而是它们真的在问"除得尽吗". 除数为 0 时商是 NaN, `NaN != 0` 成立 ⇒ 判违规, 与 ajv 同档
        // (jsonschema 那侧直接 RAISED ZeroDivisionError, 也归 RED 一档).
        JsonNode step = schema.get("multipleOf");
        if (step != null && step.isNumber()) {
            double quotientRemainder = (v / step.asDouble()) % 1.0;
            if (quotientRemainder != 0.0) {
                errors.add(path + ": " + literal(node) + " is not a multiple of " + step.asDouble());
            }
        }
    }

    private static boolean inEnum(JsonNode enumNode, JsonNode value) {
        if (value == null) return false;
        for (JsonNode candidate : enumNode) if (jsonEquals(candidate, value)) return true;
        return false;
    }

    /**
     * JSON 意义上的相等, enum / const / uniqueItems 三处共用 (规范里 const 就是"单值 enum").
     *
     * <p>对象比键值集合因而与书写顺序无关、数组按次序比、字符串不比 Java 引用: 这些是同类型下的
     * {@link JsonNode#equals} 给的. 跨数字类型与**嵌套**那两层它都不给, 所以自己走:
     * {@code IntNode(1).equals(DoubleNode(1.0))} 在顶层是 false (实测 jackson-databind
     * 2.13.5 / 2.15.4 / 2.18.9 三版一致, ref50_jackson_probe.log), {@code [[1],[1.0]]} 与
     * {@code [{"a":1},{"a":1.0}]} 在嵌套层也是 false (同三版, ref52_jackson_nested.log),
     * 而 JSON 里它们都是同一个数值 —— 两份参照校验器每一格都判"同一个值"
     * (ref50 两份日志的 "const int vs int-valued float"、ref52 两份日志的
     * "nested int vs float in array" / "numeric in object leaf").
     */
    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (a.isNumber() && b.isNumber()) return sameNumber(a, b);
        // Jackson 的 equals 是"逐节点按类型比"的, 嵌套一层就不再认数值相等 —— 实测
        // (~/.cache/zmcp_prey/ref52_jackson_nested.log, databind 2.13.5 / 2.15.4 / 2.18.9 三版一致)
        // [[1],[1.0]] 与 [{"a":1},{"a":1.0}] 都是 equals=false, 而四份参照校验器都判这两格"重复"
        // (ref52 两份日志的 "nested int vs float in array" / "numeric in object leaf").
        // 所以这里自己走下去.
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (Iterator<String> it = a.fieldNames(); it.hasNext();) {
                String field = it.next();
                // b 少这个字段时 get 返回 null, jsonEquals(.., null) 在上面那行就是 false.
                // 键序不参与 (Jackson 的 equals 本来也不看序, 实测 "key order swapped" 那格是 true).
                if (!jsonEquals(a.get(field), b.get(field))) return false;
            }
            return true;
        }
        return false;
    }

    /** 溢出成 Infinity 的 double 没有 BigDecimal 表示 (decimalValue() 抛 NumberFormatException,
     *  三版 databind 同读数, ref52_bignum_java.log), 而 {"tags": [1e1000, 5]} 这样的请求体真能到这儿
     *  —— 那一条退回按 double 比, 两家参照在 IEEE double 上与我们同一条路 (四份都把这两个饱和成 inf
     *  并判重复, 见两份 oracle 日志的 `uniqueItems both overflow to infinity` 四行). */
    private static boolean sameNumber(JsonNode a, JsonNode b) {
        try {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        } catch (NumberFormatException e) {
            return a.asDouble() == b.asDouble();
        }
    }

    /**
     * 第一对重复元素的下标; 没有重复则 null.
     *
     * <p>只给一条判定是量出来的, 不是推的: 新增那一格 {@code [1,1,2,2,3,3]} (三对重复) 在四份参照
     * 里都只回一条消息 —— ajv 两方言 "must NOT have duplicate items (items ## 4 and 5 are
     * identical)", jsonschema 两方言 "[1, 1, 2, 2, 3, 3] has non-unique elements"
     * (ref52 两份日志的 three duplicate pairs 四行). 至于报**哪**一对两家自己不一致 (单对那格 ajv
     * 报 0 and 1, 三对那格报 4 and 5; jsonschema 干脆不给下标), 所以这里取"第一对"是一个选择,
     * 依据只有那条: 逐对铺开会比参照多红, 而参照的判定数才是被钉住的那一半。
     */
    private static int[] firstDuplicate(JsonNode array) {
        for (int i = 0; i < array.size(); i++) {
            for (int j = i + 1; j < array.size(); j++) {
                if (jsonEquals(array.get(i), array.get(j))) return new int[] {i, j};
            }
        }
        return null;
    }

    private static boolean matchesType(String expected, JsonNode node) {
        if (node == null) return false;
        if ("null".equals(expected)) return node.isNull();
        switch (expected) {
            case "object": return node.isObject();
            case "array": return node.isArray();
            case "string": return node.isTextual();
            case "boolean": return node.isBoolean();
            case "number": return node.isNumber();
            case "integer":
                return node.isIntegralNumber()
                        || (node.isNumber() && node.asDouble() == Math.floor(node.asDouble()));
            default: return true; // 未知 type 值不构成约束
        }
    }

    private static String jsonType(JsonNode node) {
        if (node == null) return "missing";
        if (node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "boolean";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber()) return "number";
        return "unknown";
    }

    private static String literal(JsonNode node) {
        if (node == null) return "null";
        return node.isTextual() ? "'" + node.asText() + "'" : node.toString();
    }

    /** minLength / maxLength 的"长度" = RFC 8259 意义上的字符数, 一个字符 = 一个 Unicode 码点.
     *
     * <p>规范两版措辞一致 (draft-07 §6.3 与 2020-12 §6.3.1 都写作 "the number of its characters as
     * defined by RFC 7159/8259"), 而 RFC 8259 §2 的 ABNF 里 {@code unescaped} 取值到 %x10FFFF ——
     * 语法符号是码点, BMP 之外的那一个字符在 JSON 文本里写成**一对**被转义的代理项 (§7)。
     *
     * <p>四份参照在这一条上**逐格同判**, 所以这不是"选边"而是照抄共识 (56 格网格见
     * ref54_cells.json, 判决在 ref54_len_oracle.log 与 ref54_len_oracle_py.log):
     * {"maxLength":3} 遇三个 emoji (3 码点 / 6 码元) 全 VALID, {"minLength":2} 遇一个 emoji 全
     * INVALID。而 java.lang.String.length() 数的是码元 ⇒ 上一版两个方向都反: 上界把生产者自己
     * 收得下的载荷判成非法 (假红, 且 pydantic 2.12.5 真交得出这种 schema ——
     * ref54_len_wire.log 的 maxLength=7 那批形状里), 下界把它拒收的载荷放行 (假绿)。
     *
     * <p>码点这一侧还有个附带的读数: 落单的代理项在这里算 1 (与 Python 的 len() 与 ajv 的实现同),
     * 而不是"一段非法文本"。
     */
    private static int codePointLength(String s) {
        return s.codePointCount(0, s.length());
    }
}
