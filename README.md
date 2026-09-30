# z-mcp

> MCP（Model Context Protocol）服务端 + 上游聚合网关 —— 跑在 **Java 8** 上的手写协议层，Spring Boot 自动装配。

给一个已存在的 Spring Boot 2.7 应用挂上 `/mcp`，它同时成为两样东西：

* **MCP server** —— 业务工具经 `McpRegistry` 注册后可被任意 MCP 客户端调用（Streamable HTTP）；
* **MCP 网关** —— 把别处已经存在的 MCP server（HTTP 上游或本地 stdio 子进程）聚合成**一份**工具目录，
  撞名自动加前缀，上游失联自动把它的工具从目录里撤走。

它解决的是"存量 Java 8 服务接不上 MCP"这件事：官方 `io.modelcontextprotocol.sdk:mcp` 要求
**Java 17 + Project Reactor**，而本仓编译目标是 Java 8，也不想把 Reactor 塞进中间件层的依赖树。
所以协议层与传输层自己实现，只用 JDK 8 能用的东西 —— HTTP 客户端走 `HttpURLConnection`
（`java.net.http` 是 11+），stdio 子进程的 stderr 由独立线程排空（`Redirect.DISCARD` 是 9+，
且丢弃 stderr 等于丢掉唯一的诊断线索），SSE 用 Spring MVC 的 `SseEmitter`。
代价是每一个协议细节都要自己钉住，因此每一条约定都配了对应的测试（见「测试」）。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-mcp` |
| **Maven 坐标** | `io.github.yuku123:z-mcp:${revision}`（`packaging=pom`，聚合五模块） |
| **当前版本** | `0.2.1`（根 POM `<revision>`，CI-friendly versions + flatten-maven-plugin `1.7.2`，`flattenMode=oss`） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘） |
| **模块** | `z-mcp-api` / `z-mcp-core` / `z-mcp-starter` / `z-mcp-admin` / `z-mcp-server`（`<modules>` 实测顺序） |
| **Maven Central** | 六枚坐标 `0.2.1` **均已发布**（见「发布与构件」的逐枚实测） |
| **协议口径** | 实现基线 `2025-06-18`，最高采纳 `2025-11-25`（`McpSchema.LATEST_SUPPORTED`） |
| **默认端口** | `18095`（hub）/ `18096`（text）/ `18097`（codec），均可被 `Z_MCP_PORT` 覆盖；作为库引入时端口跟随宿主应用 |
| **运行口径** | Java 8（`maven.compiler.source/target=8` 由父链下发）· Spring Boot 2.7.18 |
| **配置前缀** | `z.mcp.*`（`McpProperties`）；总开关 `z.mcp.enabled` 默认 **false** |
| **最近更新** | 2026-09-30 |

> 根 POM 里刻意只剩 `<revision>` 一格：Java 口径、UTF-8、`spring-boot.version=2.7.18`
> 三格与父链面值逐字相同，已删，由 `z-boot-parent` → `z-boot-dependencies`（第三方地板）
> + `z-boot-fleet`（兄弟仓权威表）下发。

---

## 🎯 能力清单

每一条都点得到仓内的实现类或端点：

| 能力 | 实现 | 说明 |
|------|------|------|
| 协议方法分发 | `core/protocol/McpProtocolHandler` | `initialize` / `ping` / `tools` / `resources` / `prompts` / `logging` 的 JSON-RPC 分发与结果折叠 |
| 版本协商 | `core/protocol/McpSchema` | 支持集、缺省假定值、`negotiate()`、SSE priming 的版本闸 |
| Streamable HTTP 入口 | `core/controller/JsonRpcController` | `POST` / `GET` / `DELETE` 同址 `/mcp`，加只读探针 `GET /mcp/info` |
| 会话与续传 | `core/session/McpSessionStore` | `Mcp-Session-Id` 签发/TTL/上限、事件缓冲补发、`:keep-alive` 心跳 |
| 传输安全 | `core/security/TransportSecurityGuard` | Origin/Host 同源校验（防 DNS rebinding）、静态 Bearer 白名单、按会话限流 |
| 注册中心 | `core/registry/McpRegistry` | 工具/资源/资源模板/提示词/server 五张表，整表快照交换 |
| 入参校验 | `core/validation/JsonSchemaValidator` | 按工具 `inputSchema` 校验 `tools/call` 的参数（认一个 JSON Schema 子集） |
| 上游聚合 | `core/client/ExternalServerManager` + `McpRemoteClient` | 周期性重取上游目录、失联撤工具、两层防环 |
| 上游传输 | `core/client/HttpJsonRpcExchange` · `StdioJsonRpcExchange` | 出站两条腿：Streamable HTTP 与 stdio 子进程 |
| 内置工具与自省资源 | `core/builtin/BuiltinTools` | 四件中心件 + 三条 `z-mcp://` 资源 |
| 自动装配 | `starter/autoconfig/ZMcpAutoConfiguration` | 同时提供 Boot 2 的 `spring.factories` 与 Boot 3 的 `AutoConfiguration.imports` |
| 控制面 | `admin/controller/AdminController` + `AdminQueryService` | 只读 JSON 视图，默认不挂载 |
| 可执行进程 | `server/ZMcpServerApplication` + `server/role/{HubTools,TextTools,CodecTools}` | 一个 fat jar 装 `hub` / `text` / `codec` 三种角色 |

---

## 📡 传输口径（从代码实测，不是从版本号推）

