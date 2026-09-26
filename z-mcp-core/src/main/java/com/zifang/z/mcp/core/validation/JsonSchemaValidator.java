package com.zifang.z.mcp.core.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JSON Schema 子集校验器 — 协议要求服务端 MUST 校验工具入参.
 *
 * <p>覆盖 MCP 工具 schema 实际会用到的关键字: type / required / properties /
 * items / enum / additionalProperties / minimum / maximum / minLength / maxLength /
 * pattern / minItems / maxItems / nullable. 目标是"够用且不假通过":
 * 认不得的关键字一律忽略(按 JSON Schema 语义, 未知关键字不作约束), 但已支持的关键字
 * 一定给出确定结论.
 *
 * <p>不引第三方校验库的硬约束: z-mcp 全线 Java 8 + Spring Boot 2.7, 官方 MCP Java SDK
 * 及主流 schema 校验器都要求 Java 11/17.
 */
public class JsonSchemaValidator {

    /** @return 违规描述列表, 空表示通过. */
    public List<String> validate(JsonNode schema, JsonNode instance) {
        List<String> errors = new ArrayList<String>();
        if (schema == null || schema.isNull() || !schema.isObject()) return errors;
        walk(schema, instance, "$", errors);
        return errors;
    }

    public boolean isValid(JsonNode schema, JsonNode instance) {
        return validate(schema, instance).isEmpty();
    }

    private void walk(JsonNode schema, JsonNode node, String path, List<String> errors) {
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
            checkObject(schema, node, path, errors);
        } else if (node != null && node.isArray()) {
            checkArray(schema, node, path, errors);
        } else if (node != null && node.isTextual()) {
            checkString(schema, node, path, errors);
        } else if (node != null && node.isNumber()) {
            checkNumber(schema, node, path, errors);
        }
    }

    private void checkObject(JsonNode schema, JsonNode node, String path, List<String> errors) {
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
                if (node.has(key)) walk(props.get(key), node.get(key), path + "." + key, errors);
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
        }
        JsonNode minProps = schema.get("minProperties");
        if (minProps != null && minProps.isInt() && node.size() < minProps.asInt()) {
            errors.add(path + ": expected at least " + minProps.asInt() + " properties");
        }
    }

    private void checkArray(JsonNode schema, JsonNode node, String path, List<String> errors) {
        JsonNode minItems = schema.get("minItems");
        if (minItems != null && minItems.isInt() && node.size() < minItems.asInt()) {
            errors.add(path + ": expected at least " + minItems.asInt() + " items");
        }
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems != null && maxItems.isInt() && node.size() > maxItems.asInt()) {
            errors.add(path + ": expected at most " + maxItems.asInt() + " items");
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            for (int i = 0; i < node.size(); i++) {
                walk(items, node.get(i), path + "[" + i + "]", errors);
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
