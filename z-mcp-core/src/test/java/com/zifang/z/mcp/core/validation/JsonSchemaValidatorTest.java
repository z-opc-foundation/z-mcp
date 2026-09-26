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
}