**入站（本仓作为 server 被客户端连）** 只有一种：**Streamable HTTP**。

* 单一端点 `${z.mcp.base-path}/mcp`，三个动词：`POST` 收请求与通知、`GET` 开服务端→客户端的流、
  `DELETE` 终止会话；另有 `GET ${base-path}/mcp/info` 只读自检（不经 JSON-RPC，给探针/负载均衡器）。
* `GET /mcp` 上的 SSE 是 **Streamable HTTP 的那一条腿**，不是被协议废弃的
  "HTTP+SSE 双端点"旧传输（2024-11-05 形状）。关掉 `sse.enabled` 或客户端表明不收流时按协议回 `405`
  —— 回 406 会被真客户端（Gemini CLI、LibreChat 都报过）读成"这条 MCP 连接坏了"。
* 本仓**不作为 stdio server 运行**：`src/main` 里没有任何把 stdin/stdout 当 MCP 传输读的代码路径。
  stdio 只存在于**出站**一侧。

**出站（本仓作为 client 连上游）** 有两种，由 `z.mcp.servers[].transport` 选：

| `transport` | 实现 | 备注 |
|-------------|------|------|
| `http` / `streamable-http` / `http+json` | `HttpJsonRpcExchange` | 一次 POST 配一次响应，无常驻读流 |
| `stdio` | `StdioJsonRpcExchange` | `ProcessBuilder` 拉起子进程，newline-delimited JSON-RPC，独立线程排 stdout/stderr |
| `http+sse` / `websocket` | —— | **显式拒绝**并写进控制面 `lastError`（前者已被协议废弃，后者未实现；静默忽略配置是更难查的 bug） |

**协议版本**（`McpSchema`，逐字实测）：

| 常量 | 值 |
|------|-----|
| `SUPPORTED_VERSIONS` | `2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25` |
| `LATEST_SUPPORTED` | `2025-11-25`（客户端请求在列表内则原样回显，否则降到这一枚） |
| `DEFAULT_ASSUMED` | `2025-03-26`（客户端不带 `MCP-Protocol-Version` 时按协议兜底假定） |
| `SSE_PRIMING_SINCE` | `2025-11-25`（只有谈到这一版及之后的会话才收到 `data` 为空的 priming 帧） |

`2026-07-28` 那一版**有意不跟**：它取消 `initialize`/会话/`ping`/SSE 续传，改成每请求带
`_meta.io.modelcontextprotocol/protocolVersion` 并新增 `server/discover`、MRTR；本仓的聚合与
安全边界都以会话为主键，跟它等于拆掉主键，而真实客户端目前尚未跟进。但那一版的**错误码分配政策**
已经影响本仓挑码：`UNSUPPORTED_PROTOCOL_VERSION` 取 `-32022`（该场合规范只规定 `400`、没规定
JSON-RPC 码），而 `RESOURCE_NOT_FOUND` 仍留 `-32002`（`2025-11-25/server/resources` 的白纸黑字值）。

---

## 🧩 协议方法面

| 方法 | 状态 | 行为要点 |
|------|------|----------|
| `initialize` | ✅ | 协商版本、签发 `Mcp-Session-Id`、回显 capabilities；带着别人的会话 id 来 ⇒ `400` |
| `notifications/initialized` | ✅ | 只把会话标记为已初始化；无 `id` 键的输入一律 `202` 空体 |
| `notifications/cancelled` | ✅ | **真取消**：按 `params.requestId` 找到在飞请求并 `Future.cancel(true)`，回 `-3280` |
| `ping` | ✅ | 空对象 `{}`（0.1.x 的自造形状 `{"pong":true}` 已改），`initialize` 之前也允许 |
| `tools/list` · `tools/call` | ✅ | 分页 `cursor`/`nextCursor`；入参按 `inputSchema` 校验；结果按 `outputSchema` 兑现 |
| `resources/list` · `resources/templates/list` · `resources/read` | ✅ | 变更推 `notifications/resources/list_changed` |
| `resources/subscribe` · `resources/unsubscribe` | ❌ `-32601` | 没声明 `subscribe` 能力，就不假装订阅成功 |
| `prompts/list` · `prompts/get` | ✅ | 内置模块不提供 prompt，注册表留给业务侧；注册时推 `notifications/prompts/list_changed` |
| `logging/setLevel` | ✅ | 八级枚举按协议校验并记在会话上，**真的是门槛**：低于它的 `notifications/message` 不再上那条流 |
| `completion/complete` | ❌ `-32601` | `McpSchema` 里有常量，`dispatch()` 的 switch 里没有分支 —— 不谎报 |

声明的 capabilities（`McpProtocolHandler.capabilities()`）：`tools.listChanged=true`、
`resources{subscribe=false, listChanged=true}`、`prompts.listChanged=true`、`logging`。
客户端侧能力（`roots` / `sampling` / `elicitation`）向上游握手时是**空对象**：不广告做不到的事。

错误语义（这里与真实客户端分歧最大）：

* 传输/信封层 ⇒ HTTP 状态码 + JSON-RPC error：`-32700` 解析失败、`-32600` 非法请求、
  `-32601` 方法未知、`-32603` 内部错误、`-3280` 已被客户端取消；
* **通知与非法请求的分界只看 `id` 这个键在不在**：没有 `id` 才是通知（`202` 空体）；
  显式 `"id": null` **不是**通知，回 `-32600` 且回执 `id` 原样为 `null`；
