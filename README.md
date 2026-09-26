# z-mcp

Java **8** 可用的 [Model Context Protocol](https://modelcontextprotocol.io) 服务端 + 上游聚合客户端，Spring Boot 自动装配。

给一个已存在的 Boot 应用挂上 `/mcp`，它同时成为：

* **MCP server** —— 业务工具经 `McpRegistry` 注册后可被任意 MCP 客户端调用（Streamable HTTP）；
* **MCP 网关** —— 把别处已经存在的 MCP server（HTTP 或本地 stdio 进程）聚合成一份工具目录，撞名自动加前缀，上游挂了自动把它的工具撤走。

## 为什么协议层是手写的

官方 `io.modelcontextprotocol.sdk:mcp` 要求 **Java 17 + Project Reactor**，而 z-mcp 要能进还在 Java 8 上的存量服务，也要避免把 Reactor 塞进一个中间件层的依赖树。所以协议/传输层自己实现，并且只用 JDK 8 能用的东西：

* HTTP 客户端用 `HttpURLConnection`（`java.net.http` 是 11+）；
* stdio 子进程的 stderr 由独立线程排空（`ProcessBuilder.Redirect.DISCARD` 是 9+，而且丢弃 stderr 等于丢掉唯一的诊断线索）；
* 不引入 Reactor/WebFlux，SSE 用 MVC 的 `SseEmitter`。

代价是要自己钉住协议细节，因此**每一个约定都配了一个测试**（见「构建与测试」）。对照的基准是 [协议规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) 与 [官方 java-sdk](https://github.com/modelcontextprotocol/java-sdk)（后者 README 明示 Java 17+ 且以 Project Reactor 为 Reactive Streams 实现）。

## 模块

| artifact | 作用 |
| --- | --- |
| `z-mcp-api` | 纯 DTO 与异常：`JsonRpcRequest/Response`、`CallToolResult`、`ContentBlock`、`McpToolDto`/`McpResourceDto`/`McpPromptDto`/`McpServerDto`、`ToolAnnotations` |
| `z-mcp-core` | 注册中心、协议分发器、Streamable HTTP 端点、安全/会话/限流、上游客户端与 stdio/HTTP 传输、内置工具 |
| `z-mcp-starter` | `ZMcpAutoConfiguration`，同时提供 Boot 2 的 `spring.factories` 与 Boot 3 的 `AutoConfiguration.imports` |
| `z-mcp-admin` | 控制面只读端点 `${z.mcp.base-path}/mcp/admin/*`，默认不挂载 |

## 快速开始

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-mcp-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

```yaml
z:
  mcp:
    enabled: true          # 默认 false: z-opc 内与遗留的 z-agent-mcp-* 抢 /mcp
    server-name: my-service
    instructions: "本服务提供订单查询与退款工具。"
```

注册一个工具：

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

自检（不经过 JSON-RPC，给探针用）：`curl localhost:8080/mcp/info`

一次完整握手（协议要求非 `initialize` 请求必须带会话头）：

```bash
SID=$(curl -sS -D- -o /dev/null -X POST localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}' \
  | awk -F': ' 'tolower($1)=="mcp-session-id"{gsub(/\r/,"",$2);print $2}')

curl -sS -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'   # 期望 202

curl -sS -X POST localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" -H 'MCP-Protocol-Version: 2025-06-18' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

## 协议合规

支持的 revision：`2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25`。客户端请求的版本在列表内则原样回显，否则回 `2025-11-25`；未声明版本时按协议兜底假定 `2025-03-26`。

2026-07-28 的无状态 revision **有意不跟**：它取消了会话，而本项目的上游聚合与安全边界都以会话为主键。

| 已实现的方法 | |
| --- | --- |
| `initialize` | 协商版本、签发 `Mcp-Session-Id`、回显 capabilities |
| `notifications/initialized` | 无 `id`（严格指这个键不存在，`"id": null` 是另一回事，见"错误语义"）的输入一律 `202 Accepted` 且响应体为空 |
| `notifications/cancelled` | 同样只回 `202`，但它**真的去取消**：见下面"取消"一节 |
| `ping` | 空结果，`initialize` 之前也允许 |
| `tools/list`、`tools/call` | 三类目录各自声明 `listChanged=true`，注册表变更向在线会话推对应类目的通知：工具变更 → `notifications/tools/list_changed` |
| `resources/list`、`resources/templates/list`、`resources/read` | 变更推 `notifications/resources/list_changed`。`resources/subscribe`、`resources/unsubscribe` **回 `-32601`**：没声明 `subscribe` 能力，就不假装订阅成功 |
| `prompts/list`、`prompts/get` | 内置模块不提供 prompt，注册表留给业务侧；业务侧 `registerPrompt` 时推 `notifications/prompts/list_changed` |
| `logging/setLevel` | 级别枚举按协议校验后记在会话上，并且**真的是门槛**：低于它的 `notifications/message` 不再往那条流上推（默认 `info`）。服务端目前在下述时刻产生日志通知：工具抛异常（`warning`）、工具超时（`error`） |

未实现：`completion/complete`、`resources/subscribe`、`resources/unsubscribe` —— 一律回 `-32601`，不在 capabilities 里谎报。

通知走 `GET /mcp` 的 SSE 流，每条通知一个递增的 event id，且**不带 `id` 字段**（带了就成了请求，客户端会等着回包）。没建流的会话收到广播是空操作 —— 线上绝大多数客户端只用 POST，广播不能把它们变成异常。

开流时协议（2025-11-25 "Resumability and Redelivery"）点名的形状是一条 **priming 事件**：只有 event id、`data` 为空，外加一个 `retry` 字段（客户端对它是 MUST）。所以流的第一帧是 `:z-mcp stream open` 注释 + `retry:3000` + `id:N` + 空 data —— 客户端在收到任何通知之前就先拿到一个可续传的坐标，而不是等到第一次断线才发现手里什么都没有。断线后用 `GET /mcp` + `Last-Event-ID: N` 回来，服务端把**缓冲里仍留着的**、id 大于 N 的事件补发，再接实时流；补发与挂载在同一次锁内完成，所以"广播正好落在补发快照之后、挂载之前"这个谁都不丢的窗口不存在（`emit` 那边取号、入缓冲、摘 emitter 快照同样是原子的一步）。要的位置已经被缓冲淘汰 ⇒ `400`，让客户端退回"不续传、重新计数"的用法 —— 协议对这种情况**没有规定**，400 是本服务的选择，因为静默少给几条比明说"补不上了"更难查。

**心跳**（这一条不是协议要求，规范全文没有提到 keep-alive、SSE 注释帧或任何流层的 ping 帧 —— 注意 `ping` **请求**是另一回事：它要客户端主动发、服务端回空结果，救不了服务端一侧想留住的那条安静连接）：一条安静的 MCP 流在云上活不过中间层的空闲超时——Fly.io 的 75 秒、Cloudflare 的 100 秒是一篇生产部署复盘里报出来的数字（本仓没有复测这两个值，它们只是"为什么要默认 30 秒"的依据），而"目录没什么变化"正是一个 MCP 流的常态。0.2.0 起服务端在流**安静**时每 `sse.keep-alive-seconds`（默认 30 秒，取这个值是为了压过最常见的 60 秒那道闸）写一个 `:keep-alive` 注释帧。它写成 SSE **注释**（`:` 开头），因为 SSE 自己那份规范（WHATWG HTML 的 "server-sent events" 一节）规定注释行的内容必须被忽略——于是这份流量对客户端是零成本，不需要为它实现任何处理分支；它**不占事件序号、不进续传缓冲**，占号的心跳会把一条安静的流推到很高的 id，而那段序号在缓冲里什么都没有，客户端拿着它回来续传只会撞到一个没意义的 `400`，等于用"能连上"换掉了"能续上"。心跳线程也只在第一条流真的挂上来时才建：只用 POST 的服务不该因此常驻一个线程。写失败的流（早就不有人读的）由心跳顺手摘掉——它是这类客户端的第一个发现者，因为服务端不写就没有人写。

**取消**（`notifications/cancelled`，协议 §Cancellation）：改之前它一直是"202 收下、然后什么都不做"，于是"支持取消"的真实内容是——客户端按了停止，服务端把那次工具调用跑完，并且把结果当成功发回去。协议给接收方的是三条义务：停止处理、释放资源、**不**再发响应；这里按能兑现的形状实现：

* 请求 id 在会话里登记为"在飞"，`notifications/cancelled` 按 `params.requestId` 找到它并 `Future.cancel(true)` —— 中断真的落到工具线程上，卡在 `sleep`/`wait`/阻塞 IO 上的工具体因此立刻收口；
* **寻址先按会话、再按 id 的类型**：`requestId` 是客户端随手挑的小整数，一张全局表等于任何一条已握手的会话都能凭 `{"requestId":1}` 停掉别人的工具调用；而 `1` 与 `"1"` 在 JSON-RPC 里是两个不同的请求 —— 官方 TypeScript SDK 的在飞表是 `Map<RequestId, AbortController>`（`protocol.ts:562`），JS 的 `Map` 按 SameValueZero 比键，数字与字符串天然落在两个槽位，所以这里的键也编了类型前缀；
* 协议**没有**规定被取消的请求该回什么（它假定的是流式传输里"不再发响应"，并明确要求"取消比答案晚到"这个 race 两侧都要受得住）。Streamable HTTP 下调用方那条 POST 正阻塞在 socket 上，沉默就等于让它白等一整个读超时——所以这里给一条 `-3280 RequestCancelled` 的 JSON-RPC error，理由（`params.reason`）带在 `message` 里。这是一个**实现选择**，不是协议条文：客户端把它当"我取消成功了"处理，而不是当服务故障；
* 取消打空（id 不存在、已经答完、属于别的会话）不回任何错误、也不产生响应体——通知只有 `202` 这一个出口，那一层是 race，只能静默。这条静默与官方 SDK 同形：`_oncancel` 只在 `requestId === undefined` 时原样返回，源码注释还专门钉住"`0` 和 `''` 是合法的请求 id"（按"假值"判缺就等于把它们当成没带 id），命中不了也只是 `controller?.abort(reason)` 里的 `?.` 空转；
* 登记表的收口按**对象身份**比对：SDK 在 `.finally()` 里写的是 `if (get(request.id) === abortController) delete(request.id)`，这里 `endRequest(key, mine)` 同样比引用 —— 否则同一枚 id 先后两次请求会互相摘掉对方的登记，晚到的那次取消就打空到一个其实还在跑的请求上。

**错误语义**（这是 0.1.x 与真实客户端分歧最大的地方）：

* 传输/信封层问题走 HTTP 状态码 + JSON-RPC error：`-32700` 解析失败、`-32600` 非法请求、`-32601` 方法未知、`-32603` 内部错误；`-3280` 请求已被客户端取消；
* **"通知"与"非法请求"的分界只看 `id` 这个键在不在**：没有 `id` 才是通知（`202` 空体，协议规定通知不得产生任何响应）；显式 `"id": null` **不是**通知 —— JSON-RPC 把 `null` 留给"响应方连 id 都取不到"那个场合，并规定请求里的 id MUST NOT 为 null，官方 SDK 的 schema 也只收 string|number。写到这里之前是"按缺 id 静默 202"，于是把 id 写成 null 的客户端要为一条永远不会来的答案白等到读超时；现在它拿到 `-32600` 且回执里的 `id` 原样为 `null`；
* 未知工具、参数不合 `inputSchema` ⇒ `-32602`；
* **工具自己抛异常 ⇒ 不是协议错误**，而是 `result.isError = true` + 文本 content（2025-11-25 明确如此）。把它回成 JSON-RPC error 会让客户端认为连接坏了，而不是"这个工具失败了，换个参数重试"。

**Streamable HTTP**：

* 单一端点 `${z.mcp.base-path}/mcp`，POST/GET/DELETE 三个动词；
* `Accept` 必须同时列出 `application/json` 与 `text/event-stream`（协议原文是 MUST，且这条 MUST 属于 **POST** 那一路），否则 `406`；不带 `Accept` 或 `*/*` 放行；
* `GET /mcp`（服务端主动通知的那条流）协议只给了两个出口：要么 `text/event-stream`，要么 `405`——**全文没有 GET 侧的 406 规则**。所以这里只要求客户端表明"收得了流"（`text/event-stream`、`*/*` 或不带 `Accept`），否则回 `405`。为什么不是 406：真客户端把 GET 流上的 406 读成"这条 MCP 连接坏了"（Gemini CLI、LibreChat 都为此报过 issue），把 405 读成"这里没有推送流，继续用 POST"——后者才是它们处理得动的形状。回 200 更糟：静默开一条没人看的流，配置者只会以为推送生效了；
* 非 `initialize` 请求必须带 `Mcp-Session-Id`；服务端不再认识该 id ⇒ `404`，客户端据此重新 `initialize`；
* `GET /mcp` 带 `Last-Event-ID` 时按上一节通知里说的续传处理：非数字或负数 ⇒ `400`（事件 id 是我们自己发的十进制数，别的一律按"这客户端不可信"办），位置已被缓冲淘汰 ⇒ `400`，其余 ⇒ 补发 + 续流；流上响应一律带 `X-Accel-Buffering: no`，因为 nginx 默认真的会整条缓冲 SSE —— 不关的话推送在配置层面"生效"、在客户端一侧从来不到货；
* `initialize` 带着别人的会话 id 来 ⇒ `400`；
* `MCP-Protocol-Version` 与会话协商结果不一致 ⇒ `400`；**不带**这个头不是错误（协议规定缺省时按 `2025-03-26` 假定，本项目按会话已协商的版本走）；
* 请求体上限 4 MiB ⇒ `413`；批量数组体按 2025-06-18 的删除明确拒绝，而不是静默只处理第一个；
* 已废弃的 HTTP+SSE 传输（2024-11-05 的双端点形状）不再接受；
* 限流按会话（无会话时按来源地址）计数，超限 `429` + `Retry-After: 1`；
* `Origin` / `Host` 校验默认收敛为同源，防 DNS rebinding；`Authorization: Bearer` 白名单可选。

## 配置参考 `z.mcp.*`

0.1.x 里 `basePath` / `exposeAdmin` / `healthCheckIntervalSeconds` / `servers` **没有任何人读取**，是死配置。0.2.0 起每一项都对应一个真实行为，缺省值按"协议合规优先"选取。

逐项核过：43 个 `private` 字段里 5 个是嵌套配置组（`session` / `sse` / `security` / `rate-limit` / `servers`），剩下 **38 个可配字段有 36 个存在 main 代码里的 getter 读取点**，另外 2 个由 Spring 自己绑定 —— `base-path` 走两个 controller 上的 `@RequestMapping("${z.mcp.base-path:}")`，`expose-admin` 走 `@ConditionalOnProperty`。所以**全文找不到 `getBasePath()` 的调用点不等于这条配置是死的**，别照着"有没有人调 getter"去复查这一项。这两个字段另有真 Tomcat 用例兜底（见「构建与测试」里的 `ZMcpHttpEndpointTest` / `ZMcpAdminEndpointTest`）：不起 mock、直接按 URL 打进去。这组数是量的、可复跑（脚本 `~/.cache/zmcp_logs/cfg_cov.py`）：把 `McpProperties` 的注释剥掉后逐个 `getXxx()`/`isXxx()` 去 main 源码（**27 个文件**，测试与 `target/` 排除）里找调用点，命中为零的只有 `basePath`、`exposeAdmin` —— 也就是上面那两条。加进 `sse.keep-alive-seconds` 之后重跑过一遍，读取得多点恰好多出 `McpSessionStore.startKeepAliveIfNeeded()` 这一处。

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `false` | 总开关。关掉时一个 z-mcp bean 都不装配 |
| `base-path` | `""` | 网关前缀，同时拼在数据面 `/mcp` 与控制面 `/mcp/admin` 之前 |
| `expose-admin` | `false` | 是否挂载 `${z.mcp.base-path}/mcp/admin/*` |
| `server-name` / `server-title` / `server-version` | `z-mcp` / … / `0.2.0` | `initialize` 里的 `serverInfo`。版本以打进 MANIFEST 的 `Implementation-Version` 为准，配置值只兜底 |
| `instructions` | 空 | 服务级提示词，会进入 LLM 上下文 |
| `require-initialized` | `true` | 未完成 `initialize` 前只接受 `ping` |
| `validate-arguments` | `true` | 按 `inputSchema` 校验工具入参，不合法按执行错误（`isError`）回 |
| `strict-tool-names` | `true` | 工具名强制 `[A-Za-z0-9_.-]{1,128}` |
| `page-size` | `0` | `tools/list`/`resources/list`/`prompts/list` 每页条数，`0` = 一次给全 |
| `tool-timeout-seconds` | `60` | 单次工具调用超时，`0` = 不限。这一项同时决定**能不能中断**：`>0` 时工具跑在线程池里、取消可以中断它；配成 `0` 就在调用线程上直接跑，没人能中断（那条请求仍会以 `-3280` 收口，但要等工具自己跑完） |
| `health-check-interval-seconds` | `60` | 上游 server 健康检查周期，`0` = 禁用 |
| `session.enabled` | `true` | `false` = 无状态模式，每请求独立、不发会话头 |
| `session.ttl-seconds` / `session.max-sessions` | `3600` / `1000` | 会话回收与上限 |
| `sse.enabled` | `true` | `false` 时 `GET /mcp` 回 `405` |
| `sse.timeout-ms` | `300000` | 服务端主动流的存活时长 |
| `sse.buffer-size` | `256` | 每个会话为续传留住的最近事件条数；`0` = 不缓冲，于是带 `Last-Event-ID` 一律 `400`（流本身照开）。这条同时也是**内存上界**：一个安静的长命会话不能被无限多的广播撑大 |
| `sse.retry-ms` | `3000` | 下发给客户端的 SSE `retry` 字段，即"流断了隔多久重来"；`0` = 不带该字段 |
| `sse.keep-alive-seconds` | `30` | 一条流**安静**时每这么多秒写一个 `:keep-alive` 注释帧，把连接从中间层的空闲超时里救出来；`0` = 不发，且连心跳线程都不建 |
| `security.allowed-origins` | 空 | 空 = 只允许与 `Host` 同源 |
| `security.allow-missing-origin` | `true` | 无 `Origin` 头的非浏览器客户端放行 |
| `security.allowed-hosts` | 空 | 空 = 不校验；生产应收敛为显式白名单 |
| `security.bearer-tokens` | 空 | **空 = 关闭鉴权**，只适用于本机/内网可信网络；非空时数据面与控制面同时生效 |
| `security.trusted-proxy` | `false` | 仅当部署在可信反代后才取 `X-Forwarded-Host` |
| `rate-limit.enabled` / `requests-per-minute` / `burst` | `true` / `300` / `50` | 协议要求服务端 MUST 限流，故默认开 |
| `servers[]` | 空 | 上游 MCP server 列表，见下节 |

## 接入上游 MCP server

```yaml
z:
  mcp:
    servers:
      - name: acme
        transport: http                      # http / streamable-http / http+json 都接受
        endpoint: http://127.0.0.1:9001/mcp
        headers:
          Authorization: "Bearer ${ACME_TOKEN}"
        connect-timeout-millis: 5000         # 没有超时的客户端 = 把本机线程交给别人
        read-timeout-millis: 60000
      - name: local-fixture
        transport: stdio
        command: /usr/bin/java
        # 这条是**本仓自己的测试 fixture**（`mvn test` 后即存在于 target/test-classes），
        # 不是某个公开 server；拿它当最小可跑样本，比抄一个装不出来的第三方 server 更有用。
        args: ["-cp", "target/test-classes", "com.zifang.z.mcp.core.client.StdioMcpServerFixture"]
      - name: legacy
        transport: http+sse                  # 会被拒绝并在控制面显示拒绝原因
        endpoint: http://127.0.0.1:9002/sse
```

行为：

* **首连不在启动路径上**。配了几个 server、它们全挂着，也不会拖慢或拖死应用启动（N 个死上游 × 连接超时）。
* 撞名自动加 `<server>__` 前缀（`acme__get_time`），内置与上游两份都保留 —— 静默覆盖会让"工具去哪了"变成一个查不完的坑。
* 上游不可达 ⇒ 它的工具从目录里**撤走**，并在 `lastError` 里留下原因；恢复后重新发布。
* 重新同步只换目录，不重握手（会话复用）；但一旦连接被判死并摘除，下一次健康检查会新建传输，而不是对着已死的管道重连 —— 否则"恢复"永远不会发生。
* 状态里的 `connected` 由传输本身回答（`JsonRpcExchange.isAlive()`），不是握手时置位、之后永不复位的 flag。stdio 的子进程一退（不管是进程没了还是 stdout 到了 EOF），`/mcp/admin` 立刻变 `false`，不需要先失败一次调用才看得见。
* `enabled: false` 的 server 连拨号都不会发生。
* 一个死 server 不能带走另一个 server，也不能带走内置工具。
* stdio server 没有 URL，控制面就把它的**命令行**当入口显示（`/usr/bin/java -cp …`），而不是回一个 `null` 让运维去猜是哪个子进程。

## 内置工具与资源

工具：`echo`、`get_time`（`iso` / `epoch` / `iso_local`）、`generate_uuid`、`system_info`。
资源：`z-mcp://tools`、`z-mcp://servers`、`z-mcp://self` —— 注册表自身可读，声明 `capabilities.resources` 才不是谎报。

`get_time` 在 0.1.x 用 `SimpleDateFormat` 打 `Z` 后缀，实际是 JVM 默认时区（声明与行为不符）；现在走 `java.time` 且显式 UTC。

## 控制面 `z.mcp.expose-admin=true`

下表的路径都拼在 `${z.mcp.base-path}` 之后（默认为空串 ⇒ 就是 `/mcp/admin/*`）。控制面跟着前缀走是 0.2.0 补的：`base-path` 存在的理由就是"把 `/mcp` 让给遗留模块"，只搬数据面、控制面还钉在 `/mcp/admin`，等于让开的名字又被自己占回去。

| 端点 | 内容 |
| --- | --- |
| `GET /mcp/admin/overview` | 工具/资源/prompt/server 计数、真实版本、协议版本矩阵、上游聚合状态 |
| `GET /mcp/admin/tools` | 名称、描述、归属 server（内置工具显式标 `(builtin)`，不发 `null`）、JSON Schema |
| `GET /mcp/admin/servers` | 名称、endpoint、transport、工具数 |
| `GET /mcp/admin/servers/state` | 每个上游的连接状态、`lastError`、健康检查计数 |
| `GET /mcp/admin/health` | `UP` / `DEGRADED`，并列出不可达的 server 名 |

0.1.x 的 `overview` 把版本硬编码成 `"0.1.0"`、`health` 无条件回 `"UP"`。控制面报出去的每一个字段现在都有测试钉住：一个永远说"好话"的健康检查，比没有健康检查更糟。

`z-mcp-starter` 现在直接依赖 `z-mcp-admin`。在此之前**没有任何 pom 依赖过 z-mcp-admin**，`@ComponentScan("com.zifang.z.mcp.admin")` 扫的是一个不在 classpath 上的包 —— `expose-admin=true` 静默什么都不挂，开关等于没有。

## 构建与测试

```bash
mvn -B -ntp verify      # JDK 8（基线）或 JDK 17（下游实际运行版本）
```

235 个测试（core 175 + starter 53 + admin 7），0 failures、0 skipped；JDK 8 与 JDK 17 各一次 `clean verify` 实测通过。两份日志各自把量具钉在同一处：开头自报 JVM（`openjdk version "1.8.0_482"` / `"17.0.18" 2026-01-20 LTS`）、结尾自报 `exit=0` 与三个被测源码的**跑前/跑后 md5**（相同 ⇒ 量的就是现在这棵树），存在 `~/.cache/zmcp_logs/verify_jdk{8,17}.log`。并发那组不按"跑一次绿"结算：取消的三个类连跑 10 轮 ⇒ 10/10 全绿（`~/.cache/zmcp_logs/repeat/summary.txt`），`HttpJsonRpcExchangeTest` 连跑 15 轮 ⇒ 15/15 全绿且收尾 WARN 0 次（`~/.cache/zmcp_logs/teardown/summary.txt`）。CI 见 `.github/workflows/ci.yml`（JDK 8 + 17 矩阵，每份 job 带 `timeout-minutes: 20`）。

间歇缺陷不按"跑一次绿"结算。stdio 那一组先前在**同一份代码**上 8 次里挂 2 次（两个不同的竞态），修完按 `for i in 1..12` 复跑 ⇒ 12/12。单次绿只用来发现，不用来定罪也不用来赦免。

测试刻意不用 mock 打靶：

* `StdioJsonRpcExchangeTest` 起**真实子进程**，验证响应乱序与夹带通知时仍按 id 配对、stderr 灌 350 KB 不死锁（管道缓冲 64 KB）、子进程退出能立刻让挂起的请求失败；且退出之后再发请求要马上以"传输已关闭"收口 —— 不能把裸的 `Broken pipe` 交给调用方，也不能把请求写进没人读的缓冲区白等一个读超时（这条是实测跑出来的竞态，不是设想出来的）；
  另外钉住两条收口规则：等待槽必须在**写管道之前**登记（否则一个已经热起来的上游可以抢在登记前回答，那行响应会被当"无人认领"丢掉，调用方白等一整个读超时）；上游只是**关掉了 stdout**（进程还活着）时传输也算死 —— 这种情况 reaper 永远看不见，只有 reader 能收口，`a_server_that_blinds_its_stdout_is_dead_even_while_the_process_lives` 用"错误信息里不该出现 exit code"来证明收口的确实是 reader；
* `HttpJsonRpcExchangeTest` 起**真实 `com.sun.net.httpserver`**，验证请求体逐字节原样、`Accept` 头、404 的响应体可读（`getErrorStream`）、卡住的上游会在读超时处断掉；它的**收尾自己也是量具的一部分**：JDK 8 的 `ServerImpl.stop(delay)` 先做 `schan.close()`（`ServerImpl.java:227`）、之后才置 `finished`，而那次 close 在本机一次全量运行里实测卡死在 native `preClose0` 再也没回来 —— 主线程 RUNNABLE 停在内核、dispatcher（JDK 自己 new 的，非守护、连名字都没有）停在 `select()`，`SIGKILL` 只能把进程停在 `?E`，整个 surefire fork 永久挂住（两份 jstack 存在 `~/.cache/zmcp_logs/hang_stack_1.txt` / `_2.txt`）。现在 `stop(0)` 跑在守护线程上、主线程最多等 10 秒，超时只打一行可 grep 的 `WARN z-mcp-test-httpserver-stop` 而**不判红**（那是 JDK 与内核的毛病，不是被测代码的）：断言在那一刻已经跑完，没道理让收尾决定 CI 的寿命。这枚守卫按注入自证 —— 把 stop 动作换成永不返回 ⇒ 11 条 WARN、整类 113 秒跑完、0 failures（没有守卫的话这就是第二次无限挂起）。同一次改动顺手关掉 `@Before` 里每例新建、从不关闭的 `fixedThreadPool(2)`（每例漏两枚非守护线程），CI 侧再补 `timeout-minutes: 20` 兜底；
* `McpRemoteClientTest` 覆盖握手时序、`notifications/initialized` 必发且不带 `id`、404 只重新握手一次而 401 永不重连、游标分页到 `MAX_PAGES` 上限时拒绝而非静默截断；
* `ZMcpAutoConfigurationTest` 证明配置真的被读取并且真的产生行为（包括异步首连、用户自己的 bean 优先、容器关闭后健康检查线程消失）；
* `ZMcpHttpEndpointTest` + `ZMcpAdminEndpointTest` 起**真的 Tomcat**（`WebEnvironment.RANDOM_PORT`，测试 scope 里加 `spring-boot-starter-web`）。理由是 `base-path` 与 `expose-admin` 由 Spring 直接绑到 URL 上，`ApplicationContextRunner` 只看得到 bean 在不在、看不到请求进不进得来 —— 而下游接的恰恰是路径。这里验的是：`${base-path}/mcp` 上 `initialize` 能拿到 `Mcp-Session-Id` 而不带前缀的 `/mcp` 是 404；控制面跟着同一个前缀搬（只搬数据面等于把让给遗留模块的 `/mcp` 又自己占回去）；`notifications/initialized` 之前不给工具目录；`Accept` 少了 event-stream 回 406 且响应体仍是 JSON-RPC error；`DELETE` 之后同一个会话 id 立刻失效。
* `ZMcpListChangedNotificationTest` 钉的是**承诺兑现**那一层：capabilities 里广告了 `listChanged=true` 的三类目录，注册动作要真的变成流上对应那一类的通知 —— 而且只变成那一类。它挂在一个真的 `SseEmitter` 子类上收帧，顺带钉住通知不带 `id` 字段、每条通知各带一个递增的 event id、以及"会话没建流时广播是空操作"。
* `ZMcpAdminAuthTest` 证的是同一件事的另一半：**配了 token 之后控制面还开不开**。这里有个量具陷阱值得记下来 —— JDK 的 `HttpURLConnection` 把 `Origin` 列为受限请求头、客户端会把它**静默丢掉**，所以第一版用 `TestRestTemplate` 打跨站请求，读到的是"200，闸没生效"（假阴），换成裸 socket 手写请求行才量出真实的 403。同理，带请求体的 POST 撞 401 时 `HttpURLConnection` 会抛 `cannot retry due to server authentication, in streaming mode`（客户端的锅，不是服务端的），对称性探针因此改用无体的 `DELETE`。
* `McpSessionStreamBufferTest` 在会话对象这一层量续传的形状：挂一个只记账的 `SseEmitter` 子类，数它按什么顺序拿到哪些帧。它钉住开流的 priming 帧（带 id、`data` 为空、带 `retry`；`retry-ms=0` 时这一条必须不出现）、补发"恰好是漏掉的那些、且一条不重"、续上之后实时事件仍照收、缓冲停在配置值上（内存上界的唯一量具是 `bufferedCount()`）、`buffer-size=0` 时拒续传但流照开、客户端跑到服务端前面时不被赶走、以及一个**并发**性质：广播正好落在 `attach` 中间时要恰好送达一次 —— 那条不靠 sleep 猜时序，而是让记账 emitter 在第一次 `send`（即 priming，跑在临界区内）里放广播进来、并确认它确实被挡在会话锁外（`Thread.State.BLOCKED`），否则这条用例自己就会偶发红。心跳另加四条，全部**直接调 `store.tick()`**（不靠 sleep 猜周期）：一帧 `:keep-alive`、不带 `id` 也不带 `data`、心跳之后第一条真实通知的 id 仍紧接 priming；五拍心跳之后 `bufferedCount()` 仍是那两条、从 `Last-Event-ID: 0` 回来补出的还是 `{"n":1},{"n":2}`；写不出去的流（一个每次都抛 `IOException` 的 `Boom` emitter）被心跳摘掉而活着的继续收；`keep-alive-seconds=0` 时线程数一分不变、配 41 时恰好多一条且重复调用幂等、`close()` 之后那条线程在 5 秒内消失（数线程只认名字前缀 `z-mcp-sse-keepalive-`）。
* `ZMcpSseKeepAliveTest` 是心跳这一组里**唯一不自己敲 `tick()`** 的一层，因为上面那四条量的是"心跳这件事做对了没有"，量不到"有没有人真的在按周期敲它"：调度器建不起来、开流时没人调用 `startKeepAliveIfNeeded()`、配置没接上 —— 单元层全部照绿，而生产上"安静流被反代的空闲超时掐掉"这个症状会一模一样地回来（注入探针 K5 实测就是这个形状：core 14 条全绿、真 socket 3 条全红）。这一类把 `keep-alive-seconds` 配成 1，然后什么通知都不发，光等线上自己掉帧：安静流上要落下注释帧（`:` 开头、无 `id`、无 `data`），其后第一条真实通知的 id 仍是 `priming+1`；等两拍心跳再断流，用旧位置回来续传仍要补到断流期间那条通知（心跳占号的话这里直接是 `400`）；一条客户端已经跑掉的流不能把另一条的心跳一起带走 —— 这一条读**两拍**不是一拍，因为 `scheduleWithFixedDelay` 在任务抛出异常时是**永久**停摆的，只等一帧的话"整个线程被打死"和"正常"看起来一模一样。

* `ZMcpSseStreamTest` 补的是上一类的空洞：`GET /mcp` 自打写出来就没有被真的请求碰过。这里不装 emitter、不装 mock，真的在 Tomcat 上开一条流、握手后接着从 socket 里读帧，直到看见注册工具推出的那一帧为止（15 秒读不到就判红，不静默通过）。同一组里钉住 GET 侧的门槛：`Accept` 不含流 ⇒ **405**（json、`json;q=0.9`、`text/html` 三种写法各量一次，`*/*` 作正对照必须绿）、不带会话 ⇒ 400、会话不认识 ⇒ 404。续传也在真 socket 上量：开流第一帧要给出可续传的坐标（`id` + `retry`），据此断流、在离线期间注册一个**别类目**的资源（不能用工具 —— `notifications/tools/list_changed` 不带工具名，补发的那条和实时的那条在帧里长得一模一样，量不出"补"这个动作），重连时先等到那条 `resources/list_changed`、再等到 id 恰好在它之后的新 priming 帧；一条不带 `Last-Event-ID` 的新流即使背后有缓冲也不许补发任何东西；已经被淘汰掉的位置在线上就是 `400`，非数字的 `Last-Event-ID` 同样是 `400`。另有两条把 **logging** 也拉到真 socket 上量：工具抛异常要既在 POST 回执里 `isError`、又在流上落一条 `notifications/message`；以及"`setLevel(error)` 之后那条 warning 必须被压住"—— 这条负向断言不等超时，而是再注册一个工具当**记号**：同一会话的 emitter 是 FIFO，压不住的日志必然排在记号前面，所以"下一帧就是 list_changed"才是证据。量具上记四条：这条用例**不能**复用报状态码的 `code()` 助手（它 `disconnect` 之后流就关了）；每条用例都得显式设 `Accept`，因为 `HttpURLConnection` 会自己塞一串含 `*/*` 的默认值，不设就是在量客户端的默认值而不是服务端的闸；帧原文拼进断言消息前要压成一行，因为 surefire 在换行处截断消息 —— 不压的话红消息只剩 `id:1` 半截，而红消息后半段常常还藏着第二个缺陷；每条开过流的用例收尾要 `DELETE` 掉会话（`terminate(id)`），否则服务端那个异步请求会挂满 `sse.timeout-ms` 才收，而那条被客户端丢开的连接在这期间留在 `TestRestTemplate` 的池子里 —— 下一次握手复用它就撞 `Unexpected end of file`（实测撞上过一次，那一轮整类耗时从 4 秒涨到 25 秒）。
* `McpCancellationTest` 在 handler 这一层量取消的**形状**（真线程、真 latch，不用 mock）：中断要真的落到工具线程上并以 `-3280` 收口、取消比任务开跑更早到也杀得掉（`FutureTask` 取消之后 `run()` 一次都不执行 callable）、打空的四种写法（没有这个 id、连 `requestId` 都没有、`null`、非对象的 `requestId`）都不许动到那条真在飞的、数字 `5` 打不动字符串 `"5"`、别的会话拿着同型同值的 id 也打不动、迟到的取消不产生第二次作答也不留登记、`tool-timeout-seconds=0` 时工具在调用线程上跑（中断不了它，但服务端仍要承认"这条被取消过"而不是发一份成功结果）、会话都不存在时安静接受而不是 NPE、以及每轮之后在飞表必须是空的（`inFlightCount()` 是"登记/注销有没有漏"的唯一量具）。两个量具陷阱记在这里：`started` 那枚闩是"数尽即返回"的，同一条用例里第二次复用同一枚 gate 会让"猎物在飞"变成假断言 ⇒ 第二段换 gate、换工具名；而"没命中"这类负向断言靠的是同一条款子里"猎物照常答完 + 一次都没被打断"那两句，光一句 `assertFalse(interrupted)` 只是在赌时序。
* `ZMcpCancellationTest` 把取消拉到真 Tomcat 上，因为上面那九条是直接调 `handler.handle(...)` 的：它们量得到"命中之后做了什么"，量不到"取消到底找不找得到那条请求"。三件事只有真 HTTP 能证 —— `RequestContext` 必须每条请求一个新的（做成字段就会被迟到的请求顶掉 `requestKey`，取消打在完全无关的那条上）、寻址靠头里的 `Mcp-Session-Id`（那条用例故意让攻击者用**同型同值**的 id，这样挡住它的只可能是会话归属）、被取消的那条连接要拿回一条**完整**的 JSON-RPC 错误包络（`id` 对得上、`-3280` 在、不带 `result`）而不是一个断掉的 socket。第三条钉的是"工具体吞掉中断、坚持返回一个值"的形状：`FutureTask` 已经进了取消态，那份迟到的答案不得被当成成功发回去 —— 少那个 `catch (CancellationException)`，客户端看到的就是 `isError` 的"工具失败了"。`tool-timeout-seconds=30` 是量具的一部分：配成 `0` 工具就跑在 Tomcat 自己的线程上，第一条用例正是红在"中断没落地"。
* `StdioJsonRpcExchangeTest` 为这条路径补了一枚钉子（等子进程回答的中途被中断）：要在传输层就收口成 `IOException`、消息点名 `interrupted while waiting for stdio id=…`、把中断位还回去（`Thread.currentThread().interrupt()` 那句话以前没有任何针看着），并且这一刀之后同一条传输还能答下一个请求。取消把这条路从"只有超时会走"变成了常态。

新写的检查要先证明它会红才算数，所以这些检查是按注入 bug 验收的：把 `@RequestMapping("${z.mcp.base-path:}")` 改成空串 ⇒ 13 条里 12 条红（第 13 条是"控制面默认关"，与前缀无关，本就该绿）；把控制面钉回裸 `/mcp/admin` ⇒ 恰好 5 条红（4 条 socket + 1 条钉注解字符串的单测）；把工具清单的归属字段退回 `null` ⇒ 恰好 1 条红；把控制面上的那道闸拆掉 ⇒ 恰好 4 条红（匿名 401、错 token 401、跨站 Origin 403、数据面/控制面对称），而"好 token 打得开"这条本就该绿，`ZMcpAdminEndpointTest` 那 4 条无 token 用例也全绿 —— 后者同时证明**不配 token 时闸是空操作**，默认行为没被这次改动改掉，13 条数据面用例亦全绿。第一版里"会话不认识 ⇒ 404"这条在前缀被打坏时**仍然绿**，因为路由没命中也是 404 —— 补上响应体必须含 `unknown or expired session` 之后它才真的咬得住。

`listChanged` 那组做了两次**互补**的注入，因为可疑的地方有两层：把 registry 退回"一张监听表、谁变更都惊动所有人" ⇒ 恰好 4 条红（core 1 条 + starter 3 条，"没建流时广播是空操作"本就该绿）；只改接线、让三类都播 `tools/list_changed` ⇒ 恰好 2 条红而 core 那 17 条全绿。分发和接线各被一层单独咬住，任何一层退化都只会红它该红的那几条。这轮探针还顺手暴露了一个没人在乎的常数：`emit` 读的是 `seq.get()`，而声称"单调递增"的 `nextSeq()` 全文零调用点 —— 于是流上每条事件的 SSE id 都停在 0，按 id 去重的客户端会把第二条通知当成第一条的重放丢掉。补上 `nextSeq()` 后，探针复现出的正是 `event id 要递增, 实测 0 -> 0` 这一条红。广告侧另有一层独立的钉子（`advertised_list_changed_flags_are_the_ones_the_server_can_actually_honour`）：以前 `initialize` 的用例只看 `tools` 键在不在，正因如此"广告 true 却没人播报"才得以静悄悄成立。

`GET /mcp` 这组的门槛同样按注入验收。**拆掉 GET 的 `Accept` 闸** ⇒ 恰好 1 条红（`a_get_that_does_not_accept_a_stream_is_405_not_406` 读到 200），其余全绿。**把 TOOLS 那一类监听器的接线注释掉**（等于广告了 `tools.listChanged` 却不再播报）⇒ 预期 2 条红、实测 3 条：真 socket 那条按预期在 15 秒处判红（`15 秒内流上没有帧`），单元层两条红。多出来的第 3 条不是意外收获而是同一依赖：`each_notification_gets_its_own_event_id` 也是靠注册工具来产生两条事件的。这一轮同时暴露了量具自己的短板 —— `a_tool_change_is_announced_as_a_tool_change` 当时是以 `ArrayIndexOutOfBoundsException: -1` 死的，也就是说它只证明"没帧"这件事、却没说出口；`RecordingEmitter.last()` 因此改成先断言"流上一帧都没有"再取值。真 socket 那一条与 fake emitter 那两条**互不替代**：前者证明通知穿过了 Tomcat 的 SSE 写出路径，后者能在没有端口的情况下分辨通知属于哪一类目。

`logging` 这组分两批下刀，因为钉子分两批写。
**第一批**（core 层写完就量）：拆掉会话级门槛 + 关掉超时分支的播报 + 关掉直调分支的播报 ⇒ 恰好 3 条红，且各归各位：`客户端要 error 以上, warning 就不该出现在流上`（门槛）、`a_tool_timeout_is_announced_at_error_level` 与 `a_failure_without_the_timeout_pool_looks_identical` 都是 `expected:<1> but was:<0>`（流上一帧都没有）。"工具抛异常要有一条日志帧"这条始终绿 —— 它走的是 `ExecutionException` 分支，正对照因此成立：三条 catch 分支各有一枚自己的钉子，而不是"应该都会发通知"一句话。
**第二批**（真 socket 那两条写完再量）：只拆门槛 + 把 GET 的 405 改回 406 ⇒ 恰好 3 条红：core 的 `set_level_is_a_threshold_not_a_switch`、starter 的 `the_session_log_level_governs_what_the_stream_carries`、以及 `expected:<405> but was:<406>`。第二条值得记一笔：那条**负向**断言不是空跑的 —— 它红在"记号帧该到"这一步，因为被压住的 warning 真的抢在记号前面落了帧。这正是负向断言需要的猎物。

续传这一组按"预期红集 vs 实测红集"记账，因为六刀里两处预期与实测不符，而两处都是量具的问题。**A1 一条补发帧都不发** ⇒ 预期 core 3 + 真 socket 1，实测就是 3 + 1（正对照"没带 `Last-Event-ID` 就不补发"全程绿）。**A2 缓冲不做淘汰** ⇒ 预期 core 1 + 传输层 1 + 真 socket 1，实测 2 + 1（core 模块那 2 条正是这两层各一条）。**A3 priming 帧不带 id** ⇒ 我按"开流那两条 + 续传那两条"估了 4，实测 core 7 + 真 socket 3：凡是去读那个坐标的针全倒了，包括"客户端跑到服务端前面不该被赶走"和"同一会话各流 id 互不重复"。低估的原因清楚 —— 我只数了把 priming 当**主题**的用例，没数把它的 id 当分母用的用例。**A4 把 `attach` 的临界区拆成"锁内算快照、锁外补发 + 挂载"** ⇒ 恰好 1 条红，真 socket 10 条全绿。这是想要的形状：丢事件的窗口只在"广播正好落在补发与挂载之间"时才存在，它该压在单元层那一枚不靠 sleep 的针上，而不是让整套 E2E 跟着偶发红。**A6 不下发 `X-Accel-Buffering`** ⇒ 恰好 1 条红（传输层那根针），其余看不见这个头 —— 它是给反向代理的，不是给客户端的。
**A5 把非数字的 `Last-Event-ID` 悄悄退成 `0`** 第一版只有传输层那条红，真 socket 那句"非数字同样拒"**绿着**；查下来是那句站不住：它所在的会话最早两条事件已经出了缓冲，所以真按 0 去续也补不上，淘汰路径照样给 400 —— 那个 400 分不清是谁给的。补一条把猎物收窄到只剩解析那一条路的用例（`a_non_numeric_position_is_refused_on_a_session_that_could_replay`：缓冲里第一条就是 id=1，退成 0 会补发成功、回 200），复跑才是预期的传输层 1 + 真 socket 1。老规矩又验了一遍：**负向断言没有猎物时，长得跟真闸一模一样**。
有一处**注入不出**的，按未覆盖记账：`emit` 刻意把 socket 写在会话锁**外**（一个不再读的连接能把同会话其它流以及触发广播的那次调用拖住几十秒）。把这段写进锁内重跑三个类 ⇒ 47 条全绿（core 37 + 真 socket 10），也就是说"队头阻塞"这个性质现在没有任何针在守 —— 要量它得造一个卡住不读的对端，那是另一层工本，这一版选择不做并把它写在这里，而不是假装测过。

心跳这一组下七刀（基线 221 条全绿），预测红集与实测逐支对账：**K1 心跳去占事件序号** ⇒ 预期 3 实测 3，且名字全对（core `a_heartbeat_costs...`、socket `a_quiet_stream...`、socket `heartbeats_do_not_move...`）。第三支值得记：我预计它以"id 对不上"收场，实测它是 `expected:<200> but was:<400>` —— 心跳占了 2、3 两个号却不进缓冲，客户端拿 `Last-Event-ID: 1` 回来时缓冲里最早的一条已经是 4，于是直接被判定"淘汰过了"。这正是文档里那句"占号的心跳会让续传撞到一个没意义的 400"，只是它由探针而不是由我先想清楚。**K2 心跳入缓冲** ⇒ 预期 4 实测 4（core 2 + socket 2）。**K3 心跳写成 `event:ping` + `data:{}`** ⇒ 预期 3 实测 3，其中包括我预计会绿的 `heartbeats_do_not_move...` 确实绿着（不占号它就看不出差别），而预计会红的 `a_client_that_hung_up...` 也真红了。**K4 `keep-alive-seconds=0` 也建线程** ⇒ 恰好 1 条红，就是那根数线程的针（`expected:<3> but was:<4>`）。**K5 把传输层那句 `startKeepAliveIfNeeded()` 摘掉（接线断）** ⇒ 真 socket 3 条全红（每支各等满 12 秒），core 14 条**全绿** —— 这一支就是"单测自己调 `tick()`，生产从来没接线也照样绿"那道洞的实测形状，也是这一类存在的理由。**K6 写失败的流不摘掉** ⇒ 恰好 1 条红（core `a_stream_that_cannot_be_written_to...`），真 socket 那条按预期看不见。**K7 一次写失败把整个心跳任务打死**（`sendKeepAlive` 改抛 + Runnable 不再兜 `Throwable`）⇒ 预期 2 实测 3：core 那支按预期以 `IllegalStateException: java.io.IOException: client is gone` 死，`a_client_that_hung_up...` 按预期在**第二拍**等不到帧（第一拍照常到货，正是"只等一帧看不出来"的那点），多出来的第三支 `heartbeats_do_not_move...` 是**首帧就没读到东西**——它红的这一步与它声称的性质无关，是变异体留下的那条中毒连接被下一个用例复用了。这一支按"顺带炸"记账，不算 `heartbeats_do_not_move` 的功劳；它同时也说明这条套件里 `HttpURLConnection` 的全局 keep-alive 缓存是跨用例的，收尾只 `disconnect()` 不等于干净。

取消这一组下九刀，预测红集先落盘再跑，实测与预测逐支相同：**C1** `InFlight.cancel` 只置位、不 `future.cancel(true)`（"记了日志但没停任何东西"）⇒ 5 条红（core 3 + 真 socket 2）；**C2** 在飞表退回全局一张 ⇒ 恰好 2 条红，core 那条红在"陌生会话的在飞计数从 0 变成 1"，这是跨会话可见最直接的证据；**C3** `requestKey` 去掉类型前缀 ⇒ 2 条红（数字 `5` 与字符串 `"5"` 撞进同一格）；**C4** `finally` 里不再注销 ⇒ 5 条红，五条全红在 `inFlightCount()` 这个唯一量具上；**C5** 摘掉 `catch (CancellationException)` ⇒ 4 条红，真 socket 层的红消息给出的正是 `{"content":[…CancellationException…],"isError":true}` —— 取消被降级成"工具失败"的形状就是这个；**C6** 丢掉客户端给的 `reason` ⇒ 2 条红；**C7** `cancelled` 分支退回 0.1.x（收下但什么都不做）⇒ 5 条红，而"取消早于任务开跑"那一条**必须仍然绿** —— 它是手搓 `beginRequest`/`cancelRequest` API 的，接线断了量不到（K5 那一课的复述）；**C8** stdio 传输层摘掉 `Thread.currentThread().interrupt()` ⇒ 1 条红，红在"中断位要还回去"；**C9** 把中断说成"上游没回答"（catch 里 `line = null`）⇒ 1 条红，红在消息必须点名 `interrupted while waiting for stdio id=…`。C8 与 C9 各只红一条、且红在**同一条用例的不同断言**上，所以那一条其实是两枚钉子：吞掉中断位和换一种说法，各自只被其中一枚抓住。

有三处**注入不出**，按未覆盖记账而不是假装测过：`beginRequest` 必须排在 `submit` 之前 —— 两者之间只有纳秒级窗口，没有确定性的针能抓住（"取消早于任务开跑"那一支钉住的是窗口宽到任务还没被调度的那一半，靠的是 `FutureTask` 取消后 `run()` 一次都不执行 callable 这个语义）；超时分支里 `future.cancel(true)` 被摘掉后客户端侧形状完全不变（照样 `-3280`），只是线程池里多一个还在跑的人，而当前没有任何针在看"池子里还有没有人在跑"；"取消打空不许回错误"是**过定**的 —— `handle()` 对 notification 一律吞掉异常回 `202`，所以把那一支改成抛 `invalidParams` 也不会红，外层那道 catch 就是它的替身。

`id` 显式为 `null` 那一处补了两刀：**N1** 摘掉新加的 null-id 特判、并把 `isNotification` 退回"缺 id 或 id 为 null" ⇒ 恰好 1 条红，正是新用例的第一句（`显式 id:null 不是通知, 必须给回执`，退回来就是 `202` 空体）；**N2** 是钝刀：`isNotification = false` 恒成立 ⇒ 实测 37 条具名红（core 15 + starter 22），远超"至少 2 条"的预期 —— 但预期里那两条都在（`notification_is_accepted_with_202_and_no_body` 红在 202→200，新用例的**正对照半句**红在 `expected:<202> but was:<200>`），多出来的 35 条是同一个依赖：全套件的握手都要发一条 `notifications/initialized`。那半句正对照就是为 N2 这种改法留的 —— 没有它，"把带 method 的一律判成非法请求"也能让第一条断言变绿。

## 0.1.x → 0.2.0 迁移

破坏性变更集中在"以前没人管、现在按协议办"：

1. 工具执行失败从 JSON-RPC error 改为 `result.isError = true`；
2. 非 `initialize` 请求必须带 `Mcp-Session-Id`（除非 `session.enabled=false`）；
3. `Accept` 必须同时含 json 与 event-stream（这是 POST 那一路的 MUST）；`GET /mcp` 上协议只给了"流或 405"两个出口，所以只列 json 的 GET 会拿到 `405` 而不是 200 —— 它原来拿到的那个 200 本来也是条读不懂的流；
4. `strict-tool-names` 默认 `true`，不合规的工具名注册即失败；
5. `http+sse` 传输不再接受（该传输已被协议废弃），改指 `http`；
6. `health-check-interval-seconds`、`base-path`、`expose-admin`、`servers[]` 从无人读取变为真实生效 —— 如果 0.1.x 里为了摆设写过这些键，升级后它们会开始有作用。`base-path` 现在同时搬数据面与控制面（配了它，`/mcp/admin/*` 会变成 `${base-path}/mcp/admin/*`）；仓库内目前没有一处配置写了 `z.mcp.base-path`（`base-path`/`basePath`/`setBasePath` 三种写法全 grep 过，命中项都是 `z.llm.base-path`、文件路径局部变量之类无关同名标识符），所以这一条对在跑的下游是零冲击，但别把它当不存在；
7. 控制面的 `version` / `health` 不再是常量；
8. `z-mcp-admin` 成为 `z-mcp-starter` 的依赖 —— 引入 starter 就会带上控制面的类，但端点仍然只有 `z.mcp.expose-admin=true` 才挂载；
9. 如果 0.1.x 里**同时**配了 `security.bearer-tokens` 和 `expose-admin=true`，升级后 `/mcp/admin/*` 会开始要求同一个 token（以及过 Origin/Host 校验）。这是修复而不是回归 —— 但下游那个"抓工具清单的脚本"会突然 401，先给它带上 `Authorization` 再升。没配 token 的部署行为不变（闸是空操作，有测试钉住）。
10. `GET /mcp` 的 `Last-Event-ID` 从"一律 400"变成真补发，开流第一帧多了一个 `id` 与 `retry` 字段，流上响应多一个 `X-Accel-Buffering: no`。三处都只可能让客户端**多做**事：只按 `data:` 取通知的客户端会多看到一条空 data 的帧（忽略它即可，协议正是这么定义 priming 的），而原本自带 SSE 重连逻辑的客户端会开始按服务端给的 `retry` 间隔重来。若 0.1.x 里当摆设写过 `z.mcp.sse.*` 的其它键，现在 `buffer-size` / `retry-ms` 会开始生效（`0` 分别表示"不缓冲"与"不发 retry 字段"）。
11. 新增 `sse.keep-alive-seconds`（默认 30 秒）：安静的流上每 30 秒多一个 `:keep-alive` 注释帧。按规范实现 SSE 的客户端会直接忽略注释行，因此这条对它们是不可见的；只有"自己 `split` 行、把任何一行都当成一条消息"的手搓读取器会看到多余的空行 —— 如果你的客户端是这一类，先配 `0` 关掉再升。配 `0` 之后连心跳线程都不建。
12. `notifications/cancelled` 从"收下但什么都不做"变成真取消：那条被取消的 `tools/call` 现在回 `-3280 RequestCancelled`（原来是 `result.isError=true` 或一份成功结果），并且它的工具体会收到线程中断。协议为这个码留了空间却没指定它，所以**别把它当协议保证**：只按"我发了取消、我收到了一个错误而不是结果"来写客户端逻辑。如果 0.1.x 里你的脚本会发取消（哪怕发了也没用），升级后它开始真的停东西。

13. 带 `"id": null` 的请求不再是"静默 202"：以前它被当成没带 id 的通知吞掉，客户端因此白等一个读超时；现在按 JSON-RPC 的规定回 `-32600`（`id` 字段原样为 `null`）。真正没带 `id` 的消息行为不变，仍是 `202` 空体。

## 已知边界

* `completion/complete` 未实现；elicitation 等客户端能力不在 capabilities 里出现。
* `notifications/progress` 不推送：请求里的 `_meta.progressToken` 不被读取（全文没有任何从请求侧读 `_meta` 的代码路径），工具是同步执行完才回一次结果。长工具请自己在返回体里说话，别等进度。
* 续传缓冲在**进程内存**里、按会话各留最近 `sse.buffer-size` 条：多实例部署下重连打到另一个实例（那里根本没有这段历史）⇒ `400`，进程重启同理。客户端必须把 400 当"退回不续传"处理，别当服务端故障。要跨实例续传得把缓冲外置，这一版没有。
* 会话在进程内存里，多实例部署需要粘性会话或后续的外置存储。
* 心跳只有一个调度线程，而它做的是阻塞式 socket 写：**一个不再读、且已经把 TCP 窗口填满的客户端能把这一轮后面所有会话的心跳一起拖慢**。写失败的流会被下一拍摘掉（有测试钉住），但"写不报错、只是卡住"这一类没有 —— 要量它得真造一个不读的对端并灌满其接收窗口，那是另一层工本，这一版选择不做并把它写在这里。默认的 30 秒对 60/75/100 秒这几道常见空闲闸够用；把你的反代超时配得比它还长才是根治，心跳只是兜底。
* 取消是**协作式**的，而且只到这里：服务端做的是 `Future.cancel(true)`，也就是发一次线程中断。工具体如果不理中断（自己吞掉、或正卡在一个不可中断的调用里），它照样会跑完 —— 本层保证的是**那份结果不会再被当成成功发回去**（有测试钉住这一条），不是"工具一定停在原地"。子进程、已发出的远端任务、连接池里的资源不会因此回收，要释放得工具体自己响应中断。
* 在飞登记只挂在**工具执行**那一段（`tools/call`）。取消一条正在分页的 `tools/list`、或一条已经答完的请求，都是打空 —— 打空按协议静默，不报错。
* 取消**不向上传播**：如果这次调用的是聚合自上游 MCP server 的工具，本层不会向上游转投一条 `notifications/cancelled`，远端那次调用仍会被它跑完。中断在这里能做到的只有"服务端不再等那份结果"：stdio 那一路的等待是可中断的（立刻以 `IOException` 收口，之后那行响应被当作无人认领丢掉），HTTP 那一路是阻塞的 socket 读、`interrupt` 打不断它，只能等它自己回来。要做真正的端到端取消，得给 `McpRemoteClient`/传输层加一条"转发 cancelled + 让读可中断/可 abort"的路径，这一版没有。
* `session.enabled=false`（无状态模式）下没有可寻址的会话，取消通知因此只能是空操作。
* `security.bearer-tokens` 为空即完全放开鉴权 —— 只应出现在本机/内网可信环境。
* 鉴权是**静态 Bearer 白名单**（`security.bearer-tokens`）。协议授权章节里的 OAuth 2.1 那一套（`.well-known` 元数据、动态注册、PKCE、令牌轮换）没有实现 —— 要对外暴露 `/mcp`，请放在做授权的网关后面，别把白名单当账号系统。
* 闸只有一把：数据面与控制面共用 `TransportSecurityGuard.checkRequest(HttpServletRequest)`，因此配了 `bearer-tokens` 之后 `/mcp/admin/*` 同样 401（带 `WWW-Authenticate`），跨站 Origin 同样 403。0.2.0 之前它只挡 `/mcp`，控制面把上游 endpoint、`lastError`、全部工具 Schema 这些拓扑信息白送给任何能连上端口的人 —— 而配置者以为配了 token 就锁上了。
* 控制面只有 JSON，没有页面。`z-mcp-admin` 里那份 `templates/z-mcp-admin/index.html` 是死资源：没有任何代码引用它，而 Spring Boot 只会自动服务 `static/`（`templates/` 需要模板引擎），所以它连"打开就能看"都做不到。要控制台界面就自己拿 `/mcp/admin/*` 渲染。
* 下游 z-opc 目前仍 pin 已发布的 `0.1.2` 构件；本仓的改动要经发布 + 下游构建验证才会影响它。

## 许可

MIT，见 `LICENSE`。
