package com.zifang.z.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具注解 — 给客户端的**提示**(untrusted hints), 不是安全边界.
 *
 * <p>默认值按协议规定: readOnlyHint=false, destructiveHint=true,
 * idempotentHint=false, openWorldHint=true.
 * 缺省注解意味着客户端按"最危险"处理 ⇒ 每个工具都应显式声明.
 */
public final class ToolAnnotations {

    private final String title;
    private final Boolean readOnlyHint;
    private final Boolean destructiveHint;
    private final Boolean idempotentHint;
    private final Boolean openWorldHint;

    public ToolAnnotations(String title, Boolean readOnlyHint, Boolean destructiveHint,
                           Boolean idempotentHint, Boolean openWorldHint) {
        this.title = title;
        this.readOnlyHint = readOnlyHint;
        this.destructiveHint = destructiveHint;
        this.idempotentHint = idempotentHint;
        this.openWorldHint = openWorldHint;
    }

    public static ToolAnnotations readOnly(String title) {
        return new ToolAnnotations(title, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE);
    }

    /** 只写不改外部世界: 非幂等, 但不开外部世界. */
    public static ToolAnnotations local(String title) {
        return new ToolAnnotations(title, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
    }

    public static ToolAnnotations openWorld(String title) {
        return new ToolAnnotations(title, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
    }

    public Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (title != null) m.put("title", title);
        if (readOnlyHint != null) m.put("readOnlyHint", readOnlyHint);
        if (destructiveHint != null) m.put("destructiveHint", destructiveHint);
        if (idempotentHint != null) m.put("idempotentHint", idempotentHint);
        if (openWorldHint != null) m.put("openWorldHint", openWorldHint);
        return m;
    }

    public String getTitle() { return title; }

    public Boolean getReadOnlyHint() { return readOnlyHint; }

    public Boolean getDestructiveHint() { return destructiveHint; }

    public Boolean getIdempotentHint() { return idempotentHint; }

    public Boolean getOpenWorldHint() { return openWorldHint; }
}