* 未知工具、参数不合 `inputSchema` ⇒ `-32602`；
* **工具自己抛异常 ⇒ 不是协议错误**，而是 `result.isError=true` + 文本 content（2025-11-25 明确如此），
  回成 JSON-RPC error 会让客户端以为连接坏了；
* 传输层校验：`Accept` 必须同时列 `application/json` 与 `text/event-stream`（POST 那一路的 MUST）
  否则 `406`；`Content-Type` 只认 `application/json` 否则 `415`（不带这个头也拒）；
  请求体上限 4 MiB ⇒ `413`；批量数组体按 2025-06-18 的删除明确拒绝；
  不支持的 `MCP-Protocol-Version` ⇒ `400`（`POST`/`GET`/`DELETE` 三个入口共用同一判据）；
  不认识会话 id ⇒ `404`；超限 ⇒ `429` + `Retry-After: 1`。

---

## 🛠️ 工具如何声明与分发

**声明**：`McpRegistry.tool(name)` 返回具名 builder（`title` / `description` / `server` /
`inputSchema` / `outputSchema` / `annotations` / `origins`），终结于 `register(ToolExecutor)`。
改成具名 builder 的原因是位置参数重载之间类型完全一致，传错顺序能编译通过并把
`outputSchema` 当 `inputSchema` 用。

注册表三条不变量：

1. **不静默覆盖** —— 内置工具重名直接抛异常；外部 server 的工具与内置撞名时自动加 `server__` 前缀
   并把生效名返回给调用方；
2. **枚举顺序稳定** —— `listTools` / `listResources` 按名字排序，否则 `cursor` 分页会漏项；
3. **名字合法** —— 工具名强制 `[A-Za-z0-9_.-]{1,128}`（协议 §Tools SHOULD）。

工具表是**一份 volatile 引用指向的不可变整表快照**，不是原地增删的 map：三条写路径
（`Builder.register` / `unregister` / `replaceServerTools`）都在监视器里改副本、只经
`snapshot(...)` 这一个出口发布。于是"读到的目录缺一块"这个时间窗在结构上不存在 ——
旧形状下 `tools.remove(name)` 之后才 `fireChanged`，而同一刻协议正通知所有客户端"来重取
`tools/list`"，等于把客户端领进服务端刚造出来的空洞。

**分发**：`JsonRpcController.post` 做完传输层校验后交给 `McpProtocolHandler.handle` →
`dispatch(method)` 的 switch → `toolsCall` 按名字查 `ToolEntry` → `JsonSchemaValidator` 按
`inputSchema` 校验入参 → `invokeWithTimeout` 在线程池里执行（`tool-timeout-seconds>0` 时才可被中断）。
注册表变更经 `Change{TOOLS,RESOURCES,PROMPTS}` 监听器向在线会话推对应类目的 `*_list_changed`
（三类必须分开，把 prompt 注册播成 `tools/list_changed` 是叫客户端去重刷一张没动的工具表）。

**内置工具**（`builtin-tools-enabled=true` 时登记，默认 true）：

| 工具 | 说明 |
|------|------|
| `echo` | 回显输入文本（调试工具调用链路） |
| `get_time` | `iso`（显式 UTC）/ `epoch` / `iso_local`，走 `java.time` |
| `generate_uuid` | UUID v4 |
| `system_info` | platform / version / java / os |

配套的三条自省资源 `z-mcp://tools`、`z-mcp://servers`、`z-mcp://self` 让注册中心自己可读 ——
这是 `capabilities.resources` 不成为谎报的前提。叶子角色把它们关掉是对的：一个只提供编解码工具的服务
被聚合进 hub 后，它的 `echo` 会撞上 hub 的 `echo` 而变成 `codec__echo`，目录里躺着四份"回显输入文本"。

`resources/read`（`f6767fc`）交回的块按 `ContentBlock` 那五支判别联合折叠：`text` 走 `text` 支，
`image`/`audio` 走 `blob` 支并对 `data` 查 base64，`resource` 支沿用里层（里层 `uri`/`mimeType`
非字符串时回落目录），`resource_link` 与未知 `type` 一律 `-32602` —— 修前非文本块会被折成
`text:"null"`，图片字节整个丢掉。

---

## 🌐 上游聚合（网关那一侧）

* **首连不在启动路径上**：`ZMcpAutoConfiguration` 把第一轮 `syncAll()` 放在守护线程里，
  N 个死上游不该变成 N 倍连接超时的启动阻塞。
* 上游不可达 ⇒ 它的工具从目录里**撤走**并在 `lastError` 里点名原因；恢复后重新发布。
* **上游目录逐字未变时不动注册表**：省的不是 CPU，是一串谎报 —— 每次 `register`/`unregister`
  都 fire 一次 `Change.TOOLS`，每一帧都叫所有会话去重取 `tools/list`。摘除（`detach`）会把签名一起作废。
* **两层防环**：其一，上游握手回来的 `serverInfo.name` 等于本机 `z.mcp.server-name` 就直接拒
  （自指/复制品）；其二，不同名的两台 hub 互为上游时靠 `_meta["z-mcp/origins"]` 来源链拒收
  自己抄出去的那批条目。来源链是本仓约定，第三方 hub 剥掉 `_meta` 就等于没链可依，
  所以"hub 只聚合叶子"仍是推荐口径。
