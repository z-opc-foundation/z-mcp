package com.zifang.z.mcp.api.dto;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP {@code tools/call} 结果里的内容块 (content block).
 *
 * <p>协议规定 CallToolResult.content 是**类型化块数组**, 判别字段为 {@code type}.
 * 任何非数组 / 无 type 的返回值都不符合协议, 严格客户端会直接 schema 校验失败.
 *
 * <p>本类刻意不依赖 Jackson: 通过 {@link #toWire()} 产出有序 Map, 由 core 层序列化.
 */
public abstract class ContentBlock {

    /** 所有块共有的可选字段. */
    private List<String> audience;
    private Double priority;
    private String lastModified;

    public abstract String getType();

    /** @return 可 JSON 序列化的线格式 Map, key 顺序即输出顺序. */
    public Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", getType());
        appendFields(m);
        appendAnnotations(m);
        return m;
    }

    protected abstract void appendFields(Map<String, Object> m);

    void appendAnnotations(Map<String, Object> m) {
        if (audience == null && priority == null && lastModified == null) return;
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        if (audience != null) a.put("audience", audience);
        if (priority != null) a.put("priority", priority);
        if (lastModified != null) a.put("lastModified", lastModified);
        m.put("annotations", a);
    }

    public ContentBlock forAudience(String... roles) {
        this.audience = new ArrayList<String>();
        for (String r : roles) this.audience.add(r);
        return this;
    }

    public ContentBlock priority(double p) {
        this.priority = Double.valueOf(p);
        return this;
    }

    public ContentBlock lastModified(String iso8601) {
        this.lastModified = iso8601;
        return this;
    }

    // ------------------------------------------------------------------ text

    public static final class Text extends ContentBlock {
        private final String text;

        public Text(String text) {
            this.text = text == null ? "" : text;
        }

        @Override public String getType() { return "text"; }

        @Override protected void appendFields(Map<String, Object> m) {
            m.put("text", text);
        }

        public String getText() { return text; }
    }

    public static ContentBlock text(String s) {
        return new Text(s);
    }

    // ----------------------------------------------------------------- image

    public static final class Image extends ContentBlock {
        private final byte[] data;
        private final String mimeType;

        public Image(byte[] data, String mimeType) {
            this.data = data == null ? new byte[0] : data;
            this.mimeType = mimeType;
        }

        @Override public String getType() { return "image"; }

        @Override protected void appendFields(Map<String, Object> m) {
            m.put("data", Base64.getEncoder().encodeToString(data));
            if (mimeType != null) m.put("mimeType", mimeType);
        }
    }

    public static ContentBlock image(byte[] data, String mimeType) {
        return new Image(data, mimeType);
    }

    // ----------------------------------------------------------------- audio

    public static final class Audio extends ContentBlock {
        private final byte[] data;
        private final String mimeType;

        public Audio(byte[] data, String mimeType) {
            this.data = data == null ? new byte[0] : data;
            this.mimeType = mimeType;
        }

        @Override public String getType() { return "audio"; }

        @Override protected void appendFields(Map<String, Object> m) {
            m.put("data", Base64.getEncoder().encodeToString(data));
            if (mimeType != null) m.put("mimeType", mimeType);
        }
    }

    public static ContentBlock audio(byte[] data, String mimeType) {
        return new Audio(data, mimeType);
    }

    // -------------------------------------------------------- resource_link

    public static final class ResourceLink extends ContentBlock {
        private final String uri;
        private final String name;
        private String title;
        private String description;
        private String mimeType;
        private Long size;

        public ResourceLink(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }

        public ResourceLink title(String t) { this.title = t; return this; }

        public ResourceLink description(String d) { this.description = d; return this; }

        public ResourceLink mimeType(String m) { this.mimeType = m; return this; }

        public ResourceLink size(long bytes) { this.size = Long.valueOf(bytes); return this; }

        @Override public String getType() { return "resource_link"; }

        @Override protected void appendFields(Map<String, Object> m) {
            m.put("uri", uri);
            m.put("name", name);
            if (title != null) m.put("title", title);
            if (description != null) m.put("description", description);
            if (mimeType != null) m.put("mimeType", mimeType);
            if (size != null) m.put("size", size);
        }
    }

    public static ContentBlock resourceLink(String uri, String name) {
        return new ResourceLink(uri, name);
    }

    // -------------------------------------------------------------- resource

    /**
     * type=resource: 内嵌一个已读出的资源.
     *
     * <p>文本资源放 {@code resource.text}, 二进制放 {@code resource.blob}(base64).
     */
    public static final class Embedded extends ContentBlock {
        private final String uri;
        private final String mimeType;
        private final String text;
        private final byte[] blob;

        public Embedded(String uri, String mimeType, String text, byte[] blob) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.text = text;
            this.blob = blob;
        }

        @Override public String getType() { return "resource"; }

        @Override protected void appendFields(Map<String, Object> m) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("uri", uri);
            if (mimeType != null) r.put("mimeType", mimeType);
            if (blob != null) r.put("blob", Base64.getEncoder().encodeToString(blob));
            else r.put("text", text == null ? "" : text);
            m.put("resource", r);
        }
    }

    public static ContentBlock resource(String uri, String mimeType, String text) {
        return new Embedded(uri, mimeType, text, null);
    }

    public static ContentBlock resource(String uri, String mimeType, byte[] blob) {
        return new Embedded(uri, mimeType, null, blob);
    }

    // ------------------------------------------------------------ passthrough

    /**
     * 原样转发的块 — 用于代理上游 server 的结果.
     *
     * <p>代理必须能转发它**不认识**的块类型(协议会继续加新 type), 所以在边界上
     * 用线格式 Map 而不是具体子类; 未知 type 被丢弃会让内容静默变形.
     */
    public static final class Raw extends ContentBlock {
        private final Map<String, Object> wire;

        public Raw(Map<String, Object> wire) {
            this.wire = wire == null ? new LinkedHashMap<String, Object>() : wire;
        }

        @Override public String getType() {
            Object t = wire.get("type");
            return t == null ? "text" : String.valueOf(t);
        }

        @Override protected void appendFields(Map<String, Object> m) {
            for (Map.Entry<String, Object> e : wire.entrySet()) {
                if (!"type".equals(e.getKey())) m.put(e.getKey(), e.getValue());
            }
        }

        @Override void appendAnnotations(Map<String, Object> m) {
            // 注解已经随 wire 一起带过来了, 不再叠加
        }
    }

    public static ContentBlock raw(Map<String, Object> wire) {
        return new Raw(wire);
    }

    /**
     * 线格式 → 块. 只接受带 {@code type} 的对象, 其余返回 null 由调用方决定取舍.
     *
     * <p>一律还原成 {@link Raw} 而不是按 type 分派到具体子类: 代理的职责是"进来的字节
     * 原样出去", 认不出的新 type 也必须能转发(丢掉就变成静默的内容变形).
     *
     * @param wire 一个内容块的 JSON 视图(与 Jackson 无关, 调用方自行 convert)
     */
    public static ContentBlock fromWire(Map<String, Object> wire) {
        if (wire == null || wire.get("type") == null) return null;
        return new Raw(wire);
    }

    // ------------------------------------------------------------- utilities

    /** 把若干块组装成 list; 保证返回非 null 可变 List. */
    public static List<ContentBlock> listOf(ContentBlock... blocks) {
        List<ContentBlock> out = new ArrayList<ContentBlock>();
        if (blocks != null) {
            for (ContentBlock b : blocks) if (b != null) out.add(b);
        }
        return out;
    }

    /** 线格式视图. */
    public static List<Map<String, Object>> toWireList(List<ContentBlock> blocks) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (blocks != null) {
            for (ContentBlock b : blocks) if (b != null) out.add(b.toWire());
        }
        return out;
    }
}
