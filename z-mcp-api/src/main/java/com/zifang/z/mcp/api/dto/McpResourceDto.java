package com.zifang.z.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 资源注册条目.
 *
 * <p>协议里资源是**不可变内容寻址**的只读数据(uri 稳定, 内容有变可发
 * notifications/resources/updated). reader 为空表示该资源只做目录展示.
 */
public final class McpResourceDto {

    /** 读出一个资源的内容. */
    public interface Reader {
        ContentBlock read(Map<String, Object> params) throws Exception;
    }

    private final String uri;
    private final String name;
    private final String title;
    private final String description;
    private final String mimeType;
    private final transient Reader reader;

    public McpResourceDto(String uri, String name, String description, String mimeType, Reader reader) {
        this(uri, name, null, description, mimeType, reader);
    }

    public McpResourceDto(String uri, String name, String title, String description,
                          String mimeType, Reader reader) {
        this.uri = uri;
        this.name = name;
        this.title = title;
        this.description = description;
        this.mimeType = mimeType;
        this.reader = reader;
    }

    public Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("uri", uri);
        m.put("name", name);
        if (title != null) m.put("title", title);
        if (description != null) m.put("description", description);
        if (mimeType != null) m.put("mimeType", mimeType);
        return m;
    }

    public String getUri() { return uri; }

    public String getName() { return name; }

    public String getTitle() { return title; }

    public String getDescription() { return description; }

    public String getMimeType() { return mimeType; }

    public Reader getReader() { return reader; }
}
