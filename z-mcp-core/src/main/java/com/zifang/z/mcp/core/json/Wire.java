package com.zifang.z.mcp.core.json;

import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON-RPC 的线格式门面 —— 换掉 Jackson 树 API 之后, 本仓唯一的 JSON 入口。
 *
 * <p><b>为什么需要它而不是直接用 {@link JsonUtil}</b>: {@code JsonUtil.toJson} 认得
 * {@link JsonObject}/{@link JsonArray}, 但走的是它们的 {@code toString()}, 那是一条
 * {@code BeautifyJsonUtils} 的缩进多行路径。而 JSON-RPC 的响应必须是<b>单行</b> ——
 * SSE 的 {@code data:} 帧、stdio 的行分隔、以及所有客户端的按行读都建立在这一条上。
 * 实测(非推断):
 * <pre>
 *   JsonUtil.toJson(newTree)                    =&gt; 多行缩进
 *   JsonUtil.toJson(JsonUtil.toPlainValue(tree)) =&gt; 单行, 与 Jackson 逐字节相同
 * </pre>
 * 这个坑收在这里, 全仓就不必每处都记得先 {@code toPlainValue}。
 *
 * <p><b>类型判别为什么也收在这里</b>: {@link JsonCursor} 只给 {@code isScalar()},
 * 没有 Jackson 那套 {@code isTextual()} / {@code isNumber()} / {@code isIntegralNumber()}。
 * JSON Schema 校验与 JSON-RPC 的 id 分型(字符串 {@code "1"} 与数字 {@code 1} 是两个请求)
 * 都依赖这个区分, 所以判别一律看值本身的类型, 不看游标。
 *
 * <p>节点读出来可能是 {@link JsonObject}/{@link JsonArray}, 也可能是原生
 * {@link Map}/{@link List}(宿主交回的参数、以及 {@link #toMap} 的产物都是后者),
 * 两种都要认。
 */
public final class Wire {

    private Wire() {
    }

    // ------------------------------------------------------------ 线格式

    /** 单行 JSON。树先摊平成普通 Map/List 再序列化 —— 见类注释里那个实测。 */
    public static String write(Object value) {
        Object plain = value instanceof JsonObject || value instanceof JsonArray
                ? JsonUtil.toPlainValue(value) : value;
        rejectUnrepresentable(plain, "");
        return JsonUtil.toJson(plain);
    }

    /**
     * 写之前先把"z-util 会悄悄写成 {@code {}}"的那些值挑出来报错。
     *
     * <p>z-util 这一族只认 JSON 自身的类型; 落进来一个别的对象(POJO、空 {@code new Object()}、
     * {@code Thread} 之类)时它不抛, 而是把它当一个没有可枚举字段的对象写成 {@code {}} ——
     * 实测 {@code JsonUtil.toJson(new Bean())} 与 {@code JsonUtil.toJson(map("count", new Object()))}
     * 分别得到 {@code {}} 与 {@code {"count":{}}}。Jackson 那一侧是反射读 getter, 所以同一份值
     * 过去能写出内容, 现在会静默丢成空对象: **协议上仍然是一份合法 JSON, 只是内容没了**,
     * 对端无从察觉, 而这正是工具结果那一格最不能出的错。
     *
     * <p>所以这里选择 fail closed: 遇到写不出内容的值就抛, 让调用点那圈 {@code catch} 出
     * {@code isError=true} 加一句"这个值序列化不了" —— 与迁移前的读数一致, 也比发一份空对象
     * 出去可查。
     *
     * <p>查的是整棵树而不只是顶层: 工具作者手搭的 {@code structuredContent} 常常是一棵
     * {@link JsonObject}, POJO 埋在它某一格上, 只查顶层的话那格照样被写成 {@code {}}。
     * {@link #write} 因此先把树摊平再查 —— 摊平后的产物只剩 Map/List/标量, 遍历到底即可。
     */
    private static void rejectUnrepresentable(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Map || value instanceof List
                || value instanceof JsonObject || value instanceof JsonArray) {
            if (value instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    rejectUnrepresentable(e.getValue(), path + "." + e.getKey());
                }
            } else if (value instanceof List) {
                List<?> l = (List<?>) value;
                for (int i = 0; i < l.size(); i++) {
                    rejectUnrepresentable(l.get(i), path + "[" + i + "]");
                }
            }
            return;
        }
        throw new IllegalArgumentException("cannot serialize " + (path.isEmpty() ? "value" : path)
                + ": " + value.getClass().getName()
                + " is not a JSON type (map/list/string/number/boolean/null), and writing it"
                + " anyway would emit an empty object and silently drop its content");
    }

    /** 解析任意 JSON。形态不可判时(以及非法文本)返回 {@code null}, 由调用方走兜底。 */
    public static Object read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JsonUtil.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 解析并把失败**原样抛出去** —— 与 {@link #read} 的区别就是异常去哪儿。
     *
     * <p>入口那几处要区分"这不是 JSON"与"这是空的": 两种情形的 HTTP 状态与 error code 一样,
     * 但 message 不同, 而排查时能看见解析器说的是什么, 差的是几分钟和几小时。
     */
    public static Object readOrThrow(String json) {
        return JsonUtil.parse(json);
    }

    /** 空对象节点 —— 替 {@code mapper.createObjectNode()}。 */
    public static JsonObject object() {
        return new JsonObject();
    }

    /** 空数组节点 —— 替 {@code mapper.createArrayNode()}。 */
    public static JsonArray array() {
        return new JsonArray();
    }

    // ------------------------------------------------------------ 类型判别

    public static boolean isObject(Object v) {
        return v instanceof JsonObject || v instanceof Map;
    }

    public static boolean isArray(Object v) {
        return v instanceof JsonArray || v instanceof List;
    }

    public static boolean isNull(Object v) {
        return v == null;
    }

    public static boolean isTextual(Object v) {
        return v instanceof String || v instanceof Character;
    }

    public static boolean isBoolean(Object v) {
        return v instanceof Boolean;
    }

    public static boolean isNumber(Object v) {
        return v instanceof Number;
    }

    /** 整型数字 —— Jackson {@code isIntegralNumber()} 的等价物。 */
    public static boolean isIntegral(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Byte || v instanceof BigInteger;
    }

    /** 既非对象也非数组 —— JSON 标量(含 null 缺席的情形由调用方自行判)。 */
    public static boolean isScalar(Object v) {
        return !isObject(v) && !isArray(v);
    }

    /**
     * 节点取文本。Jackson 的 {@code asText()} 对数字/布尔也返回字符串形态, 这里同义 ——
     * JSON-RPC 的 {@code method} 槽用得上这条(它比 {@code isTextual()} 更宽松)。
     */
    public static String asText(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    // ------------------------------------------------------------ 导航

    /** 取对象字段; 不是对象返回 {@code null}(Jackson 对非对象 {@code get} 也返回 null)。 */
    public static Object get(Object node, String field) {
        if (node instanceof JsonObject) {
            return ((JsonObject) node).get(field);
        }
        if (node instanceof Map) {
            return ((Map<?, ?>) node).get(field);
        }
        return null;
    }

    /** 取数组下标; 越界或不是数组返回 {@code null}。 */
    public static Object get(Object node, int index) {
        if (node instanceof JsonArray) {
            JsonArray a = (JsonArray) node;
            return index < 0 || index >= a.size() ? null : a.get(index);
        }
        if (node instanceof List) {
            List<?> l = (List<?>) node;
            return index < 0 || index >= l.size() ? null : l.get(index);
        }
        return null;
    }

    public static boolean has(Object node, String field) {
        return get(node, field) != null;
    }

    /**
     * 键是否存在 —— 与 {@link #has} 的区别正是本仓最要紧的一处语义差。
     *
     * <p>Jackson 的 {@code JsonNode.has()} 对<b>显式 null</b> 也返 true(键在, 值是 null 节点),
     * 而 z-util 的树把 JSON null 落成 Java {@code null}, 于是按值判的 {@link #has} 会把
     * {@code {"const": null}} 看成"没有 const"。JSON Schema 恰恰在这一点上有明确要求 ——
     * {@code {"const": null}} 是要参与比对的常量约束, {@code {}} 才是没有这一条。
     * 所以判"这一条 schema 关键字在不在"一律用本方法, 别用 {@link #has}。
     */
    public static boolean hasKey(Object node, String field) {
        if (node instanceof JsonObject) {
            return ((JsonObject) node).getAllKeyValue().stream()
                    .anyMatch(e -> field.equals(e.getKey()));
        }
        if (node instanceof Map) {
            return ((Map<?, ?>) node).containsKey(field);
        }
        return false;
    }

    /** 违规描述里显示值用的字面量: 字符串加引号, 其余按紧凑 JSON 原样。 */
    public static String literal(Object node) {
        if (node == null) {
            return "null";
        }
        return isTextual(node) ? "'" + asText(node) + "'" : write(node);
    }

    /** 违规描述里显示类型用的名字 —— 与 JSON Schema 的 type 命名一致。 */
    public static String jsonType(Object node) {
        if (node == null) {
            return "missing";
        }
        if (isObject(node)) {
            return "object";
        }
        if (isArray(node)) {
            return "array";
        }
        if (isTextual(node)) {
            return "string";
        }
        if (isBoolean(node)) {
            return "boolean";
        }
        if (isIntegral(node)) {
            return "integer";
        }
        if (isNumber(node)) {
            return "number";
        }
        return "null";
    }

    /**
     * 数组长度或对象字段数 —— 替 {@code JsonNode.size()}(它在容器上是同一个意思)。
     * 标量返 0。
     */
    public static int size(Object node) {
        if (node instanceof JsonArray) {
            return ((JsonArray) node).size();
        }
        if (node instanceof List) {
            return ((List<?>) node).size();
        }
        if (node instanceof JsonObject) {
            return ((JsonObject) node).size();
        }
        if (node instanceof Map) {
            return ((Map<?, ?>) node).size();
        }
        return 0;
    }

    /** 布尔取值 —— 替 {@code asBoolean()}; 不是布尔返 {@code null}(而不是 Jackson 的 false)。 */
    public static Boolean asBoolean(Object v) {
        return v instanceof Boolean ? (Boolean) v : null;
    }

    /** 数值取值 —— 替 {@code asDouble()}; 不是数字返 {@code null}。 */
    public static Double asDouble(Object v) {
        return v instanceof Number ? Double.valueOf(((Number) v).doubleValue()) : null;
    }

    /** 字段名 —— 替 {@code JsonNode.fieldNames()}。两种节点都返有序的 Set。 */
    public static Set<String> fieldNames(Object node) {
        Set<String> out = new java.util.LinkedHashSet<String>();
        if (node instanceof JsonObject) {
            for (Map.Entry<String, Object> e : ((JsonObject) node).getAllKeyValue()) {
                out.add(e.getKey());
            }
            return out;
        }
        if (node instanceof Map) {
            for (Object k : ((Map<?, ?>) node).keySet()) {
                out.add(String.valueOf(k));
            }
        }
        return out;
    }

    /** 遍历对象字段 —— 替 {@code fields()} 的迭代器。 */
    public static Iterable<Map.Entry<String, Object>> fields(Object node) {
        List<Map.Entry<String, Object>> out = new ArrayList<Map.Entry<String, Object>>();
        for (String k : fieldNames(node)) {
            out.add(new java.util.AbstractMap.SimpleEntry<String, Object>(k, get(node, k)));
        }
        return out;
    }

    /** 遍历数组元素。 */
    public static List<Object> elements(Object node) {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < size(node); i++) {
            out.add(get(node, i));
        }
        return out;
    }

    // ------------------------------------------------------------ 互转

    /** 摘掉字段 —— 替 {@code ObjectNode.remove(String)}。 */
    public static Object remove(Object node, String field) {
        if (node instanceof JsonObject) {
            return ((JsonObject) node).remove(field);
        }
        if (node instanceof Map) {
            return ((Map<?, ?>) node).remove(field);
        }
        return null;
    }

    /**
     * 摊平成普通 {@link Map} —— 替 {@code mapper.convertValue(node, Map.class)}。
     * 不是对象时返回 {@code null}(调用方那几处本来就在判这个)。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object node) {
        Object plain = node instanceof JsonObject || node instanceof JsonArray
                ? JsonUtil.toPlainValue(node) : node;
        return plain instanceof Map ? (Map<String, Object>) plain : null;
    }

    /** 摊平成普通 {@link List} —— 替 {@code convertValue(node, List.class)}。 */
    @SuppressWarnings("unchecked")
    public static List<Object> toList(Object node) {
        Object plain = node instanceof JsonObject || node instanceof JsonArray
                ? JsonUtil.toPlainValue(node) : node;
        return plain instanceof List ? (List<Object>) plain : null;
    }

    /**
     * 摊平成纯 Java 值(Map / List / String / Number / Boolean / null) ——
     * 替 {@code mapper.convertValue(node, Object.class)}。
     *
     * <p>{@code structuredContent} 这一槽要的正是它: 交给业务方和再序列化的都得是普通值,
     * 不能把 {@link JsonObject} 这种节点类型漏进协议外的面去。
     */
    public static Object toPlain(Object node) {
        if (node == null || node instanceof JsonObject || node instanceof JsonArray) {
            return JsonUtil.toPlainValue(node);
        }
        return node;
    }

    /** 数值归一: JSON 里所有数字读出来都应当是 {@link Number}, 供 {@code numberValue} 型比较。 */
    public static Number number(Object v) {
        return v instanceof Number ? (Number) v : null;
    }

    public static BigDecimal decimal(Object v) {
        Number n = number(v);
        return n == null ? null : new BigDecimal(n.toString());
    }

    /** 供 {@link JsonCursor} 用的游标视图; 供只需读一次的轻量调用点。 */
    public static JsonCursor cursor(Object node) {
        return JsonCursor.of(node);
    }

    /** 迭代器形式的元素遍历, 让增强 for 直接可用。 */
    public static Iterator<Object> iterator(Object node) {
        return elements(node).iterator();
    }
}