* `connected` 由传输自己回答（`JsonRpcExchange.isAlive()`），不是握手置位后永不复位的 flag。
* stdio 上游没有 URL，控制面就把它的**命令行**当入口显示，而不是回 `null`。
* 上游亲口推来的 `notifications/tools/list_changed` 会立刻换来一次重取（不占用期清扫那台池子），
  **但只有 stdio 那一路存在这一条**：HTTP 那一路没有入站通道（见「已知边界」）。

---

## 🏗️ 项目结构

```
z-mcp/
├── pom.xml                 # 聚合根 POM：parent z-boot-parent:1.0.21，<revision>0.2.1</revision>，五模块
├── z-mcp-api/              # 零依赖 DTO 与异常：JsonRpcRequest/Response、CallToolResult、
│                           #   ContentBlock（text/image/audio/resource_link/resource 五支 + raw）、
│                           #   McpToolDto/McpResourceDto/McpPromptDto/McpServerDto、ToolAnnotations、McpException
├── z-mcp-core/             # 引擎：McpRegistry、McpProtocolHandler、McpSchema、JsonRpcController、
│                           #   McpSessionStore、TransportSecurityGuard、JsonSchemaValidator、
│                           #   client/（ExternalServerManager + McpRemoteClient + http/stdio 两传输）、BuiltinTools
├── z-mcp-starter/          # ZMcpAutoConfiguration + spring.factories(Boot 2) / AutoConfiguration.imports(Boot 3)
├── z-mcp-admin/            # 控制面 AdminController + AdminQueryService（只读 JSON，默认不挂载）
├── z-mcp-server/           # 可执行进程（前四个是库）：fat jar 装 hub/text/codec 三角色
│   └── src/main/resources/  # application.yml + application-{hub,text,codec}.yml
├── tools/                  # 仓内自校验脚本（Python，不是 MCP 工具）：
│   ├── ci-tally.py         #   surefire 报告压成按模块的表，"该跑的没跑"当场判红（CI 的门禁这一步）
│   └── readme-sha-audit.py #   把 README 里当作"某枚提交存在"的十六进制引用逐枚 git cat-file 过
├── .github/workflows/      # ci.yml（JDK 8/17 矩阵）+ publish-central.yml（-Pcentral 白名单发布）
└── LICENSE                 # MIT
```

> `tools/` 是**给仓库自己用的量具**，与 MCP 工具目录无关 —— 两个脚本都不产出工具、也不被任何
> pom 或 yml 引用（`ci-tally.py` 由 CI 那一步直接执行，`readme-sha-audit.py` 是本地账尺，
> 没有挂在 workflow 上）。MCP 工具在 `core/builtin/` 与 `server/role/` 两处。

`z-mcp-server` 留在 reactor 里，但根 POM 的 `<dependencyManagement>` 把六枚自家坐标逐条钉
`${project.version}`：继承来的 fleet 把它们钉在 repo1 的旧对外面值，而本仓 `<revision>` 已经往前走，
不钉就会被旧面值反向污染。全仓**没有任何** `maven.deploy.skip` 属性 —— 哪些坐标出门由
`publish-central.yml` 的 `-pl` 白名单决定（见「发布与构件」）。

---

## 🔧 技术栈

| 层级 | 技术（均来自 pom 实测） |
|------|--------------------------|
| 语言 / 运行时 | Java 8（口径由 `z-boot-parent` 的 `pluginManagement` 下发，产出 class major 52） |
| 框架 | Spring Boot 2.7.18（`z-boot-dependencies` 地板供给；根 POM 已删重复 import） |
| HTTP 层 | `spring-web` + `spring-webmvc` + `javax.servlet-api`，SSE 用 `SseEmitter` |
| 序列化 | `jackson-databind`（唯一 JSON 依赖） |
| 日志 | `slf4j-api`（core 侧不绑定实现；starter/server 测试期用 `logback-classic`） |
| 协议实现 | 自研，**不**依赖官方 `io.modelcontextprotocol.sdk:mcp`（它要 Java 17 + Reactor） |
| 构建 | Maven；`maven-jar-plugin:3.4.1`（开 `addDefaultImplementationEntries`）、`flatten-maven-plugin:1.7.2`（`oss`）、`maven-release-plugin:3.1.1` |
| 发布 | `central-publishing-maven-plugin:0.8.0`（`autoPublish=true`）+ source/javadoc/gpg 三插件（`central` profile） |
| 进程打包 | `spring-boot-maven-plugin` 的 `repackage`（只在 `z-mcp-server` 模块声明） |
| 测试 | JUnit 4（经 `junit-vintage-engine` 运行），`spring-test` / `spring-boot-starter-test` |
| CI | GitHub Actions：`mvn verify` + `python3 tools/ci-tally.py` 门禁，JDK 8/17 双矩阵 |

`z-mcp-core` 的依赖面刻意收在 Spring Web + Jackson + SLF4J 三样上，这样它进任何宿主应用
都不会带来第二套 JSON 库或响应式栈。

本仓没有 `Dockerfile` / `docker-compose*.yml` / `deploy/` / `k8s/` / `Makefile`（实测目录树），
交付物就是 fat jar；250 那台机器上的部署不在本仓的资产面里。

---

## 🚀 快速开始

### 编译

```bash
mvn -B -ntp clean verify                                 # JDK 8 基线；JDK 17 跑同一句（CI 两格都跑）
mvn -B -ntp -DskipTests -pl z-mcp-server -am package     # 只出可执行 fat jar
```

