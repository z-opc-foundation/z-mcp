package com.zifang.z.mcp.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 提示词模板注册条目 (prompts/list + prompts/get).
 *
 * <p>prompt 是**服务端预先编排好的对话片段**, arguments 声明需要的参数,
 * composer 按参数产出 messages[].
 */
public final class McpPromptDto {

    /** 单个 prompt 参数声明. */
    public static final class Arg {
        private final String name;
        private final String description;
        private final boolean required;

        public Arg(String name, String description, boolean required) {
            this.name = name;
            this.description = description;
            this.required = required;
        }

        public Map<String, Object> toWire() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            if (description != null) m.put("description", description);
            m.put("required", required);
            return m;
        }

        public String getName() { return name; }

        public boolean isRequired() { return required; }
    }

    /** 按参数组装 message 列表. */
    public interface Composer {
        /** @return [{role: "user"|"assistant", content: ContentBlock}...] */
        List<Map<String, Object>> compose(Map<String, Object> arguments) throws Exception;
    }

    public static Map<String, Object> message(String role, ContentBlock block) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("role", role);
        m.put("content", block.toWire());
        return m;
    }

    private final String name;
    private final String title;
    private final String description;
    private final List<Arg> arguments;
    private final transient Composer composer;

    public McpPromptDto(String name, String description, List<Arg> arguments, Composer composer) {
        this(name, null, description, arguments, composer);
    }

    public McpPromptDto(String name, String title, String description,
                        List<Arg> arguments, Composer composer) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.arguments = arguments == null
                ? Collections.<Arg>emptyList()
                : Collections.unmodifiableList(new ArrayList<Arg>(arguments));
        this.composer = composer;
    }

    public Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        if (title != null) m.put("title", title);
        if (description != null) m.put("description", description);
        List<Map<String, Object>> args = new ArrayList<Map<String, Object>>();
        for (Arg a : arguments) args.add(a.toWire());
        m.put("arguments", args);
        return m;
    }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public List<Arg> getArguments() { return arguments; }

    public Composer getComposer() { return composer; }
}
