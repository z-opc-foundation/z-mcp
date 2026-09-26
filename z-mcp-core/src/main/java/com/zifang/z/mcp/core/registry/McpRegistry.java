package com.zifang.z.mcp.core.registry;

import com.zifang.z.mcp.api.dto.CallToolResult;
import com.zifang.z.mcp.api.dto.McpPromptDto;
import com.zifang.z.mcp.api.dto.McpResourceDto;
import com.zifang.z.mcp.api.dto.McpServerDto;
import com.zifang.z.mcp.api.dto.McpToolDto;
import com.zifang.z.mcp.api.dto.ToolAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * MCP 工具/资源/提示词/server 注册中心 — 内存版默认实现.
 *
 * <p>线程安全. 三条不变量:
 * <ol>
 *   <li><b>不静默覆盖</b>: 0.1.x 的 registerServerTool 会直接 put 覆盖同名内置工具,
 *       与类注释承诺的"名字冲突时 builtin 优先"相反. 现在内置工具重名会抛异常,
 *       外部 server 工具重名会自动加 {@code server__} 前缀并把生效名返回给调用方.</li>
 *   <li><b>枚举顺序稳定</b>: listTools/listResources 按名字排序. 协议的分页
 *       (cursor/nextCursor) 要求遍历顺序可重复, ConcurrentHashMap 的随机序会让分页漏项.</li>
 *   <li><b>名字合法</b>: 工具名按协议 SHOULD 限制为 {@code [A-Za-z0-9_.-]{1,128}}.</li>
 * </ol>
 */
public class McpRegistry {