第三方与兄弟仓版本一律由父链供给，模块 POM 里不应再出现字面版本钉。少了 `z-mcp-server` 自己那段
`repackage`，`package` 出来的是一枚没有 `Main-Class` 的普通 jar —— 而单测跑在 `package` 之前，
**没有任何测试会因为它缺失而红**，只有真的 `java -jar` 起一次才量得到。

### 作为库接入宿主应用

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-mcp-starter</artifactId>
    <version>0.2.1</version>
</dependency>
```

```yaml
z:
  mcp:
    enabled: true          # 默认 false：z-opc 内与遗留的 z-agent-mcp-* 抢 /mcp，故总开关先关
    server-name: my-service
    instructions: "本服务提供订单查询与退款工具。"
```

注册一条工具：

```java
@Component
public class OrderTools {
    public OrderTools(McpRegistry registry) {
        registry.tool("query_order")
                .title("Query order")
                .description("按订单号查询订单")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},"
                        + "\"required\":[\"id\"],\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("查询订单"))
                .register(args -> orders.find(String.valueOf(args.get("id"))));
    }
}
```

### 起独立进程（三种角色同一个 fat jar）

```bash
Z_MCP_ROLE=hub   java -jar z-mcp-server/target/z-mcp-server-0.2.1.jar   # :18095
Z_MCP_ROLE=text  java -jar z-mcp-server/target/z-mcp-server-0.2.1.jar   # :18096
Z_MCP_ROLE=codec java -jar z-mcp-server/target/z-mcp-server-0.2.1.jar   # :18097
```

| 角色 | 缺省端口 | 目录内容 |
|------|----------|----------|
| `hub` | `18095` | 四条内置工具 + `find_tools`（按关键词检索目录）+ `registry_state`（上游真实连接状态），外加 `z.mcp.servers[]` 聚合来的工具 |
| `text` | `18096` | `text_stats`（字符/词/行/UTF-8 字节）、`text_transform`（upper/lower/snake/kebab/title/reverse） |
| `codec` | `18097` | `base64_codec`（标准与 URL-safe 两套字母表）、`hash_digest`（md5/sha-1/sha-256/sha-512，十六进制） |

两个叶子角色的 yml 都写了 `builtin-tools-enabled: false`（理由见「内置工具」那一段）。
`ZMcpServerApplication` 刻意不放在默认包：`@SpringBootApplication` 在默认包会把整个 classpath
当扫描根，自动装配的条件求值会在 r2dbc 之类的 SPI 上炸 `NoClassDefFoundError`。

### 环境变量（只列名字，值一律外部注入）

`z-mcp-server/src/main/resources/application.yml` **刻意不含任何口令**：写进版本库的令牌
等于公开令牌，而它是这台机器上所有 MCP 工具调用的通行证。

| 变量 | 落到哪 | 说明 |
|------|--------|------|
| `Z_MCP_ROLE` | `spring.profiles.active` | `hub` / `text` / `codec`，缺省 `hub` |
| `Z_MCP_PORT` | `server.port` | 每角色各有一个缺省值，同机跑三角色不必互相让端口 |
| `Z_MCP_BEARER_TOKENS` | `z.mcp.security.bearer-tokens` | 逗号分隔可多条（轮换期新旧并存）。未设时绑成**空列表**而非 `[""]` —— 后者会让闸永久拒绝一切请求；空列表 = 关闭鉴权，只适用于本机/内网 |
| `Z_MCP_TEXT_ENDPOINT` / `Z_MCP_CODEC_ENDPOINT` | hub 的 `servers[0..1].endpoint` | 上游不在缺省端口上时改这里，不用改 yml |
| `Z_MCP_UPSTREAM_TOKEN` | hub 发给上游的 `Authorization` | 中心侧的**出站**凭证，与上面那把**入站**的是两把（叶子收、hub 发） |

发布侧另需 `CENTRAL_USERNAME` / `CENTRAL_TOKEN` / `GPG_PASSPHRASE`，由 workflow 从 secrets 注入，
仓内不落值。

### 一次完整握手（curl）

```bash
SID=$(curl -sS -D- -o /dev/null -X POST localhost:18095/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}' \
  | awk -F': ' 'tolower($1)=="mcp-session-id"{gsub(/\r/,"",$2);print $2}')

curl -sS -o /dev/null -w '%{http_code}\n' -X POST localhost:18095/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'        # 期望 202

curl -sS -X POST localhost:18095/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" -H 'MCP-Protocol-Version: 2025-06-18' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

只读自检（探针，不经 JSON-RPC）：`curl localhost:18095/mcp/info`。它与数据面、控制面共用同一把闸，
所以配了 `bearer-tokens` 之后它也要带 `Authorization`。

---

## 🔌 端点一览

路径都拼在 `${z.mcp.base-path}` 之后（缺省为空串）。控制面跟着前缀走是 0.2.0 补的：
`base-path` 存在的理由就是"把 `/mcp` 让给遗留模块"，只搬数据面而控制面钉在原处，
等于让开的名字又被自己占回去。

