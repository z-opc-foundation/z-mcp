package com.zifang.z.mcp.api.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP {@code tools/call} 的结果 (CallToolResult).
 *
 * <p>线格式: {@code {content: [block...], isError?: bool, structuredContent?: any}}.
 *
 * <p>关键语义: **工具执行失败** 走 {@code isError=true} + content 说明, 而不是 JSON-RPC error.
 * 客户端会把 isError 的内容回灌给 LLM 让它自我纠正; 协议错误则会直接终止这次调用.
 * 只有"请求本身不合法"(未知工具名 / 缺参数 / 服务端内部故障)才是协议错误.
 */
public final class CallToolResult {

    private final List<ContentBlock> content;
    private Boolean isError;
    private Object structuredContent;
    private Map<String, Object> meta;

    private CallToolResult(List<ContentBlock> content) {
        this.content = content == null ? new ArrayList<ContentBlock>() : content;
    }

    public static CallToolResult of(List<ContentBlock> blocks) {
        return new CallToolResult(blocks);
    }

    public static CallToolResult of(ContentBlock... blocks) {
        return new CallToolResult(ContentBlock.listOf(blocks));
    }

    public static CallToolResult text(String s) {
        return new CallToolResult(ContentBlock.listOf(ContentBlock.text(s)));
    }

    /**
     * 工具执行失败 — 供 LLM 自我纠正, 不是协议错误.
     *
     * @param message 人类/模型可读的失败原因
     */
    public static CallToolResult executionError(String message) {
        CallToolResult r = new CallToolResult(ContentBlock.listOf(ContentBlock.text(message)));
        r.isError = Boolean.TRUE;
        return r;
    }

    /** 结构化结果: 同时给一份 JSON 文本块以兼容只看 content 的老客户端. */
    public static CallToolResult structured(Object structuredContent, String jsonTextFallback) {
        CallToolResult r = new CallToolResult(ContentBlock.listOf(ContentBlock.text(jsonTextFallback)));
        r.structuredContent = structuredContent;
        return r;
    }

    public CallToolResult meta(Map<String, Object> m) {
        this.meta = m;
        return this;
    }

    /** 代理上游结果时标记执行失败(协议: isError 与 JSON-RPC error 是两回事). */
    public CallToolResult setError(boolean error) {
        this.isError = error ? Boolean.TRUE : null;
        return this;
    }

    public Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("content", ContentBlock.toWireList(content));
        if (isError != null) m.put("isError", isError);
        if (structuredContent != null) m.put("structuredContent", structuredContent);
        if (meta != null && !meta.isEmpty()) m.put("_meta", meta);
        return m;
    }

    public List<ContentBlock> getContent() { return content; }

    public boolean isError() { return Boolean.TRUE.equals(isError); }

    public Object getStructuredContent() { return structuredContent; }

    public void setStructuredContent(Object o) { this.structuredContent = o; }
}
