package com.zifang.z.mcp.server.role;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * 工具入参取值.
 *
 * <p>协议层已经按 inputSchema 校验过类型与 required, 所以这里只把 JSON 值折成 Java 类型;
 * 缺参数只可能是 {@code "text": null} 这种"键在值没"的形状, 那属于校验放过后的边界, 报清楚.
 */
final class ToolArgs {

    private ToolArgs() {}

    static String required(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v == null) throw new IllegalArgumentException("argument '" + name + "' is required");
        return String.valueOf(v);
    }

    static String optional(Map<String, Object> args, String name, String fallback) {
        Object v = args.get(name);
        return v == null ? fallback : String.valueOf(v);
    }

    static int integer(Map<String, Object> args, String name, int fallback) {
        Object v = args.get(name);
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("argument '" + name + "' is not an integer: " + v);
        }
    }

    static boolean flag(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v == null) return false;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return Boolean.parseBoolean(String.valueOf(v).trim().toLowerCase(Locale.ROOT));
    }

    /** 把实现里那一份合法取值表写成 JSON 数组字面量 —— schema 与实现只有一个来源. */
    static String enumLiteral(Collection<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append('"').append(v).append('"');
        }
        return sb.toString();
    }

    static String joinList(Collection<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(v);
        }
        return sb.toString();
    }
}