| 方法与路径 | 归属 | 说明 |
|------------|------|------|
| `POST ${base-path}/mcp` | `JsonRpcController` | JSON-RPC 请求与通知；通知 ⇒ `202` 空体，请求 ⇒ `application/json` 单对象 |
| `GET ${base-path}/mcp` | `JsonRpcController` | 服务端→客户端 SSE 流；支持 `Last-Event-ID` 续传，补不上 ⇒ `400`；响应带 `X-Accel-Buffering: no` |
| `DELETE ${base-path}/mcp` | `JsonRpcController` | 终止会话 |
| `GET ${base-path}/mcp/info` | `JsonRpcController` | name/version/协议支持集/工具·资源·prompt·会话计数，`transport=streamable-http` |
| `GET ${base-path}/mcp/admin/overview` | `AdminController` | 计数、真实版本、协议矩阵（`servedAs` + `supported`）、上游聚合状态 |
| `GET ${base-path}/mcp/admin/tools` | `AdminController` | 名称、描述、归属 server（内置标 `(builtin)` 而非 `null`）、JSON Schema |
| `GET ${base-path}/mcp/admin/servers` | `AdminController` | 目录里现在广告了什么 |
| `GET ${base-path}/mcp/admin/servers/state` | `AdminController` | 上游现在到底连没连上、为什么（`connected` / `lastError`） |
| `GET ${base-path}/mcp/admin/health` | `AdminQueryService` | `UP` / `DEGRADED` + `unreachableServers`；自己活着就是 UP，不因别人的故障报 DOWN |

配置项清单见 [`McpProperties.java`](z-mcp-core/src/main/java/com/zifang/z/mcp/core/properties/McpProperties.java)
（`z.mcp.*` 下 5 个嵌套组 `session` / `sse` / `security` / `rate-limit` / `servers`）。
0.1.x 里 `base-path` / `expose-admin` / `health-check-interval-seconds` / `servers`
**没有任何人读取**，是死配置；0.2.0 起每一项都对应一个真实行为。其中三条由 Spring 按字符串键绑定
而不是 getter（`enabled`、`expose-admin` 走 `@ConditionalOnProperty`，`base-path` 走
`@RequestMapping` 占位符），所以"全文找不到 `getBasePath()` 的调用点"不等于这条配置是死的。

---

## 🧪 测试

```bash
mvn -B -ntp verify
```

盘上现数（本仓口径 `git grep -c '^    @Test'`）：**404 条用例** —— core **302** / starter **69** /
server **26** / admin **7**，共 31 个测试类。`z-mcp-api` 没有测试源码（纯 DTO），
CI 的表里那一行如实标 `-`，那是说得出名字的空格，不是缺格。

门禁不在 `mvn` 那一步，而在 [`tools/ci-tally.py`](tools/ci-tally.py)：CI 用
`mvn verify -Dmaven.test.failure.ignore=true` 把四格**全跑完**（Maven 默认 fail-fast，第一个模块一红
它下游一条都不跑，红消息里分不清"下游也通过"和"下游根本没测"），再由这个脚本判红 ——
任何 failure/error、任何 `skipped>0`、该有报告的模块没报告，三条各对应一种"看着像绿的假象"。
"该跑哪几格"从根 `pom.xml` 的 `<modules>` 现读，不写死。

[`ci.yml`](.github/workflows/ci.yml) 跑 JDK **8 与 17** 两格（不是冗余）：8 是 pom 的 source/target 基线
与唯一被承诺支持的版本，17 是构件对外发布后 Boot 3 一侧消费者会跑的版本。每格
`timeout-minutes: 20`（挂死的测试进程不会自己结束，本仓实测撞到过一次 JDK 8 httpserver 收尾死锁）。

几条需要注意的前提：

* `z-mcp-server` 的角色/端口/令牌用例起真 Tomcat 与真 profile（`BearerGateTest`、`RolesTest`、
  `PortOwnershipTest`）；它们绑的是 `127.0.0.1` 的 1809x 高端口，不与宿主服务的 `8888` 抢。
* stdio 那一路用 `StdioMcpServerFixture` 当被拉起的子进程，所以它要求 `target/test-classes` 已存在
  （同模块 `mvn test` 自然满足）。它是**本仓的测试 fixture**，不是某个公开 server。
* 时序类用例（取消、SSE 帧原子性、心跳）历史上在 CI 上间歇红过，现在的判据是"有上限的等待"
  而不是 `sleep` + 断言；`Skipped` 恒为 0 —— 本仓没有任何 `@Ignore` / `Assume`。

---

## 📦 发布与构件

`central` profile（source + javadoc + gpg + central-publishing）定义在**根** POM 上，所以它会注进
每一个模块 —— 哪些坐标真正对外，由 [`publish-central.yml`](.github/workflows/publish-central.yml)
的 `-pl` 白名单决定（`maven.deploy.skip` 对这个插件不生效，写了属性却没真跳过是最难查的那种"发布成功"）。

Maven Central 逐枚实测（`https://repo1.maven.org/maven2/io/github/yuku123/…`，普通 GET 取 `.pom`、
ranged GET 取 `.jar`；repo1 对 HEAD 不给 200，所以判活必须用 GET）：

| artifact | `0.2.1` pom | `0.2.1` jar（ranged） | 结论 |
|----------|-------------|------------------------|------|
| `z-mcp` | 200 | —— | 已发布（聚合 POM） |
| `z-mcp-api` | 200 | 206 | 已发布 |
| `z-mcp-core` | 200 | 206 | 已发布 |
| `z-mcp-starter` | 200 | 206 | 已发布 |
| `z-mcp-admin` | 200 | 206 | 已发布 |
| `z-mcp-server` | 200 | 206 | **已发布** |

