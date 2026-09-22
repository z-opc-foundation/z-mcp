package com.zifang.z.mcp.core.registry;

import com.zifang.z.mcp.api.dto.McpServerDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.exception.McpException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具/server 注册中心 — 内存版默认实现.
 *
 * <p>线程安全, 内置工具 + 外部 server 工具统一管理.
 * <p>listTools: 跨所有 server 聚合输出, 名字冲突时 builtin 优先.
 * <p>call: 按 name 路由到对应执行器, serverName 为 null 时走内置.
 */
public class McpRegistry {

    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<String, ToolEntry>();
    private final Map<String, McpServerDto> servers = new ConcurrentHashMap<String, McpServerDto>();

    /**
     * 注册内置工具(由 z-mcp-core 提供默认实现).
     *
     * @param name     工具名
     * @param schemaJson JSON Schema 字符串
     * @param executor 执行器
     */
    public void registerBuiltin(String name, String description, String schemaJson, ToolExecutor executor) {
        tools.put(name, new ToolEntry(name, description, null, schemaJson, executor));
    }

    /**
     * 注册外部 server 提供的工具.
     */
    public void registerServerTool(String serverName, String name, String description, String schemaJson, ToolExecutor executor) {
        tools.put(name, new ToolEntry(name, description, serverName, schemaJson, executor));
    }

    public void unregister(String name) {
        tools.remove(name);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public List<McpToolDto> listTools() {
        List<McpToolDto> out = new ArrayList<McpToolDto>(tools.size());
        for (ToolEntry t : tools.values()) {
            out.add(new McpToolDto(t.name, t.description, t.serverName, t.schemaJson));
        }
        return out;
    }

    public List<McpToolDto> listBuiltinTools() {
        List<McpToolDto> out = new ArrayList<McpToolDto>();
        for (ToolEntry t : tools.values()) {
            if (t.serverName == null) out.add(new McpToolDto(t.name, t.description, null, t.schemaJson));
        }
        return out;
    }

    /**
     * 调工具.
     *
     * @param name      工具名
     * @param arguments 参数 JSON 对象(已 parse 为 Map)
     * @return 工具执行结果(字符串 / Map / 任意可序列化对象)
     */
    public Object call(String name, Map<String, Object> arguments) {
        ToolEntry t = tools.get(name);
        if (t == null) {
            throw McpException.toolNotFound(name);
        }
        try {
            return t.executor.execute(arguments == null ? Collections.emptyMap() : arguments);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw McpException.toolExecutionFailed(name, e);
        }
    }

    /**
     * 注册一个外部 MCP server(仅元信息, 真正调用走 serverName 工具的 executor).
     */
    public void registerServer(McpServerDto server) {
        servers.put(server.getName(), server);
    }

    public void unregisterServer(String serverName) {
        servers.remove(serverName);
    }

    public List<McpServerDto> listServers() {
        return new ArrayList<McpServerDto>(servers.values());
    }

    public int toolCount() {
        return tools.size();
    }

    public int serverCount() {
        return servers.size();
    }

    /**
     * 工具条目.
     */
    public static final class ToolEntry {
        public final String name;
        public final String description;
        public final String serverName; // null = builtin
        public final String schemaJson;
        public final ToolExecutor executor;

        public ToolEntry(String name, String description, String serverName, String schemaJson, ToolExecutor executor) {
            this.name = name;
            this.description = description;
            this.serverName = serverName;
            this.schemaJson = schemaJson;
            this.executor = executor;
        }
    }

    /**
     * 工具执行器 SPI — 跟 kernel.tool.Tool 解耦, 接受 Map<String,Object>, 返回任意可序列化对象.
     */
    public interface ToolExecutor {
        Object execute(Map<String, Object> arguments) throws Exception;
    }
}