    /** 协议对工具名的字符集/长度约束(§Tools, SHOULD). */
    static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_.\\-]{1,128}$");

    /** 外部 server 工具重名时的命名空间分隔符. */
    public static final String NAMESPACE_SEPARATOR = "__";

    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<String, ToolEntry>();
    private final Map<String, McpServerDto> servers = new ConcurrentHashMap<String, McpServerDto>();
    private final Map<String, McpResourceDto> resources = new ConcurrentHashMap<String, McpResourceDto>();
    private final Map<String, McpResourceDto> resourceTemplates = new ConcurrentHashMap<String, McpResourceDto>();
    private final Map<String, McpPromptDto> prompts = new ConcurrentHashMap<String, McpPromptDto>();
    private final Map<Change, List<Runnable>> changeListeners =
            new ConcurrentHashMap<Change, List<Runnable>>();

    private boolean strictToolNames = true;

    public void setStrictToolNames(boolean strict) {
        this.strictToolNames = strict;
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 注册内置工具(便捷形式; 需要 title/outputSchema/annotations 时用 {@link #tool(String)}).
     *
     * @throws IllegalStateException 名字已被占用 — 绝不静默覆盖
     */
    public void registerBuiltin(String name, String description, String schemaJson, ToolExecutor executor) {
        tool(name).description(description).inputSchema(schemaJson).register(executor);
    }

    /**
     * 注册外部 server 提供的工具(便捷形式).
     *
     * @return 实际生效的工具名(与内置重名时会带 {@code server__} 前缀)
     */
    public String registerServerTool(String serverName, String name, String description,
                                     String schemaJson, ToolExecutor executor) {
        return tool(name).server(serverName).description(description)
                .inputSchema(schemaJson).register(executor);
    }

    /**
     * 流式注册入口.
     *
     * <p>0.1.x/0.2.0 早期用一串同形位置参数(name, title, description, schemaJson,
     * outputSchemaJson, annotations, executor), 其中 (String,String,String,String,
     * ToolAnnotations,ToolExecutor) 与少一个 outputSchema 的重载**类型完全一致**,
     * 传错顺序可以编译通过并把 outputSchema 当成 inputSchema 用. 所以改成具名 builder.
     */
    public Builder tool(String name) {
        return new Builder(name);
    }

    public final class Builder {
        private final String name;
        private String title;
        private String description;
        private String serverName;
        private String inputSchemaJson;
        private String outputSchemaJson;
        private ToolAnnotations annotations;
        private java.util.List<String> origins;

        private Builder(String name) { this.name = name; }

        public Builder title(String t) { this.title = t; return this; }

        public Builder description(String d) { this.description = d; return this; }

        /** 标记为某个外部 server 提供的工具(否则视为内置). */
        public Builder server(String s) { this.serverName = s; return this; }

        public Builder inputSchema(String json) { this.inputSchemaJson = json; return this; }

        public Builder outputSchema(String json) { this.outputSchemaJson = json; return this; }

        public Builder annotations(ToolAnnotations a) { this.annotations = a; return this; }

        /**
         * 聚合来源链. 只有 {@link com.zifang.z.mcp.core.client.ExternalServerManager} 该用它:
         * 传的是上游广告出来的那条链(不含本机), 广告时由协议层补上本机名字.
         */
        public Builder origins(java.util.List<String> o) { this.origins = o; return this; }

        /**
         * @return 生效工具名(与内置工具重名的外部工具会带命名空间前缀)
         */
        public String register(ToolExecutor executor) {
            if (executor == null) throw new IllegalArgumentException("executor required");
            String validated = validateName(name);
            String effective = validated;
            synchronized (McpRegistry.this) {
                ToolEntry existing = tools.get(effective);
                if (existing == null || serverName == null) {
                    if (existing != null) {
                        throw new IllegalStateException("duplicate tool registration: " + effective
                                + " already provided by "
                                + (existing.serverName == null ? "(builtin)" : existing.serverName));
                    }
                } else {
                    // 外部工具撞上内置/别人的工具: 加命名空间, 保住"内置优先"
                    effective = serverName + NAMESPACE_SEPARATOR + validated;
                    if (tools.containsKey(effective)) {
                        throw new IllegalStateException("duplicate tool registration for server "
                                + serverName + ": " + effective);
                    }
                }
                tools.put(effective, new ToolEntry(effective, title, description, serverName,
                        inputSchemaJson, outputSchemaJson, annotations, executor, origins));
            }
            fireChanged(Change.TOOLS);
            return effective;
        }
    }

    public void unregister(String name) {
        if (tools.remove(name) != null) fireChanged(Change.TOOLS);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    // ------------------------------------------------------------------ 查询

    /** 按名字排序, 保证分页遍历顺序稳定. */
    public List<McpToolDto> listTools() {
        List<ToolEntry> entries = sortedTools();
        List<McpToolDto> out = new ArrayList<McpToolDto>(entries.size());
        for (ToolEntry t : entries) out.add(t.toDto());
        return out;
    }

    public List<McpToolDto> listBuiltinTools() {
        List<McpToolDto> out = new ArrayList<McpToolDto>();
        for (ToolEntry t : sortedTools()) {
            if (t.serverName == null) out.add(t.toDto());
        }
        return out;
    }

    private List<ToolEntry> sortedTools() {
        List<ToolEntry> entries = new ArrayList<ToolEntry>(tools.values());
        Collections.sort(entries, new Comparator<ToolEntry>() {
            @Override public int compare(ToolEntry a, ToolEntry b) {
                return a.name.compareTo(b.name);
            }
        });
        return entries;
    }

    /** 分页视图: cursor 是"上一页最后一个名字", 空/null 表示从头. */
    public Page<McpToolDto> pageTools(String cursor, int limit) {
        List<McpToolDto> all = listTools();
        return paginate(all, cursor, limit, new NameOf<McpToolDto>() {
            @Override public String name(McpToolDto d) { return d.getName(); }
        });
    }

    public Page<McpResourceDto> pageResources(String cursor, int limit) {
        List<McpResourceDto> all = new ArrayList<McpResourceDto>(resources.values());
        Collections.sort(all, new Comparator<McpResourceDto>() {
            @Override public int compare(McpResourceDto a, McpResourceDto b) {
                return a.getUri().compareTo(b.getUri());
            }
        });
        return paginate(all, cursor, limit, new NameOf<McpResourceDto>() {
            @Override public String name(McpResourceDto d) { return d.getUri(); }
        });
    }

    public Page<McpResourceDto> pageResourceTemplates(String cursor, int limit) {
        List<McpResourceDto> all = new ArrayList<McpResourceDto>(resourceTemplates.values());
        Collections.sort(all, new Comparator<McpResourceDto>() {
            @Override public int compare(McpResourceDto a, McpResourceDto b) {
                return a.getUri().compareTo(b.getUri());
            }
        });
        return paginate(all, cursor, limit, new NameOf<McpResourceDto>() {
            @Override public String name(McpResourceDto d) { return d.getUri(); }
        });
    }

    public Page<McpPromptDto> pagePrompts(String cursor, int limit) {
        List<McpPromptDto> all = new ArrayList<McpPromptDto>(prompts.values());
        Collections.sort(all, new Comparator<McpPromptDto>() {
            @Override public int compare(McpPromptDto a, McpPromptDto b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return paginate(all, cursor, limit, new NameOf<McpPromptDto>() {
            @Override public String name(McpPromptDto d) { return d.getName(); }
        });
    }

    interface NameOf<T> { String name(T t); }

    static <T> Page<T> paginate(List<T> all, String cursor, int limit, NameOf<T> key) {
        int from = 0;
        if (cursor != null && !cursor.isEmpty()) {
            from = -1;
            for (int i = 0; i < all.size(); i++) {
                if (cursor.equals(key.name(all.get(i)))) { from = i + 1; break; }
            }
            if (from < 0) throw new IllegalArgumentException("invalid cursor: " + cursor);
        }
        int size = limit <= 0 ? all.size() - from : Math.min(limit, all.size() - from);
        if (size < 0) size = 0;
        List<T> items = new ArrayList<T>(all.subList(from, Math.min(from + size, all.size())));
        String next = null;
        boolean truncated = from + size < all.size();
        if (truncated && !items.isEmpty()) next = key.name(items.get(items.size() - 1));
        return new Page<T>(items, next);
    }

    /** 分页结果. nextCursor 为 null 表示已到末尾(协议: 缺省即无下一页). */
    public static final class Page<T> {
        private final List<T> items;
        private final String nextCursor;

        Page(List<T> items, String nextCursor) {
            this.items = items;
            this.nextCursor = nextCursor;
        }

        public List<T> getItems() { return items; }

        public String getNextCursor() { return nextCursor; }
    }

    // ------------------------------------------------------------------ 执行

    public ToolEntry lookup(String name) {
        return name == null ? null : tools.get(name);
    }

    /**
     * 调工具, 返回原始执行结果.
     *
     * <p>工具自身抛出的异常原样上抛(由协议层转成 isError=true), 不再包装成
     * 协议错误 — 这是 0.2.0 的核心语义修正.
     */
    public Object call(String name, Map<String, Object> arguments) throws Exception {
        ToolEntry t = tools.get(name);
        if (t == null) throw com.zifang.z.mcp.api.exception.McpException.toolNotFound(name);
        if (t.executor == null) throw new IllegalStateException("tool has no executor: " + name);
        return t.executor.execute(arguments == null ? Collections.emptyMap() : arguments);
    }

    /** 已排序工具名快照, 供限流/审计使用. */
    public List<String> toolNames() {
        List<String> out = new ArrayList<String>(tools.keySet());
        Collections.sort(out);
        return out;
    }

    // -------------------------------------------------- resources / prompts

    public void registerResource(McpResourceDto resource) {
        if (resource.getUri() == null || resource.getUri().isEmpty()) {
            throw new IllegalArgumentException("resource uri required");
        }
        if (resource.getUri().indexOf('{') >= 0) {
            resourceTemplates.put(resource.getUri(), resource);
        } else {
            resources.put(resource.getUri(), resource);
        }
        fireChanged(Change.RESOURCES);
    }

    public McpResourceDto lookupResource(String uri) {
        return uri == null ? null : resources.get(uri);
    }

    public List<McpResourceDto> listResources() {
        List<McpResourceDto> out = new ArrayList<McpResourceDto>(resources.values());
        Collections.sort(out, new Comparator<McpResourceDto>() {
            @Override public int compare(McpResourceDto a, McpResourceDto b) {
                return a.getUri().compareTo(b.getUri());
            }
        });
        return out;
    }

    public List<McpResourceDto> listResourceTemplates() {
        List<McpResourceDto> out = new ArrayList<McpResourceDto>(resourceTemplates.values());
        Collections.sort(out, new Comparator<McpResourceDto>() {
            @Override public int compare(McpResourceDto a, McpResourceDto b) {
                return a.getUri().compareTo(b.getUri());
            }
        });
        return out;
    }

    public void unregisterResource(String uri) {
        if (resources.remove(uri) != null || resourceTemplates.remove(uri) != null) fireChanged(Change.RESOURCES);
    }

    public void registerPrompt(McpPromptDto prompt) {
        validateName(prompt.getName());
        prompts.put(prompt.getName(), prompt);
        fireChanged(Change.PROMPTS);
    }

    public McpPromptDto lookupPrompt(String name) {
        return name == null ? null : prompts.get(name);
    }

    public List<McpPromptDto> listPrompts() {
        List<McpPromptDto> out = new ArrayList<McpPromptDto>(prompts.values());
        Collections.sort(out, new Comparator<McpPromptDto>() {
            @Override public int compare(McpPromptDto a, McpPromptDto b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ server

    public void registerServer(McpServerDto server) {
        servers.put(server.getName(), server);
    }

    public void unregisterServer(String serverName) {
        servers.remove(serverName);
    }

    public List<McpServerDto> listServers() {
        List<McpServerDto> out = new ArrayList<McpServerDto>(servers.values());
        Collections.sort(out, new Comparator<McpServerDto>() {
            @Override public int compare(McpServerDto a, McpServerDto b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    public McpServerDto lookupServer(String name) {
        return name == null ? null : servers.get(name);
    }

    public int toolCount() { return tools.size(); }

    public int serverCount() { return servers.size(); }

    public int resourceCount() { return resources.size(); }

    public int promptCount() { return prompts.size(); }

    // ------------------------------------------------------------ 变更通知

    /**
     * 目录类目. 协议给三类目录各配了一个 listChanged 通知, 因此变更必须按类目分发:
     * 注册一个 prompt 却广播 {@code notifications/tools/list_changed}, 会让每个在线客户端
     * 去重刷一份根本没动的工具表.
     */
    public enum Change { TOOLS, RESOURCES, PROMPTS }

    /** 注册某一类目录的变更监听(协议 listChanged 通知的挂载点). */
    public void addChangeListener(Change change, Runnable listener) {
        if (change == null || listener == null) return;
        List<Runnable> listeners = changeListeners.get(change);
        if (listeners == null) {
            List<Runnable> created = new CopyOnWriteArrayList<Runnable>();
            List<Runnable> existing = changeListeners.putIfAbsent(change, created);
            listeners = existing == null ? created : existing;
        }
        listeners.add(listener);
    }

    private void fireChanged(Change change) {
        List<Runnable> listeners = changeListeners.get(change);
        if (listeners == null) return;
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignore) {
                // 通知失败不能污染注册本身
            }
        }
    }

    private String validateName(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("tool name required");
        if (strictToolNames && !TOOL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid tool name '" + name
                    + "' (协议要求 [A-Za-z0-9_.-] 且长度 1..128)");
        }
        return name;
    }

    // ------------------------------------------------------------------ 条目

    /** 工具条目. */
    public static final class ToolEntry {
        public final String name;
        public final String title;
        public final String description;
        public final String serverName; // null = builtin
        public final String schemaJson;
        public final String outputSchemaJson;
        public final ToolAnnotations annotations;
        public final ToolExecutor executor;
        /** 聚合来源链(不含本机); 内置工具为 null. 见 {@link McpToolDto#getOrigins()}. */
        public final java.util.List<String> origins;

        public ToolEntry(String name, String description, String serverName, String schemaJson,
                         ToolExecutor executor) {
            this(name, null, description, serverName, schemaJson, null, null, executor, null);
        }

        public ToolEntry(String name, String title, String description, String serverName,
                         String schemaJson, String outputSchemaJson, ToolAnnotations annotations,
                         ToolExecutor executor) {
            this(name, title, description, serverName, schemaJson, outputSchemaJson, annotations,
                    executor, null);
        }

        public ToolEntry(String name, String title, String description, String serverName,
                         String schemaJson, String outputSchemaJson, ToolAnnotations annotations,
                         ToolExecutor executor, java.util.List<String> origins) {
            this.name = name;
            this.title = title;
            this.description = description;
            this.serverName = serverName;
            this.schemaJson = schemaJson;
            this.outputSchemaJson = outputSchemaJson;
            this.annotations = annotations;
            this.executor = executor;
            this.origins = origins;
        }

        public McpToolDto toDto() {
            return new McpToolDto(name, title, description, serverName, schemaJson,
                    outputSchemaJson, annotations, origins);
        }

        public boolean isBuiltin() { return serverName == null; }

        /** 该工具是否为某个外部 server 的一部分(用于 admin 视图分组). */
        public String serverOrBuiltin() {
            return serverName == null ? "(builtin)" : serverName;
        }
    }

    /**
     * 工具执行器 SPI — 与 kernel.tool.Tool 解耦, 接受 Map, 返回任意可 JSON 序列化对象
     * 或 {@link com.zifang.z.mcp.api.dto.CallToolResult}(需要完全掌控内容块时).
     */
    public interface ToolExecutor {
        Object execute(Map<String, Object> arguments) throws Exception;
    }

}
