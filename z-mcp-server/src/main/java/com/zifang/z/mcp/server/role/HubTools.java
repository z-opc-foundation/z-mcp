package com.zifang.z.mcp.server.role;

import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.core.client.ExternalServerManager;
import com.zifang.z.mcp.core.properties.McpProperties;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code hub} 角色的目录服务工具 —— 服务中心对客户端提供的"关于目录本身"的两条能力.
 *
 * <p>{@code find_tools} 存在的理由: 聚合 N 个上游之后目录会长到几十上百条, 而工具的
 * name+description 是要整个塞进 LLM 上下文的. 让模型先检索再调用, 比一次性广告全部便宜得多.
 *
 * <p>{@code registry_state} 报的是 {@link ExternalServerManager} 的<em>真实</em>状态:
 * 连不上就是 {@code connected=false} + {@code lastError} 原文, 不写"假定正常"的好话.
 */
@Component
@Profile("hub")
public class HubTools {

    private static final int DEFAULT_LIMIT = 20;

    HubTools(final McpRegistry registry, final ExternalServerManager servers, McpProperties properties) {
        registry.tool("find_tools")
                .title("Find tools")
                .description("在服务中心聚合的全部工具里按关键词检索(空格分词, 需全部命中),"
                        + " 返回候选工具及其来源 server")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\","
                        + "\"description\":\"关键词, 匹配工具名/标题/描述\"},"
                        + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200,"
                        + "\"description\":\"最多返回几条, 默认 20\"}},"
                        + "\"required\":[\"query\"],\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("检索工具目录"))
                .register(args -> search(registry, ToolArgs.required(args, "query"),
                        ToolArgs.integer(args, "limit", DEFAULT_LIMIT)));

        registry.tool("registry_state")
                .title("Registry state")
                .description("返回服务中心本地目录条数与各上游 MCP server 的真实连接状态(含 lastError 原文)")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("注册中心状态"))
                .register(args -> state(registry, servers, properties));
    }

    static Map<String, Object> search(McpRegistry registry, String query, int limit) {
        String[] tokens = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (McpToolDto tool : registry.listTools()) {
            if (!matchesQuery(tool, tokens)) continue;
            total++;
            if (matches.size() < limit) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", tool.getName());
                m.put("title", tool.getTitle());
                m.put("description", tool.getDescription());
                m.put("server", tool.getServerDisplayName());
                matches.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("query", query);
        out.put("matched", Integer.valueOf(total));
        out.put("truncated", Boolean.valueOf(total > matches.size()));
        out.put("tools", matches);
        return out;
    }

    private static boolean matchesQuery(McpToolDto tool, String[] tokens) {
        if (tokens.length == 1 && tokens[0].isEmpty()) return true;
        String haystack = (tool.getName() + " " + nullToEmpty(tool.getTitle()) + " "
                + nullToEmpty(tool.getDescription())).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    static Map<String, Object> state(McpRegistry registry, ExternalServerManager servers,
                                     McpProperties properties) {
        Map<String, Object> self = new LinkedHashMap<String, Object>();
        self.put("name", properties.getServerName());
        self.put("version", properties.getServerVersion());
        self.put("tools", Integer.valueOf(registry.toolCount()));
        self.put("resources", Integer.valueOf(registry.resourceCount()));
        self.put("prompts", Integer.valueOf(registry.promptCount()));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("self", self);
        out.put("upstreams", servers.health());
        out.put("servers", servers.state());
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