`maven-metadata.xml` 里 `z-mcp` 与 `z-mcp-server` 的 `latest`/`release` 都是 `0.2.1`。
`z-mcp-server` 在中央上的历史只有 `0.2.0`/`0.2.1` 两枚（同版本段 `z-mcp-core:0.1.2` 是 200，
而 `z-mcp-server:0.1.2` 是 404 —— 那一版确实没发）。

两点要写清，因为它们表面上互相矛盾：

* `publish-central.yml` 的白名单是 `z-mcp-api,z-mcp-core,z-mcp-starter,z-mcp-admin`（`-am` 顺带带上
  聚合根 POM），**不含 `z-mcp-server`**；
* 但 repo1 上确实躺着 `z-mcp-server` 的 `0.2.0`/`0.2.1` 全套（pom + jar + sources + javadoc），
  且本仓没有 `excludeArtifacts` 把它挡在门外。

也就是说这一枚可执行进程的坐标是**由那条 workflow 之外的路径**上到中央的；`ad69da1` 已经把
"z-mcp-server 不在中央"这个旧口径改掉了（那句错话写在 `8e12fdc` 之前）。要不要改用
`excludeArtifacts` 把它收回，属于"已经公开出去的坐标怎么收"，另议，本次 README 只如实记录实测状态。

`flattenMode=oss`（`8e12fdc`）把 `<parent>` 剥掉、版本落成字面量，让五份叶子发布 POM 自包含 ——
旧模式 `resolveCiFriendliesOnly` 下消费者要顺着 `z-mcp → z-boot-parent → z-boot-fleet` 才能补齐版本，
等于把我们的 parent 链变成消费者依赖的一部分。`autoPublish=true`（`89ea43c`）补上之前，
deployment 会停在 `VALIDATED`、错误 0 却永远不公开，而 Maven 一路报 `BUILD SUCCESS`。

版本上报的真实链路：`maven-jar-plugin` 开了 `addDefaultImplementationEntries`，
`ZMcpAutoConfiguration` 读自己那个包的 `Implementation-Version` 覆盖 `z.mcp.server-version`。
所以属性里那个 `0.2.0` 字面值只是**兜底**（`McpProperties` 至今仍写着 `"0.2.0"`），
`/mcp/info` 报出来的才是打进 MANIFEST 的真实版本（`0.2.1`）。根 POM 注这一段的原因是：默认情况下
jar 插件根本不发这个头，那条判据恒为 `null`，线上版本永远是 yml 里手抄的那一个。

---

## 🧱 已知边界

* **`completion/complete`、`resources/subscribe`/`unsubscribe` 未实现**，一律 `-32601`，不在
  capabilities 里谎报。`elicitation`/`sampling`/`roots` 两侧都不广告 —— 但"不广告"不等于"不许被问"。
* **上游反过来发的请求只有 stdio 那一路答得了**：stdio 传输会回答上游的 `ping`（空 `result`）
  与其余请求（`-32601`，id 原样回显）。这不是礼貌 —— filesystem / git 那一类单线程本地 server
  推完一条就阻塞在下一口读上，不回它它就是不动。HTTP/SSE 那一路在现有传输形状下根本答不了 ——
  一次 POST 配一次响应，hub 也从不为上游开 GET 流，对端没有通道把请求递进来。
* **上游推来的目录变更同样只有 stdio 收得到**。配了 http 上游时"目录多久跟上"只由
  `health-check-interval-seconds`（缺省 60 秒）决定，调小它是唯一旋钮。这不是漏了一个分支，是传输形状。
* `resources/list_changed` 与 `prompts/list_changed` 的上游通知收到只记一笔 DEBUG ——
  接入器目前只聚合 tools，没有"重取什么"可重取。
* **入参校验器只认一个 JSON Schema 子集**。认得的包括 `type`（含数组形式、`integer` 接受整值 double）、
  `required`、`properties`、`items`（单份/布尔/数组三种形式）、`prefixItems`、`additionalItems`、
  `additionalProperties`、`patternProperties`、`propertyNames`、`min/maxProperties`、
  `minLength`/`maxLength`/`minItems`/`maxItems`（长度按 **Unicode 码点**数，不做归一化也不做字素簇切分）、
  `pattern`、`enum`、`const`、`uniqueItems`（只认真值 `true`，逐对扫 ⇒ 几千元素的入参是平方级）、
  `exclusiveMinimum`/`exclusiveMaximum`、`multipleOf`、`nullable`、
  `$ref`/`$defs`/`definitions`（只跟文档内指针，跨文档/远程 ref **一律不抓取** ——
  抓一次就把一个校验器变成 SSRF 的出口；解不开的 ref 是一次违规而不是"没有约束"）、
  `allOf`/`anyOf`/`oneOf`/`not`、布尔 schema。
  **不认的关键字按 JSON Schema 语义放行而不作约束**：`format`（注记不是断言，`date`/`date-time`/`email`/
  `uri`/`uuid4` 等实测全部放行）、`contains`、`dependentRequired`、`if`/`then`/`else`、`$id`/`baseUri`。
  所以 hub 逐字抄上游的 `outputSchema` 再自己判时，兑现强度是"上游 schema × 本仓校验器"的**取小者** ——
  别把 hub 的绿读成上游语义的绿。
* **`outputSchema` 只校验 `structuredContent`，一个字节都不校验 `content`**（两家官方参照同样）。
  给模型读的那段文字不在承诺范围内，要可信得由工具体自己保证两份同源。`isError` 的结果**不校验** ——
  那是生产者留给模型自我纠正的原话，改判成校验失败会把它盖掉。
