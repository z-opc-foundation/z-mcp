package com.zifang.z.mcp.core.json;

import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Wire} 的线格式契约。
 *
 * <p>右边那些字面量是<b>迁移前用 Jackson 实跑出来的原文</b>(差分探针同一批输入),
 * 不是照着规范手写的期望值 —— 所以它们是"换库前线上长什么样"的证据, 而非我的推断。
 * JSON-RPC 的单行性与 id 的数字形态都在其中: SSE 的 data: 帧与 stdio 的行分隔都靠单行,
 * 而 {@code "1"} 与 {@code 1} 是两个不同请求。
 */
public class WireContractTest {

    @Test
    void 请求信封_数字id_单行且id不被转成字符串() {
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("jsonrpc", "2.0");
        req.put("id", 123L);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", "echo");
        params.put("arguments", new LinkedHashMap<String, Object>());
        req.put("params", params);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":123,\"params\":{\"name\":\"echo\",\"arguments\":{}}}",
                Wire.write(req));
    }

    @Test
    void 请求信封_字符串id() {
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("jsonrpc", "2.0");
        req.put("id", "abc-1");
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\"}", Wire.write(req));
    }

    /** 迁移前 Jackson 的 build 出来是单行; 直接 toJson 会变多行缩进 —— 这个测试就是防它变回去。 */
    @Test
    void 树节点必须单行_不能出现换行或缩进() {
        JsonObject tree = Wire.object();
        tree.put("type", "object");
        JsonObject props = Wire.object();
        tree.put("properties", props);
        props.put("q", Wire.object());
        JsonArray required = Wire.array();
        required.add("a");
        required.add("b");
        tree.put("required", required);

        String json = Wire.write(tree);
        assertEquals("{\"type\":\"object\",\"properties\":{\"q\":{}},\"required\":[\"a\",\"b\"]}", json);
        assertFalse(json.contains("\n"), "JSON-RPC 响应必须单行");
    }

    @Test
    void 读回来的树再写出_仍与原文逐字节相同() {
        String src = "{\"jsonrpc\":\"2.0\",\"id\":7,\"params\":{\"n\":[1,2,{\"deep\":\"x\"}]}}";
        Object node = Wire.read(src);
        assertEquals(src, Wire.write(node));
    }

    /** error 包络靠显式 null 表达 data 缺席, 不能被悄悄省掉。 */
    @Test
    void null字段保留_不被悄悄省掉() {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("jsonrpc", "2.0");
        err.put("id", null);
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        err.put("error", e);
        e.put("code", -32600);
        e.put("message", "Invalid Request");
        e.put("data", null);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                + "\"message\":\"Invalid Request\",\"data\":null}}", Wire.write(err));
    }

    /** 超 double 精度的 long 不能被截断或变成科学计数法 —— 迁移前 Jackson 给出原值。 */
    @Test
    void 数字保真_不丢精度不被转字符串() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("score", 0.5d);
        m.put("whole", 1.0d);
        m.put("big", 9007199254740993L);
        assertEquals("{\"score\":0.5,\"whole\":1.0,\"big\":9007199254740993}", Wire.write(m));
    }

    @Test
    void 类型判别_分得出字符串数字布尔与整型() {
        Object parsed = Wire.read("{\"s\":\"1\",\"n\":1,\"d\":1.5,\"i\":3,\"b\":true,\"o\":{},\"a\":[]}");
        assertTrue(Wire.isTextual(Wire.get(parsed, "s")));
        assertFalse(Wire.isTextual(Wire.get(parsed, "n")));
        assertTrue(Wire.isNumber(Wire.get(parsed, "n")));
        assertTrue(Wire.isIntegral(Wire.get(parsed, "i")));
        assertFalse(Wire.isIntegral(Wire.get(parsed, "d")), "1.5 不是整型");
        assertTrue(Wire.isBoolean(Wire.get(parsed, "b")));
        assertTrue(Wire.isObject(Wire.get(parsed, "o")));
        assertTrue(Wire.isArray(Wire.get(parsed, "a")));
    }

    /** JSON-RPC 的 requestKey 靠 "1" 与 1 分成两个请求, 所以字符串与数字的判别不能糊。 */
    @Test
    void 字符串1与数字1判别不同() {
        Object parsed = Wire.read("{\"s\":\"1\",\"n\":1}");
        assertEquals("1", Wire.asText(Wire.get(parsed, "s")));
        assertEquals("1", Wire.asText(Wire.get(parsed, "n")));
        assertTrue(Wire.isTextual(Wire.get(parsed, "s")));
        assertTrue(Wire.isNumber(Wire.get(parsed, "n")));
    }

    @Test
    void 非法文本返回null_由调用方走兜底而不是抛() {
        assertNull(Wire.read("not json at all"));
        assertNull(Wire.read("{unclosed"));
    }

    @Test
    void toMap把树摊平成普通Map() {
        Object node = Wire.read("{\"a\":1,\"b\":[1,2]}");
        Map<String, Object> m = Wire.toMap(node);
        assertEquals(1, m.get("a"));
        assertEquals(2, ((List<?>) m.get("b")).size());
        assertNull(Wire.toMap("[1,2]"), "数组不是对象");
    }

    @Test
    void 字段遍历保序() {
        Object node = Wire.read("{\"z\":1,\"a\":2,\"m\":3}");
        List<String> keys = new ArrayList<String>();
        for (String k : Wire.fieldNames(node)) {
            keys.add(k);
        }
        assertEquals(java.util.Arrays.asList("z", "a", "m"), keys);
    }

    @Test
    void 数组元素与长度() {
        Object arr = Wire.read("[1,\"two\",{\"three\":3}]");
        assertEquals(3, Wire.size(arr));
        assertEquals("two", Wire.get(arr, 1));
        assertEquals(3, Wire.toMap(Wire.get(arr, 2)).get("three"));
    }

    // ------------------------------------------------------------ 显式 null 与缺席

    /**
     * {@code {"const":null}} 与 {@code {}} 在 JSON Schema 里是两条不同的约束 ——
     * 前者要参与比对, 后者没有这一条。z-util 把 JSON null 落成 Java null, 按值判的
     * {@link Wire#has} 分不开这两者, 所以关键字在不在一律问 {@link Wire#hasKey}。
     */
    @Test
    void hasKey分得开显式null与缺席() {
        Object node = Wire.read("{\"const\":null}");
        assertTrue(Wire.hasKey(node, "const"));
        assertFalse(Wire.has(node, "const"), "按值判看不见显式 null —— 这就是 hasKey 存在的理由");
        assertFalse(Wire.hasKey(node, "type"));
        assertNull(Wire.get(node, "const"));
    }

    /** 缺席那一格不该被 hasKey 认成"键在", 否则 schema 会去校验一个不存在的关键字。 */
    @Test
    void 普通Map上的hasKey同样只看键() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("a", null);
        assertTrue(Wire.hasKey(m, "a"));
        assertFalse(Wire.hasKey(m, "b"));
    }

    // ------------------------------------------------------------ 写不出的值必须报错

    /** 迁移前 Jackson 反射读 getter, 现在 z-util 会把它写成 {@code {}} —— 内容静默没了。 */
    public static final class Pojo {
        public String getName() { return "n"; }
    }

    @Test
    void 顶层POJO必须报错_不能静默写成空对象() {
        try {
            String json = Wire.write(new Pojo());
            throw new AssertionError("POJO 被写成了 " + json + " —— 内容静默丢了");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Pojo"), e.getMessage());
        }
    }

    @Test
    void Map里埋着的POJO同样要报错() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("count", new Object());
        try {
            Wire.write(m);
            throw new AssertionError("嵌套 POJO 被静默写成了空对象");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains(".count"), e.getMessage());
        }
    }

    /**
     * structuredContent 常常是工具作者手搭的一棵树, POJO 埋在某一格上。
     * 迁移中头一版只查顶层, 那一格会漏成 {@code {}} —— 这一条钉住"树里也查"。
     */
    @Test
    void 树里埋着的POJO也要报错() {
        JsonObject tree = Wire.object();
        tree.put("name", "ok");
        JsonObject inner = Wire.object();
        inner.put("bad", new Pojo());
        tree.put("nested", inner);
        try {
            String json = Wire.write(tree);
            throw new AssertionError("树里的 POJO 被写成了 " + json);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains(".nested.bad"), e.getMessage());
        }
    }

    @Test
    void 数组里埋着的POJO也要报错() {
        List<Object> l = new ArrayList<Object>();
        l.add("ok");
        l.add(new Pojo());
        try {
            Wire.write(l);
            throw new AssertionError("数组里的 POJO 被静默写成了空对象");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("[1]"), e.getMessage());
        }
    }
}
