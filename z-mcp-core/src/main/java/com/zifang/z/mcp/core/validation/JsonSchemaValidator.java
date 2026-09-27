package com.zifang.z.mcp.core.validation;

import com.fasterxml.jackson.databind.JsonNode;

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
 * items / enum / additionalProperties / minimum / maximum / minLength / maxLength /
 * pattern / minItems / maxItems / nullable, 外加 refs 与组合子: $ref / $defs /
 * definitions / allOf / anyOf / oneOf / not.
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
        if (schema == null || schema.isNull()) return errors;
        if (!schema.isObject() && !schema.isBoolean()) {
            errors.add("$: a schema must be an object or a boolean but is "
                    + jsonType(schema) + " " + literal(schema) + ", refusing to treat it as no constraint");
            return errors;
        }
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
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                if (props == null || !props.has(key)) {
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
                if (props == null || !props.has(key)) {
                    walk(additional, node.get(key), path + "." + key, ctx, errors);
                }
            }
        }
        JsonNode minProps = schema.get("minProperties");
        if (minProps != null && minProps.isInt() && node.size() < minProps.asInt()) {
            errors.add(path + ": expected at least " + minProps.asInt() + " properties");
        }
    }

    private void checkArray(JsonNode schema, JsonNode node, String path, Ctx ctx,
                            List<String> errors) {
        JsonNode minItems = schema.get("minItems");
        if (minItems != null && minItems.isInt() && node.size() < minItems.asInt()) {
            errors.add(path + ": expected at least " + minItems.asInt() + " items");
        }
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems != null && maxItems.isInt() && node.size() > maxItems.asInt()) {
            errors.add(path + ": expected at most " + maxItems.asInt() + " items");
        }
        JsonNode items = schema.get("items");
        if (items != null && !items.isNull()) {
            // items 也可以是一份布尔 schema —— 布尔子 schema 两份参照都认 (ref48_bool_schema.log
            // 量了顶层、properties 里、$ref 指进去三个位置), 所以这里不特殊处理, 交给 walk()
            // 的布尔分支. 而 zod 4 的 tuple 实测交的是**数组**形式 items + additionalItems:false
            // (ref48_zod_probe.log), 那一种不认, 见 README「已知边界」.
            for (int i = 0; i < node.size(); i++) {
                walk(items, node.get(i), path + "[" + i + "]", ctx, errors);
            }
        }
    }

    private void checkString(JsonNode schema, JsonNode node, String path, List<String> errors) {
        String s = node.asText();
        JsonNode minLen = schema.get("minLength");
        if (minLen != null && minLen.isInt() && s.length() < minLen.asInt()) {
            errors.add(path + ": string shorter than minLength " + minLen.asInt());
        }
        JsonNode maxLen = schema.get("maxLength");
        if (maxLen != null && maxLen.isInt() && s.length() > maxLen.asInt()) {
            errors.add(path + ": string longer than maxLength " + maxLen.asInt());
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
    }

    private static boolean inEnum(JsonNode enumNode, JsonNode value) {
        if (value == null) return false;
        for (JsonNode candidate : enumNode) {
            if (candidate.equals(value)) return true;
            if (candidate.isNumber() && value.isNumber()
                    && Double.compare(candidate.asDouble(), value.asDouble()) == 0) return true;
        }
        return false;
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
}
