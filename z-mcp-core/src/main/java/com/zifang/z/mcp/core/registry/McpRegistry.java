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

    /**
     * 工具表是<b>一份整体换掉的快照</b>, 不是一张原地增删的表.
     *
     * <p>读它的人比写它的人多得多({@code tools/list}、{@code find_tools}、限流、审计、每次调用),
     * 而"换一批外部工具"天然是<b>多次</b>写. 原地增删的话, 两次写之间来一读, 目录就是缺的 ——
     * 偏偏同一次变更还要发 {@code notifications/tools/list_changed} 叫所有客户端来重取,
     * 于是我们亲手把客户端领进自己刚造出来的空洞(09-26 实测: 12 条里读到 10 条; 单元层读到 {@code []}).
     * 现在每次写都在锁内做出一份完整的新表、换掉这一个 volatile 引用, 读侧要么看到旧的整份、
     * 要么看到新的整份.
     */
    private volatile Map<String, ToolEntry> tools = Collections.emptyMap();
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
            final ToolEntry entry;
            String effective;
            synchronized (McpRegistry.this) {
                Map<String, ToolEntry> next = new java.util.LinkedHashMap<String, ToolEntry>(tools);
                effective = resolveName(next, validated, serverName);
                entry = new ToolEntry(effective, title, description, serverName,
                        inputSchemaJson, outputSchemaJson, annotations, executor, origins);
                next.put(effective, entry);
                tools = snapshot(next);
            }
            fireChanged(Change.TOOLS);
            return effective;
        }
    }

    /**
     * 把某个外部 server 的那一批工具<b>一次换到位</b>: 一次快照交换、一次 {@link Change#TOOLS}.
     *
     * <p>这就是 {@code ExternalServerManager} 重发布上游目录时缺的那块原语. 逐条
     * {@code unregister} 再逐条 {@link Builder#register} 会播出一串变更(每一帧都在叫所有客户端
     * 重取目录), 而其中每一帧里读到的目录都是缺一块的. 撞名规则与 {@link Builder#register}
     * 共用 {@link #resolveName}, 所以生效名的算法只有一份.
     *
     * @return 这批工具的生效名(顺序与 {@code incoming} 相同)
     */
    public List<String> replaceServerTools(String serverName, List<McpToolDto> incoming,
                                           ToolExecutorFactory factory) {
        if (serverName == null) throw new IllegalArgumentException("serverName required");
        if (factory == null) throw new IllegalArgumentException("factory required");
        List<String> effective = new ArrayList<String>();
        synchronized (this) {
            Map<String, ToolEntry> next = new java.util.LinkedHashMap<String, ToolEntry>();
            for (Map.Entry<String, ToolEntry> e : tools.entrySet()) {
                // 别人提供的、内置的(serverName 为 null)一概原样留着 —— 这一批改的只有这一个 server
                if (!serverName.equals(e.getValue().serverName)) next.put(e.getKey(), e.getValue());
            }
            for (McpToolDto dto : incoming) {
                String validated = validateName(dto.getName());
                String name = resolveName(next, validated, serverName);
                next.put(name, new ToolEntry(name, dto.getTitle(), dto.getDescription(), serverName,
                        dto.getInputSchemaJson(), dto.getOutputSchemaJson(), dto.getAnnotations(),
                        factory.create(dto), dto.getOrigins()));
                effective.add(name);
            }
            //  "把这家的东西换成它自己"这种空操作不许播变更: detach 一台从来没有工具的 server、
            //  或上游一轮没动而调用方没先做签名判断, 都不该叫所有会话去重取目录.
            if (!next.equals(tools)) tools = snapshot(next);
            else return effective;
        }
        fireChanged(Change.TOOLS);
        return effective;
    }

    /**
     * 撞名规则(唯一一份): 名字空着 ⇒ 按原名; 被内置或别人占着而这条带 server 名 ⇒ 加
     * {@code server__} 前缀保住"内置优先"; 前缀名也被占 ⇒ 重复注册. {@code current} 是"此刻已在
     * 树上的那一批", 所以同一批里两条撞名也会在这里抛出来.
     */
    private static String resolveName(Map<String, ToolEntry> current, String validated,
                                      String serverName) {
        ToolEntry existing = current.get(validated);
        if (existing == null || serverName == null) {
            if (existing != null) {
                throw new IllegalStateException("duplicate tool registration: " + validated
                        + " already provided by "
                        + (existing.serverName == null ? "(builtin)" : existing.serverName));
            }
            return validated;
        }
        String namespaced = serverName + NAMESPACE_SEPARATOR + validated;
        if (current.containsKey(namespaced)) {
            throw new IllegalStateException("duplicate tool registration for server "
                    + serverName + ": " + namespaced);
        }
        return namespaced;
    }

    /** 整份工具表从这里出去, 且只从这里出去 —— 忘了走这一步的写就是下一个洞. */
    private static Map<String, ToolEntry> snapshot(Map<String, ToolEntry> from) {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<String, ToolEntry>(from));
    }

    /** 批量替换时按条目现造执行体(上游工具的执行体要绑住"它是哪一条"). */
    public interface ToolExecutorFactory {
        ToolExecutor create(McpToolDto tool);
    }

    public void unregister(String name) {
        boolean removed;
        synchronized (this) {
            if (!tools.containsKey(name)) {
                removed = false;
            } else {
                Map<String, ToolEntry> next = new java.util.LinkedHashMap<String, ToolEntry>(tools);
                next.remove(name);
                tools = snapshot(next);
                removed = true;
            }
        }
        if (removed) fireChanged(Change.TOOLS);
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
     *
     * <p>"完全掌控内容块"不包括绕过 {@code outputSchema}: 广告了那份 schema 的工具,
     * 交回的 {@code structuredContent} 一律要过一遍校验(判红走 isError, 不是 JSON-RPC error).
     */
    public interface ToolExecutor {
        Object execute(Map<String, Object> arguments) throws Exception;
    }

}