* `notifications/progress` 不推送：请求里的 `_meta.progressToken` 不被读取，工具是同步跑完才回一次结果。
* 续传缓冲与会话都在**进程内存**里：多实例部署下重连打到另一个实例 ⇒ `400`，进程重启同理。
  客户端必须把 400 当"退回不续传"处理，别当服务端故障。要跨实例得把缓冲外置，这一版没有。
* 取消是**协作式**的：服务端做的是 `Future.cancel(true)`，即发一次线程中断。工具体不理中断就照样跑完 ——
  本层保证的是"那份结果不会再被当成成功发回去"，不是"工具一定停在原地"。取消**不向上传播**：
  调的是聚合自上游的工具时，本层不会向上游转投 `notifications/cancelled`，远端仍会被它跑完。
* 在飞登记只挂在 `tools/call` 那一段；取消一条正在分页的 `tools/list` 或已经答完的请求都是打空
  （按协议静默，不报错）。`session.enabled=false`（无状态模式）下没有可寻址会话，取消因此只能是空操作。
* 心跳写出手池是**有界**的（8 根）：第九个不再读的对端不会让心跳停摆，但会让那一拍里排不上手的流
  **跳过一拍**，服务端留一行 WARN。默认 30 秒对 60/75/100 秒这几道常见反代空闲闸够用；
  把反代超时配得比它更长才是根治，心跳只是兜底。
* 鉴权是**静态 Bearer 白名单**。协议授权章节的 OAuth 2.1 那一套（`.well-known` 元数据、动态注册、
  PKCE、令牌轮换）没有实现 —— 要对外暴露 `/mcp`，请放在做授权的网关后面，别把白名单当账号系统。
  `bearer-tokens` 为空即**完全放开**，只应出现在本机/内网可信环境。
* 闸只有一把 `TransportSecurityGuard.checkRequest(HttpServletRequest)`，五个调用点盖住
  `POST /mcp`、`GET /mcp`、`DELETE /mcp`、`GET /mcp/info` 与控制面全部 mapping。
  0.2.0 之前它只挡 `/mcp`，控制面把上游 endpoint、`lastError`、全部工具 Schema 这些拓扑信息
  白送给任何能连上端口的人，而配置者以为配了 token 就锁上了。401 的 `WWW-Authenticate` 按
  RFC 6750 §3.1 分三种形状（裸挑战 / `error="invalid_request"` / `error="invalid_token"`），
  三个数据面入口与控制面共用同一个出口；挑战里**不带** `resource_metadata`，
  因为静态白名单没有授权服务器可指，指出去就是一个 404 的广告。
* 控制面只有 JSON，没有页面。`z-mcp-admin/src/main/resources/templates/z-mcp-admin/index.html`
  是**死资源**：没有任何代码引用它，而 Spring Boot 只自动服务 `static/`（`templates/` 需要模板引擎），
  所以它连"打开就能看"都做不到。要控制台界面就自己拿 `/mcp/admin/*` 渲染。

---

## 🔗 与 agent 族的分层关系

实测两侧**都没有**依赖：

* 本仓五份 POM 里没有任何 `z-agent-kernel*` 依赖，`src/main/java` 里没有一处 `com.zifang.z.agent` import。
  根 POM 把这条写成原则：基建模块不能反向依赖应用层（0.1.x 那四枚 kernel 依赖无人 import）。
* `z-agent-kernel:z-agent-kernel-mcp` 是一份**零依赖 SPI**（POM 里只有 test 作用域的 junit），
  声明 `McpClient` / `McpTransport` / `StdioMcpTransport`；它自己的 javadoc 写着"实现方: z-mcp"，
  `<description>` 写着 `interface McpClient (0 实现, 实现方 z-mcp)`。

所以当前的关系是：**kernel-mcp 那侧点名 z-mcp 为实现方的意图存在，但本仓并未实现那个接口，
两侧之间没有 Maven 也没有源码依赖**。`z-mcp-core` 的 `McpRemoteClient` / `JsonRpcExchange`
是自己的一套客户端抽象，不是 kernel SPI 的实现。谁并到谁（kernel 侧改吃 z-mcp，还是 z-mcp 反向实现
SPI）是跨仓的架构决定，不在本仓能自己动的范围里。

本仓**确实**已经有下游消费者（上一版 README 记的还是旧账，本次纠正）：

* `z-boot/z-boot-fleet` 的 `<z-mcp.version>` 现钉 `0.2.1`，受管五枚 `z-mcp-*` 坐标；
* `z-boot/z-boot-integration-starters/z-boot-mcp-starter` 直接依赖 `z-mcp-starter`；
* `z-opc/z-middleware-integration-test` 依赖 `z-boot-mcp-starter`，其
  `ZAgentAiL3StarterCentralPullIT` 从中央仓库拉构件并反射断言
  `com.zifang.z.mcp.starter.autoconfig.ZMcpAutoConfiguration` 与
  `com.zifang.z.mcp.core.controller.JsonRpcController` 上的 `@PostMapping("/mcp")` ——
  也就是说**类名与那条映射已被跨仓用例钉住**，改名前要先改那里。

---

## 📄 License

MIT，见 [`LICENSE`](LICENSE)；根 POM 的 `<licenses>` 同声明 MIT License。

_Maintained by the z-opc-foundation organization._
