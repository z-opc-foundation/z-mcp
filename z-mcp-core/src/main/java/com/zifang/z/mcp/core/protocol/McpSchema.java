package com.zifang.z.mcp.core.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议常量与版本协商.
 *
 * <p>实现基线: 2025-06-18, 并接收 2025-11-25(官方 Java SDK 2.0.1 支持到的最高修订).
 * 2026-07-28 修订取消了 initialize/会话/ping, 目前真实客户端尚未跟进,
 * 因此这里只保留一个可扩展的版本表, 新增版本是加法而不是改写.
 */
public final class McpSchema {

    private McpSchema() {}

    /** 服务端能同意的最高版本(客户端请求在支持列表内则原样回显). */
    public static final String LATEST_SUPPORTED = "2025-11-25";

    /** 客户端未声明版本时的兜底假定值 — 协议规定为 2025-03-26. */
    public static final String DEFAULT_ASSUMED = "2025-03-26";

    public static final List<String> SUPPORTED_VERSIONS = Collections.unmodifiableList(
            Arrays.asList("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"));

    // ---- 方法名 ----
    public static final String M_INITIALIZE = "initialize";
    public static final String M_NOTIFICATION_INITIALIZED = "notifications/initialized";
    public static final String M_NOTIFICATION_CANCELLED = "notifications/cancelled";
    public static final String M_PING = "ping";
    public static final String M_TOOLS_LIST = "tools/list";
    public static final String M_TOOLS_CALL = "tools/call";
    public static final String M_RESOURCES_LIST = "resources/list";
    public static final String M_RESOURCES_READ = "resources/read";
    public static final String M_RESOURCES_TEMPLATES_LIST = "resources/templates/list";
    public static final String M_RESOURCES_SUBSCRIBE = "resources/subscribe";
    public static final String M_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";
    public static final String M_PROMPTS_LIST = "prompts/list";
    public static final String M_PROMPTS_GET = "prompts/get";
    public static final String M_LOGGING_SET_LEVEL = "logging/setLevel";
    public static final String M_COMPLETION_COMPLETE = "completion/complete";

    // ---- 通知名 ----
    public static final String N_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    public static final String N_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";
    public static final String N_RESOURCES_UPDATED = "notifications/resources/updated";
    public static final String N_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";
    public static final String N_MESSAGE = "notifications/message";
    public static final String N_PROGRESS = "notifications/progress";

    // ---- 日志级别 ----
    /**
     * 协议的八个级别, 按**严重度递增**排列, 下标就是序.
     *
     * <p>{@code logging/setLevel} 传的是门槛而不是开关: 客户端说 warning, 服务端就不该再
     * 往下喂 info/debug。校验和排序必须共用这一张表 —— 分成两处写就会漂移,
     * 而"哪些级别算合法"和"谁比谁严重"其实是同一个问题的两半。
     */
    public static final List<String> LOG_LEVELS = Collections.unmodifiableList(
            Arrays.asList("debug", "info", "notice", "warning", "error", "critical", "alert", "emergency"));

    /** 级别的严重度序; 未知(含 null)回 -1. */
    public static int logLevelRank(String level) {
        return level == null ? -1 : LOG_LEVELS.indexOf(level);
    }

    // ---- Streamable HTTP 头 ----
    public static final String H_SESSION = "Mcp-Session-Id";
    public static final String H_PROTOCOL_VERSION = "MCP-Protocol-Version";
    public static final String H_LAST_EVENT_ID = "Last-Event-ID";

    /**
     * 版本协商: 客户端请求的版本若被支持则原样采纳, 否则回落到服务端最高版本.
     *
     * <p>协议要求服务端**必须**返回一个自己支持的版本, 而不是报错; 由客户端决定重试还是放弃.
     */
    public static String negotiate(String requested) {
        if (requested != null && SUPPORTED_VERSIONS.contains(requested)) return requested;
        return LATEST_SUPPORTED;
    }

    public static boolean isSupported(String version) {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }

    /** tools 能力; listChanged=true 表示服务端会推 notifications/tools/list_changed. */
    public static Map<String, Object> toolsCapability(boolean listChanged) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("listChanged", Boolean.valueOf(listChanged));
        return m;
    }

    public static Map<String, Object> resourcesCapability(boolean subscribe, boolean listChanged) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("subscribe", Boolean.valueOf(subscribe));
        m.put("listChanged", Boolean.valueOf(listChanged));
        return m;
    }

    public static Map<String, Object> promptsCapability(boolean listChanged) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("listChanged", Boolean.valueOf(listChanged));
        return m;
    }

    public static Map<String, Object> loggingCapability() {
        return new LinkedHashMap<String, Object>();
    }

    /** 组装 serverInfo / clientInfo 这类 Implementation 对象. */
    public static Map<String, Object> implementation(String name, String title, String version) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        if (title != null) m.put("title", title);
        m.put("version", version == null ? "0.0.0" : version);
        return m;
    }
}
