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
| `z-mcp-server` | **进程**（前四个是库）：一个 fat jar 装 `hub` / `text` / `codec` 三种角色，`java -jar` 起来就是一个能被任意 MCP 客户端挂上的 server。见「独立进程宿主」 |

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

自检（不经过 JSON-RPC，给探针用）：`curl -H "Authorization: Bearer $TOKEN" localhost:8080/mcp/info` —— 这台探针自己也在闸后面，没配 `bearer-tokens` 时 `Authorization` 那一段可以省

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

* **支持集与参考实现同点，不是落后**：官方 java-sdk 的 `main` 今天（09-26 经 `api.github.com` 的 contents 接口取回，686 字节）在 `mcp-core/src/main/java/io/modelcontextprotocol/spec/ProtocolVersions.java` 里只声明 `MCP_2024_11_05` / `MCP_2025_03_26` / `MCP_2025_06_18` / `MCP_2025_11_25` 四个常量，没有 2026 那一档。（取回过程里两件事值得记：这个文件在旧的 `mcp/…` 路径下已经是 404 —— SDK 把模块改名成了 `mcp-core/`，那个 404 是真的"路径没了"不是网络故障；而今天 `raw.githubusercontent.com` 本身确实在间歇超时，同一份内容改走 `api.github.com` 的 contents 接口一次就通了。）
* **2026-07-28 有意不跟**。规范仓库今天同时挂着 `2026-07-28` 与 `2026-07-28-RC` 两个 tag，前者是正式版。它把 `initialize`/`notifications/initialized` 握手、`Mcp-Session-Id` 与协议会话、SSE 续传（`Last-Event-ID`）、`ping`、`logging/setLevel`、`resources/subscribe` 一起取消，改成每个请求在 `_meta` 里带 `io.modelcontextprotocol/protocolVersion` 与 `clientCapabilities`，新增 `server/discover`、`subscriptions/listen`、MRTR（`resultType: "input_required"`）、必需的 `Mcp-Method`/`Mcp-Name` 头和 `ttlMs`/`cacheScope`。本仓的上游聚合与安全边界都以会话为主键，跟这一版等于把主键拆掉，而且真实客户端目前还没跟进。
* 但那一版的**错误码分配政策**已经影响本仓怎么挑码（`docs/specification/2026-07-28/changelog.mdx` 第 12 条）：`-32000…-32019` 归实现自定义（现有 SDK 用法豁免），`-32020…-32099` 留给规范，并把草案自己引入的三个码重编号（`HeaderMismatch -32001→-32020`、`MissingRequiredClientCapability -32003→-32021`、`UnsupportedProtocolVersion -32004→-32022`）。本仓的 `UNSUPPORTED_PROTOCOL_VERSION` 取 `-32022`：只支持到 2025-11-25 的前提下，这个场合规范**没有**规定 JSON-RPC 码（`2025-11-25/basic/transports.mdx` 通篇没有 `-32xxx`，只说 MUST 回 `400 Bad Request`），于是挑一个规范日后认领的值，将来真去跟这一版时客户端读到的码不必改。反过来 `RESOURCE_NOT_FOUND` 仍留 `-32002` —— 那是 `2025-11-25/server/resources.mdx` 白纸黑字的值，2026 那一版才把它并到 `-32602`，而本仓不声称支持 2026。

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
* `POST /mcp` 的 `Content-Type` 只认 `application/json`，否则 `415`（协议 2025-06-18 "Sending Messages" 第 3 条：体 MUST 是 `application/json`，不支持的类型 MUST 回 415）。判据 `contentTypeOk` 剥掉 `;charset=...` 这类参数、大小写不敏感，因此 `application/json;charset=UTF-8` 照收；**不带这个头也拒**——缺省意味着发送方从没声称过自己送的是什么，服务端没理由替他猜。响应体与其余传输层错误同形（`-32600` + 那句 `Content-Type must be application/json`），因为只回状态码的客户端会原样重试同一份头。0.2.0 之前这一维是完全空的：`Content-Type: text/plain` 的 JSON 体被照单解析并执行，实测在 250 部署盘上 `interop_malformed_run1.log` 的 `non_json_content_type_is_rejected_explicitly` 读出 `200 + result`，而参照实现（官方 python SDK `streamable_http.py` 的 `_check_content_type`）在同一请求上回 415。**这道闸第一次红是在红自己的测试**：加完之后 starter 有 16 条挂在握手那一步（`ZMcpSseStreamTest` 10 / `ZMcpCancellationTest` 3 / `ZMcpSseKeepAliveTest` 3），因为它们用 `HttpEntity<String>` 送体而 RestTemplate 给 String 补的默认头就是 `text/plain;charset=UTF-8` —— 也就是说本仓自己的客户端一直是非合规的，只是服务端从不检查所以看不见。修法是一律补显式 `application/json`（同仓 `ZMcpHttpEndpointTest` 早就是这么写的），没有放宽判定。层级各钉一层：core 的 `JsonRpcControllerTransportTest` 用 `MockHttpServletRequest` 钉形状（四种坏头、缺头、带参数仍收、以及"闸在会话闸之前"），starter 的 `non_json_content_type_is_refused_by_the_real_container` 过真 Tomcat 钉同一件事——后者是对照四格：`initialize` 在 `text/plain` 下 415、在 `application/json` 下 200，缺会话的 `tools/list` 在 `application/json` 下 400、在 `text/plain` 下 415，只测 415 的话一个"永远 415"的实现也能过。一处与参照实现**故意不同**：它按 `part == "application/json"` 逐字比，于是大写形式会被它自己拒掉，而 RFC 2045 说媒体类型不区分大小写。注入验收（脚本 `~/.cache/zmcp_server_gauges/ct27_inject.py`，逐支按字节还原并复验 sha256，分母钉在 255）：把判定条件改恒假 ⇒ 4 条具名红（core 三条 + starter 真容器那条）；换成"逐字等于 `application/json`"（不剥参数）⇒ 恰好 1 条红，红的是带 `charset` 的正对照。两支红集不重叠 ⇒ "拒太少"与"拒太多"是两个独立的方向，各有一层守着。没红过的那条 `content_type_ok_strips_parameters_and_is_case_insensitive` 守的是判据本身的真值表，它结构上不可能被"调用点改动"类变异打到，所以不算漏。改完在 250 上按字节对账重发（jar `6c5f7be9117fefc7…`，本机与远端 sha256 同值；并且先读**嵌在 jar 里的** `z-mcp-core` 那个 class 的常量池确认 `Content-Type must be application/json` 真的进了构件 —— 只看源码树会被"还原源码 ≠ 还原构件"那一课再骗一次），三把尺同轮复测：`verify_250_run6.log` `PASS=40 FAIL=0`、`interop_malformed_run3.log` `PASS=10 FAIL=0`（原来那格 FAIL 现在读 `415` + 同形 JSON-RPC 体，判据同时从"400 或 415 都算拒"收窄成"必须 415 且点名 `application/json`"，因为 400 是会话闸顺手挡的、量不到这一维）、`interop_sdk_run8.log` 仍是 `PASS=33 FAIL=0 NOT_COVERED=2` ⇒ 官方客户端自己发的头一直是 `application/json`，这道闸没有把它挡在门外。
* 非 `initialize` 请求必须带 `Mcp-Session-Id`；服务端不再认识该 id ⇒ `404`，客户端据此重新 `initialize`；
* `GET /mcp` 带 `Last-Event-ID` 时按上一节通知里说的续传处理：非数字或负数 ⇒ `400`（事件 id 是我们自己发的十进制数，别的一律按"这客户端不可信"办），位置已被缓冲淘汰 ⇒ `400`，其余 ⇒ 补发 + 续流；流上响应一律带 `X-Accel-Buffering: no`，因为 nginx 默认真的会整条缓冲 SSE —— 不关的话推送在配置层面"生效"、在客户端一侧从来不到货；
* `initialize` 带着别人的会话 id 来 ⇒ `400`；
* `MCP-Protocol-Version` 与会话协商结果不一致 ⇒ `400`；**不带**这个头不是错误（协议规定缺省时按 `2025-03-26` 假定，本项目按会话已协商的版本走）；
* 头里那个版本**本身不支持**也在 `400` 之列（JSON-RPC 体带 `-32022 UnsupportedProtocolVersion`，message 里列出支持集，客户端不用猜），判据是一个只认"头存在且不在支持集"的 `isUnsupportedVersion`：`POST /mcp`、`GET /mcp`（流）、`DELETE /mcp` 三个入口各调一次，位置都在闸之后、碰会话之前。0.2.0 之前这一维只在"有会话可比"时成立 —— `session.enabled=false` 下没有任何东西和头对照，一个声明了 `2026-07-28` 的请求会被当成缺省按 `2025-03-26` 服务掉，而流和 `DELETE` 两路干脆不读这个头。校验放在请求入口而不是会话比较里，就是因为无状态模式没有会话可比。规范在这一句上**只规定状态码、对响应体保持沉默**（它明确允许带 JSON-RPC error 体的只有 Origin 那一路的 403、和 notification 被拒那一路的 400），所以：POST 那一路带体（`-32022` + 支持集，`id` 为 `null` —— 与本仓其它传输层 400 同形），而流与 `DELETE` 两路回**裸 400**，这是本服务的取舍不是条文：GET 那一路客户端的 `Accept` 要的是 `text/event-stream`，回一份 `application/json` 等于自己制造一次内容协商冲突；`DELETE` 成功时本来就是 200 空体，没有哪个客户端会去读它的错误体。这条头**不管辖 `initialize`**：握手那趟以 `params.protocolVersion` 为准，头里带什么都不改变结果（有测试钉住，防止把"校验版本头"误实现成"版本头一票否决"）。注入验收（脚本 `~/.cache/zmcp_guards/mutate_round2.py`，逐支按字节还原并复验 md5）：把判据整个摘掉 ⇒ 3 条红；改成"带版本头就拒" ⇒ 9 条红（正对照全灭，因为 `withSession()` 那个助手会带一个受支持的头）；把校验挪去也管握手 ⇒ 只有那一条握手用例红；GET／`DELETE` 各自不校验 ⇒ 各红自己那一条。上一轮两处"预期红集"算错都是我的错不是守卫的错：M2 少算了共用助手的波及面，M3 多算了一条根本不带版本头的用例（它的 400 由"缺会话"那一支给出，与版本无关）。同一趟里还删掉了一条**永远走不到的**分支：`handler.handle(...)` 之后再判 `McpSchema.isSupported(ctx.negotiatedVersion)` 并回 `-32022` —— 协商函数只会从 `SUPPORTED_VERSIONS` 里取值，所以这个判据恒假，它给读者的印象（"发出去之前还会再验一次"）没有任何实现兑现。它真正想守的那条不变量（降级目标自身必须在支持集里）现在由 `McpProtocolHandlerTest.the_downgrade_target_is_itself_supported` 直接钉住：`isSupported(negotiate(任何东西))` 恒真、`negotiate` 在垃圾/缺省/已知输入下都落在支持集内，且 `2026-07-28` 确实不在。
* 请求体上限 4 MiB ⇒ `413`；批量数组体按 2025-06-18 的删除明确拒绝，而不是静默只处理第一个；
* 已废弃的 HTTP+SSE 传输（2024-11-05 的双端点形状）不再接受；
* 限流按会话（无会话时按来源地址）计数，超限 `429` + `Retry-After: 1`；
* `Origin` / `Host` 校验默认收敛为同源，防 DNS rebinding；`Authorization: Bearer` 白名单可选。

## 配置参考 `z.mcp.*`

0.1.x 里 `basePath` / `exposeAdmin` / `healthCheckIntervalSeconds` / `servers` **没有任何人读取**，是死配置。0.2.0 起每一项都对应一个真实行为，缺省值按"协议合规优先"选取。

逐项核过：44 个 `private` 字段里 5 个是嵌套配置组（`session` / `sse` / `security` / `rate-limit` / `servers`），剩下 **39 个可配字段有 36 个存在 main 代码里的 getter 读取点**，另外 **3 个由 Spring 自己按字符串键绑定** —— `enabled` 走 `ZMcpAutoConfiguration:32` 的 `@ConditionalOnProperty(name = "z.mcp.enabled")`，`expose-admin` 走同文件 `:124`，`base-path` 走 `JsonRpcController:50` 与 `AdminController:30` 上的 `@RequestMapping("${z.mcp.base-path:}")`。所以**全文找不到 `getBasePath()` 的调用点不等于这条配置是死的**，别照着"有没有人调 getter"去复查这一项。这三个字段都有对得上号的用例兜底：`base-path` / `expose-admin` 走真 servlet 容器（`@SpringBootTest(RANDOM_PORT)` 的 `ZMcpHttpEndpointTest` / `ZMcpAdminEndpointTest`，不起 mock、直接按 URL 打进去），`enabled` 走 `ZMcpAutoConfigurationTest.nothing_is_wired_unless_the_module_is_asked_for()`（`ApplicationContextRunner`，断言键缺失时 `McpRegistry` / `McpProtocolHandler` 的 bean 数为 0）。

这组数是量的、可复跑，但**必须按接收者归属去量**（脚本 `~/.cache/zmcp_guards/cfg_cov2.py`，09-26 加了 `z-mcp-server` 之后复跑：32 个 main 文件 / 44 字段 / 39 可配 / 36 有归属读取点）。第一版 `~/.cache/zmcp_logs/cfg_cov.py` 只按 getter 名字在 main 源码里找 `.getXxx()`/`.isXxx()`，同一棵树今天复跑给出 **42 有人读 / 2 没人读** —— 那是**虚高**：`enabled` 这个字段名在 `McpProperties` / `Session` / `Sse` / `RateLimit` / `ServerConfig` 里各出现一次，而真实调用点一共 7 处（`TransportSecurityGuard:120` 是 RateLimit、`JsonRpcController:153/206/252/319` 是 Session、`:249` 是 Sse、`ExternalServerManager:123` 是 ServerConfig），一处 `rl.isEnabled()` 会把五个同名字段全记成"有人读"。按"链上访问器名 + 同文件里的变量声明类型"重新归属后，这 7 处各归各家，`McpProperties.isEnabled()` 才是真的零命中 —— 它不是漏接线，是根本不该由 getter 读（开关发生在 bean 装配之前，装配好了才谈得上读属性）。本批新加的 `builtin-tools-enabled` 不在这个歧义里（访问器叫 `isBuiltinToolsEnabled()`，只有一个读取点 `ZMcpAutoConfiguration`），它之所以存在，是因为叶子角色一旦照抄默认值就会把四件中心件推给 hub。改成按接收者归属的口径之后，39 = 36 + 3，两边都对得上；`sse.keep-alive-seconds` 那条的读取点是 `McpSessionStore.startKeepAliveIfNeeded()`。

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
| `builtin-tools-enabled` | `true` | 是否登记那四件中心件（`echo` / `get_time` / `generate_uuid` / `system_info`）与三条自省资源。被别的 hub 聚合的叶子服务应设 `false`，否则撞名成 `text__echo` 那类前缀件（见「独立进程宿主」） |
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
| `security.bearer-tokens` | 空 | **空 = 关闭鉴权**，只适用于本机/内网可信网络；非空时数据面与控制面同时生效，401 的挑战形状分三种（见「有意边界」） |
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
* **上游目录逐字未变时不动注册表**（签名相同且自己那批条目还在 ⇒ 整轮跳过）。这条不是省 CPU，是省一次谎报：`register` / `unregister` 各会 fire 一次 `Change.TOOLS`，而每次 fire 都会变成每条在线会话的一帧 `notifications/tools/list_changed`；协议又要求客户端收到它就重取 `tools/list` —— 于是"什么都没变的一轮健康检查"会给 N 条会话各造成 2×工具数 次无意义的重取。250 上实测（两台叶子 × 两条工具、`health-check-interval-seconds: 30`）修前一条静默流 **80 秒收到 24 帧**（`~/.cache/zmcp_server_gauges/watch_churn.sh` → `churn_stream.txt`），修后同一条流 0 帧。摘除（`detach`）会把签名一起作废，否则"上游断了又原样回来"会被这个跳过逻辑吃掉。签名按名字排序取"会进目录的字段"，**来源链也算其中一个字段** —— 链变了哪怕内容没变，收与不收的判定就变了，不该被这一轮跳过。
* 但上一条那个签名**起初取错了位置**：它取在环检测过滤**之前**的整份上游目录上。hub↔hub 环上，对岸每聚合一轮都会把"抄回来的复制品"换一批措辞，于是我这台**收进来的那部分逐字未变**、签名却变了 ⇒ 走"先 `dropTools()` 再逐条重登同一批"这条路。顺序是 `tools.remove(name)` 之后才 `fireChanged` ⇒ **第一次 fire 时那批条目已经不在目录里**：单元尺在那一次 fire 里读到的 `toolNames()` 是 `[]`（该 fixture 只收了一条），真进程尺 `HubAggregationTest.two_hubs_that_aggregate_each_other_reach_a_fixed_point` 在本机 jdk17 全量 `clean verify` 里量到 `12 条只剩 10 条`（缺 `origin__registry_state`、`origin__system_info` —— 掉的正是聚合来的那两条，内置六条不受影响）。刺人的地方在于这次 fire 同时还通知所有会话"目录变了，来重取 `tools/list`" —— **服务端把客户端领进自己刚造出来的空洞**。现在改成过滤在前、签名取在**收进来的那部分**上，被拒的那部分怎么抖都不再动目录（`churn_only_in_the_rejected_part_does_not_re_publish_the_accepted_part` 钉两条：fire 次数不许变、任何一次 fire 里都不许读到空洞；并留一支阳性对照"收进来的真变了必须落地"，免得判据修成"永远不重发布"）。**残留没修**：收进来的部分**真的**变了时仍是先摘后登，那一次仍有短洞 —— 要无洞得给注册表加"整批替换"的原语（直接先登后摘会撞上自己上一轮的条目，`Builder.register` 对此抛 `duplicate tool registration`），这条只记账没做。
* 状态里的 `connected` 由传输本身回答（`JsonRpcExchange.isAlive()`），不是握手时置位、之后永不复位的 flag。stdio 的子进程一退（不管是进程没了还是 stdout 到了 EOF），`/mcp/admin` 立刻变 `false`，不需要先失败一次调用才看得见。
* `enabled: false` 的 server 连拨号都不会发生。
* 一个死 server 不能带走另一个 server，也不能带走内置工具。
* stdio server 没有 URL，控制面就把它的**命令行**当入口显示（`/usr/bin/java -cp …`），而不是回一个 `null` 让运维去猜是哪个子进程。

## 独立进程宿主 `z-mcp-server`

前四个模块是库 —— 要有人 `java -jar` 起来它们才说话。`z-mcp-server` 就是那个"有人"：它不引入任何新能力，只是把 starter 自动装配的那套件按角色摆成一个进程，好让"MCP 服务中心"这件事在没有人改自己应用之前先成立。

**一个 jar 装三种角色**，`Z_MCP_ROLE` 选哪一份 `application-{role}.yml`（默认 `hub`）：

| 角色 | 端口 | 目录内容 |
| --- | --- | --- |
| `hub` | `18095` | 四条内置工具 + `find_tools`（按关键词检索目录）+ `registry_state`（上游真实连接状态），外加 `z.mcp.servers[]` 聚合来的上游工具 |
| `text` | `18096` | `text_stats`（字符/词/行/UTF-8 字节）、`text_transform`（upper/lower/snake/kebab/title/reverse） |
| `codec` | `18097` | `base64_codec`（标准与 URL-safe 两套字母表）、`hash_digest`（md5/sha-1/sha-256/sha-512，十六进制） |

两个叶子角色都配了 `builtin-tools-enabled: false`。这不是洁癖：`echo`/`get_time`/`system_info` 在 hub 上本来就有，叶子带着它们被聚合就会撞名成 `text__echo` —— 一张目录里混着真工具和前缀化的调试残留，"哪条才是我要的"就变成了使用者的负担。同理，那三条自省资源（`z-mcp://tools` 等）描述的是注册中心自己，叶子没有中心可描述，于是 builtin 关掉时它们一起消失（`RolesTest` 按 `resources/list` 的空数组钉住这一点）。

环境变量（全部有默认值，`application.yml` 里**刻意不含任何口令**）：

| 变量 | 落到哪 | 说明 |
| --- | --- | --- |
| `Z_MCP_ROLE` | `spring.profiles.active` | `hub` / `text` / `codec`，缺省 `hub` |
| `Z_MCP_PORT` | `server.port` | 按角色各有一个缺省值，同机部署三角色不用互相让端口 |
| `Z_MCP_BEARER_TOKENS` | `z.mcp.security.bearer-tokens` | 逗号分隔可给多条（轮换期新旧并存）；不给就是空列表 = **关闭鉴权** |
| `Z_MCP_TEXT_ENDPOINT` / `Z_MCP_CODEC_ENDPOINT` | hub 的 `servers[0..1].endpoint` | 上游不在默认端口上时改这里，不用改 yml |
| `Z_MCP_UPSTREAM_TOKEN` | hub 发给上游的 `Authorization` | 中心侧的出站凭证，与上面那把入站的**是两把**（叶子收 token、hub 发 token） |

这里有两件是**量过**而不是推出来的（`BearerGateTest` 走真 HTTP + 真 profile，加上面的 fat-jar 冒烟）：

* `bearer-tokens: ${Z_MCP_BEARER_TOKENS:}` 在变量未设时绑成**空列表**，不是 `[""]`。这一条值得钉，是因为两种形状在配置面上只差一个冒号，而 `[""]` 会让闸永久拒绝一切请求（空白名单=放开，含空串的白名单=门锁死）；实测未设变量时匿名握手 200、`/mcp/info` 200、`/mcp/admin/overview` 200。
* 逗号分隔确实绑成多元素 `List<String>`（`t1,t2` → 两个 token，两个都打得开门），所以轮换不用停机：**先加新令牌、再摘旧令牌**，中间那段时间两把都有效。

注册一台新的上游（不改 yml，命令行按位覆盖即可 —— Spring 的 per-index 覆盖是**与 yml 合并**而不是替换整个元素，所以 `servers[2]` 追加一台、`servers[1].enabled=false` 摘掉一台都能表达）：

```bash
java -jar z-mcp-server-0.2.0.jar \
  --z.mcp.servers[2].name=acme --z.mcp.servers[2].transport=http \
  --z.mcp.servers[2].endpoint=http://127.0.0.1:9100/mcp \
  --z.mcp.servers[2].headers.Authorization="Bearer ${ACME_TOKEN}"
```

构建与起进程（Java 8 基线；`repackage` 是模块自己的 pom 里那段 `spring-boot-maven-plugin`，少了它 `package` 出来的是一枚没有 `Main-Class` 的普通 jar，而**单测跑在 repackage 之前，没有任何测试会因为它缺失而红**）：

```bash
mvn -B -ntp -DskipTests -pl z-mcp-server -am package
Z_MCP_ROLE=text Z_MCP_PORT=18096 Z_MCP_BEARER_TOKENS="a-token,b-token" \
  java -jar z-mcp-server/target/z-mcp-server-0.2.0.jar
```

冒烟脚本 `~/.cache/zmcp_server_gauges/fatjar_smoke.sh`（日志同目录 `jar_hub.log` / `jar_text.log`）用一枚假版本 `9.9.9-probe` 起真进程量四件只有 `java -jar` 能量的事，09-26 15:58 实测全部成立：

1. 不给任何变量 ⇒ profile 是 `hub`，`Z_MCP_PORT` 说话算数；
2. `/mcp/info` 报的版本是 **`9.9.9-probe`** —— 值取自 jar 的 `Implementation-Version`，不是 `McpProperties` 里手抄的 `0.2.0`。之所以要用一个假版本号来量，是因为真版本号下"MANIFEST 那条路走通了"和"读到了兜底常量"给出**同一个字符串**，看多少次都判不出来；
3. `Z_MCP_ROLE=text` 真的挑中了 `application-text.yml`（`serverInfo.name=z-mcp-text`、`instructions` 是文本服务那句、日志 `The following 1 profile is active: "text"`，目录里 2 条工具、0 条资源）；
4. `Z_MCP_BEARER_TOKENS` 在进程级别锁上了门：匿名 `/mcp/info` 回 `401` + `WWW-Authenticate: Bearer realm="z-mcp"`，而**逗号分隔的第二把** `b-token` 打得开。

顺带一条健康检查的形状：默认 hub 的两条上游端口上没人时，`/mcp/admin/health` 回 `DEGRADED` 并点名 `text`、`codec`（`unreachableServers`），启动日志留下两行 `mcp server text unavailable: Connection refused` —— 首连不在启动路径上，进程 1.0 秒起来，两条死上游既不拖慢启动也不把它报成 `UP`。

### 250 上的实机部署（09-26 16:1x 起在跑，17:17 换成带来源链的那一枚，23:37 换成 `7f92f61` 之后那一枚）

同一枚 fat jar 在 192.168.31.250 上按角色起三个进程，端口就是上面那三个（**直连**，不碰 250 上共用的 nginx —— 那是别人的家目录）：

```
~/zmcp/lib/z-mcp-server-0.2.0.jar   # sha256 0587fa659cb5c891…b9dd7b2eb4c69bc21d62，bytes=17998351（23:37 重发），与本机 target/ 逐字节同一枚
~/zmcp/logs/{hub,text,codec}.log
~/zmcp/run/{hub,text,codec}.pid      # text 15951 / codec 15970 / hub 15989
~/zmcp/env.sh                        # 600，三把 token 只活在这里，重发时沿用（不换 token 才能证明"旧的能打通靠的是身份判据不是凭证"）
~/zmcp/lib/keep/                     # 被替换掉的那一枚不删：z-mcp-server-0.2.0.jar.pre-7f92f61.6c5f7be9（18:23 起、跑了 5h11m 的那代字节）
```

历代（同一台机器上按 sha256 前缀认）：17:17 那枚 `306eaf33…`（带来源链，pid 26073/26021/26039）→ 18:23 那枚 `6c5f7be9…`（`a8cee6a` 的字节，**不含 SSE 写失败要 `complete()` 那条修复**，pid 6597/6617/6636）→ 23:37 这枚 `0587fa65…`。

口令不落版本库、不进命令行：`deploy_250.sh` 在远端 `openssl rand -hex 16` 现生成、写进 `~/zmcp/env.sh`（600），值不打印；本机验收用的 `~/.cache/zmcp_server_gauges/{hub,leaf}.hdr` 也是 600 的请求头文件，`curl -H @文件` 而不是 `-H "Authorization: Bearer …"` —— 后者会让同一台机器上任何一步 `ps` 读到它。**文档里记的是"口令在哪个文件"，不是口令本身。**

脚本三件（都在 `~/.cache/zmcp_server_gauges/`）：

* `deploy_250.sh`：先做**jar 字节级预检**（从 `BOOT-INF/classes/application*.yml` 里 grep 出打包进去的 `active: ${Z_MCP_ROLE:hub}`、`bearer-tokens: ${Z_MCP_BEARER_TOKENS:}`、两份叶子 yml 里的 `builtin-tools-enabled: false`），再核空闲端口 → scp → 远端 sha256 对账 → 生成 token → 按 text→codec→hub 起（叶子先起来，hub 首连就不会白摔一跤）→ 只认 `000` 的就绪轮询；
* `remote_start.sh` / `remote_stop.sh`：启动用 `( … exec java … ) & echo $!`，因为 `&&` 链整个放后台时 `$!` 记的是子 shell 而不是 java；停止只杀 `/proc/<pid>/cmdline` 里匹配 `z-mcp-server-0.2.0.jar` 的进程，250 上还有别人的 JVM 在跑（:8886 / :8888 / :18090 / :5278），按端口或按 `pkill -f java` 停都是拿别人的进程当代价；
* `verify_250.sh`：从 Mac 打向 250 的 **40 条**断言（`verify_250_run3.log`，`合计 PASS=40 FAIL=0`）—— `/mcp/info` 形状、hub 与叶子两把 token **互不通用**、匿名 401 带裸挑战、错 token 的 `error="invalid_token"`、完整握手到 `Mcp-Session-Id` 再到 `initialized` 202、hub 目录恰好 10 条、三条自省资源、`text_stats` 跨进程算出 words=3/utf8Bytes=17、`base64_codec`→`aGVsbG8=`、`hash_digest`→`sha-256 ba7816bf8f01cfea`、`find_tools`、`registry_state` 的 `2 2 10 0.2.0`、健康 `UP`、控制面匿名 401、叶子各自 2 条工具 0 条资源、SSE priming 帧与 `retry`、`DELETE` 之后旧会话 404 且理由点名会话。

这次部署本身交了一笔学费，值得留在这里：**还原源码不等于还原构件**。第一轮 38 条里红 17 条（修掉量具自身的错之后是 38→40 条，最终 `PASS=40 FAIL=0`）、hub 目录从 150 涨到 228 条，三台都以 hub 起并互相聚合。取证结果是 `/proc/<pid>/environ` 里明明有 `Z_MCP_ROLE=text`，profile 却是 hub —— 因为那枚 jar 是注入探针 P2（把 `application.yml` 的默认角色改成 `hub`）留在 `target/` 里的产物：探针按 md5 把**源码**还原了，但 `mvn verify` 会把 `src/main/resources` 复制进 `target/classes` 再 repackage，**改过的字节还活在 jar 里**。源码树的 mtime 和 jar 的 sha256 都回答不了"打包进去的是哪一份"，只有 `unzip -p … | grep` 能。预检那一步因此成为部署流程的一部分，而不是可选的谨慎。

顺带记下这次事故暴露的性质，以及它现在被守到哪一层：hub 指向"另一台 hub"而那台其实就是自己（或同配置的复制品）时，对岸的目录里装的是我这台目录的副本，于是每一轮再同步都往目录里加一代 —— 实测十分钟 150→228。机制是聚合的命名规则：上游工具**不与本地撞名时就按原名进目录**（撞了才加 `server__` 前缀），所以我把上一轮自己 invent 出来的那个前缀名在下一轮当成"别人的原名"收了进来。现在这道被 `ExternalServerManager` 在握手后就地拒掉：上游 `initialize` 回来的 `serverInfo.name` 等于本机 `z.mcp.server-name` 即判定自指，不拉目录、不登工具，`lastError` 写明是谁自指（判据取名字不取 endpoint —— 换端口、走 `localhost` 还是走机器名，自指都是同一个名字；`application-hub.yml` 的 `server-name` 就是 `z-mcp`，所以事故里那三台互为上游时会全部被拒）。这条由 `an_upstream_that_advertises_this_servers_own_name_is_refused` 钉住，先红后绿（红在 `expected:<0> but was:<2>`，红消息里那两个就是被抄进来的副本）。

**名字不同的两台 hub 互为上游**（`hub-a` ↔ `hub-b`）此前是"仍未覆盖"，现在靠**来源链**界住了。自指判据在这个形状里必然失效 —— 两边握手看到的都是对岸自己的名字。所以判据换成分布式的：每台 z-mcp 在 `tools/list` 广告每条工具时，往 `_meta["z-mcp/origins"]`（逗号分隔，越近的一跳越靠后）追加自己的 `server-name`；拉取侧（`McpRemoteClient`）把那串链原样收进注册表（**不含自己**），下一跳再广告时又追加它自己。于是 a 的内置工具经 b 抄回来时链是 `hub-a,hub-b`，a 一看链上有自己就不收这一条。要点有三个：链**内置工具也要盖章**（内置工具若不带链，它经对岸抄回来时链上就只有对岸一跳，判据认不出"这就是我自己的工具"）；链上缺字段（第三方 hub 会把我们的 `_meta` 原样丢掉）时**照常收**，不能把判据写成"没链一律拒"；这条链不参与寻址，`server__` 前缀规则一字未改。钉子有三层：`tools_the_upstream_copied_from_us_are_not_copied_back`（假上游一页目录里同时放"对岸自己的"与"抄我的"两条，后者不许进、前者是阳性对照）、`the_provenance_chain_grows_by_exactly_one_hop_per_server`（广告侧恰好加一跳、且链上已有自己时不重复追加）、`two_hubs_that_aggregate_each_other_reach_a_fixed_point`（真起两个 Spring 上下文、真 Tomcat、互指上游，先等各 12 条，再连着六轮逐轮复量目录，六轮后仍 12 条且不许出现 `__origin__` 这种第二代前缀名）。两把注入探针 09-26 17:3x 在 `1eda79e7ed6a65a7` 那棵树上重跑过，**18:5x 又在当前树（`3c22289a735f6b15`）上重跑一遍**：C0 零改动 283 条全绿（core 200 + admin 7 + starter 55 + server 21，四格 tally 都打印），J1/J2 各自恰好 2 条具名红、**红集与 17:3x 逐条相同**（脚本 `~/.cache/zmcp_server_gauges/inject_chain.sh` → 17:3x 的 `chain_inject/run3.log`、18:5x 的 `chain_inject_tree6/`；这一遍跑在 `~/.cache/zmcp_chain_check` 的隔离副本上；它收尾那行打印的还是脚本里写死的 `1eda…` 字面量（那一版还没改），所以我另取一次副本的整树哈希对账：四档跑完仍是 `3c22289a735f6b15`，与主树同值 ⇒ 变异没在任一棵树上留东西）。那行字面量已改成"运行时先量本次基线、收尾与它比"—— 改完第一版把 `BASE=$(tree_hash)` 放在函数定义之前，`bash -n` 通过而取到空值，也一并修掉并单测它产得出哈希：**J1** 把拉取侧那句 `getOrigins().contains(self)` 换成恒假 ⇒ 恰好 2 条具名红 —— 单元那条之外，真进程那条红在 `第 2 轮之后 a 的目录必须还是 12 条`，盘上实测 **18** 条，多出来的 6 条正是 `origin__echo` / `origin__find_tools` … 这一族 —— 我自己抄出去、被对岸按它的配置名加了前缀、再被我收回来的第二代名字。（注意这一族长的是 `origin__x` 而不是 `__origin__x`，所以用例里那句 `assertFalse(…"__origin__"…)` **抓不到它**，真正咬住的是"12 条"那个数 —— 只写名字形状那句负向断言就是空跑的。）而 **J2** 只给非内置盖章（在 `meta.put("z-mcp/origins", …)` 那句上多合取一个 `&& !t.isBuiltin()`）⇒ 也是 2 条红：广告侧 `内置工具广告时要带上自己这一跳 expected:<z-mcp> but was:<null>`，不动点那条连 `awaitCatalog` 都过不去（等的 12 条等不到；17:3x 那遍盘上是 **18** 条 —— 但这个"死法"并不稳定，见下一句）—— 这一支就是"内置也必须盖章"那句的猎物。**但这条红的"死法"不稳定**：同一个变异体单独连跑 3 轮 `HubAggregationTest`（脚本 `~/.cache/zmcp_server_gauges/chain_j2_symptom.sh` → `chain_j2_symptom/summary.txt`，09-26 18:5x，每轮前后树哈希 `e6aafd49a443b8bb`/`3c22289a735f6b15`）⇒ **3/3 全红**，可其中 2 轮的消息是 `tools/list 期望 200, 实得 429 rate limit exceeded`，只有 1 轮给出"现在是 [18 条，含 `origin__*`]"。原因在等待助手本身：`Boot.until` 的通用 `catch` 会拿传输错**覆盖掉上一次真看到的目录**，而这条用例每 100 ms 打一次 `tools/list`，两轮等待之间就能把自家的限流预算（默认 300/min）打穿。⇒ 判红只认**具名用例 + 分母**，不认消息；要稳定拿到"18 条"那张照片，得让 `Boot.until` 在传输错时保留上一次成功的观察。**这两把探针的第一版各有一个自己的 bug，都记在脚本头部**：J2 原先写成 `t.isBuiltin() ? new ArrayList<String>() : new ArrayList<String>()` —— 两支一模一样，是个**等价变异**，277 条全绿差点被读成"内置盖章没钉住"；另一处是 mvn 不加 `-Dmaven.test.failure.ignore=true` 时 core 一红就中止 reactor，server 那 21 条**根本没跑**（第一次修成 `-fae` 也不对：fail-at-end 会跳过失败模块的下游），于是"第三红在第 2 轮"那句话在头两份日志里既没被证实也没被证伪 —— 它是被跳过读成"没出现"的。

**还剩的边界**：链是 z-mcp 自己的约定，第三方 hub 若剥掉 `_meta`，它抄回来的东西就没有链可依 —— 那种环仍要靠"hub 别去聚合 hub"的部署纪律回避。协议里没有 `Max-Forwards` 那一类字段，这已经是不改协议能做到的最外层。

### 16:44 重发：两条闸都在真进程上量过

带上自指判据与"目录未变不重发布"之后重打 jar 发到 250，三件事各自有前后对账（都不靠单测自证）：

* 40 条跨机验收复跑：`verify_250.sh` → `verify_250_run4.log`，`合计 PASS=40 FAIL=0`。这一条同时是"新闸没有误伤正常拓扑"的证据 —— hub 聚合 `text` / `codec` 两台上游仍各 2 条工具、目录仍是 10 条（自指判据比的是 `serverInfo.name`，叶子报的是 `z-mcp-text` / `z-mcp-codec`，不相等）。
* 通知风暴：同一条尺 `watch_churn.sh`（开一条静默会话等 80 秒数帧）修前 **24 帧** `notifications/tools/list_changed`、修后 **0 帧**，而 `:keep-alive` 两边都是 3 帧 —— 后一句是必需的，否则"0 帧"也可能只是流根本没开起来。
* 自指判据：另起一台一次性 hub（用完即杀），把它的**两格上游都指向 18095**，并把出站 token 设成 18095 的入站 token —— 于是握手一定成功，"被拒"只可能来自身份判据而不是 401。实测两格都 `connected=false / toolCount=0`，`lastError` 逐字是 `upstream text identifies itself as this server (z-mcp) — …`，`/mcp/admin/health` 回 `DEGRADED` 并点名两格，目录停在内置 6 条且**再等一轮健康检查仍是 6 条**（拒绝日志 4 行 = 两格 × 两轮）。这里"等 33 秒再看一次"是量具的一部分：只在启动时看一眼的话，"拒绝一次然后每轮照旧抄进来"这种形状是看不出来的。

### 17:17 重发：来源链到了真进程

带着 `_meta["z-mcp/origins"]` 重打、重发（`deploy_run4.log`：jar 字节级预检四行 PASS → 三枚端口空 → scp → 本机与远端 sha256 逐字节相同、`bytes=17997223` → 按 text→codec→hub 起）。三件事各自有读数，都不靠单测自证：

* **40 条跨机验收再复跑**：`verify_250.sh` → `verify_250_run5.log`，`合计 PASS=40 FAIL=0`（40 行 PASS 逐条打印，不是"总数 40"）。
* **链在两台机器上各自的形状**：叶子 `text` 广告 `text_stats` 时是 `{"z-mcp/origins":"z-mcp-text"}`（一跳，只有它自己）；hub 广告同一条时是 `{"z-mcp/server":"text","z-mcp/origins":"z-mcp-text,z-mcp"}`（两跳）；hub 的六条内置是 `{"z-mcp/origins":"z-mcp"}` 且**不带** `z-mcp/server`。最后一格顺带钉住两个语义：内置也盖了章（否则它经对岸抄回来时链上只有对岸一跳，判据认不出"这就是我自己的"），以及 `_meta` 那两个键不是一回事 —— `z-mcp/server` 存的是**配置名**（`servers[i].name: text`），链里走的是协议里的 `serverInfo.name`（`z-mcp-text`）。
* **固定点**：hub 日志里 `mcp server … aggregated 2 of 2 upstream tool(s)` 恰好两行（每台上游首连一次），此后每一轮健康检查都不再多一行；整份日志 `grep -cE "WARN|ERROR"` 为 **0**。也就是说"目录不动"这件事在真进程上是**什么都不发生**，而不只是少发通知。
* **通知风暴**：同一条尺 `watch_churn.sh` → `churn_run5.log`，80 秒静默会话收到 **0** 帧 `list_changed`、**3** 帧 `:keep-alive`（后一句是必需的对照，否则 0 也可能只是流没开起来）。

量具自己也交了一笔：第一版链探针把 body 过了一道 `sed -n 's/^data: //p'`，打出 **0 字节**，看着完全像"服务端根本没广告 `_meta`"。真相是 POST 的回执是 `application/json`（只有 `GET /mcp` 那条流才是 `text/event-stream`），body 里就没有 `data:` 前缀可抠。**空输出先怀疑量具，再怀疑被测方。**

### 23:37 重发：把线上追回到 `7f92f61` 之后（#37 的断言改造与 #38 的签名修复都在这代字节里）

"源码修好 ≠ 线上修好"这句话此前只是记账，这次它有读数了：被换掉的那枚 jar（`6c5f7be9…`，18:23 起、跑了 5h11m）是 `a8cee6a` 的字节，**SSE 写失败要 `complete()` 掉那条流**的修复在它的 class 文件里根本不存在。

新的这枚是从 `90f18482affef148` 那棵树打的（与 CI 判绿的 `53ce20b` **代码逐字节相同**，`67a6e85` 只动 README，而整树哈希尺按设计不吃 README）：`mvn -B -ntp clean package -DskipTests -pl z-mcp-server -am`，构建 JDK 是 corretto `1.8.0_482`（250 上 `/usr/bin/java` 自报 `1.8.0_362`），日志 `~/.cache/zmcp_server_gauges/pkg_for_250_run5.log` 里 `BUILD SUCCESS`、`Total time: 11.925 s`、`Finished at 2026-09-26T23:36:10+08:00`，**构建前后各取一枚整树哈希同为 `90f18482affef148`** —— 少这一步，"这枚 jar 来自我量过的那棵树"就只是意图。

判"哪一代字节在 jar 里"仍然只能读 jar 内的字节，这次有两类证据：

* **常量池字面量**：读 `BOOT-INF/lib/z-mcp-core-0.2.0.jar` 里 `McpSessionStore*` / `ExternalServerManager*` 的 class 字节，新 jar 含 `dropped an SSE stream after a send failure`、`dropped an SSE stream during keep-alive`、`closed {} SSE stream(s)` 三行且 `returned {} tool(s) that this server` 在，**旧 jar 三行全无**（`keep-alive tick failed` 两边都在，正好当"读法没坏"的阳性对照）。
* **调用点计数**：`javap -p -c` 数 `McpSessionStore$McpSession` 里的 `SseEmitter.complete` —— 新 **3** 处、旧 **1** 处。注意别拿"字符串 `complete` 在 class 里出现几次"当尺：常量池会去重，新旧都是 1 次，那条尺结构上分辨不了任何东西。

量具自己也绊了一下：第一版用 macOS 的 `strings` 扫 class 文件，它把 class 认成 fat binary 直接报错、`grep -c` 得到 **0** —— 那是一个**坏掉的尺印出的"零命中"**，与"字节里真的没有"长得一模一样。换成 python `zipfile` 读原始字节再找子串才有上面那组读数。

部署与复验（`deploy_250.sh` → 本轮四行 jar 预检 PASS → 三枚端口空 → scp → 本机与远端 sha256 逐字节相同、`bytes=17998351` → 按 text→codec→hub 起 → 三枚端口各回 401）：

* **40 条跨机验收**：`verify_250.sh` → `verify_250_run6.log`，`合计 PASS=40 FAIL=0`，40 行 PASS 逐条打印。
* **官方 python SDK 互操作**：`interop_sdk.py` → `interop_sdk_run9.log`，`PASS=33 FAIL=0 NOT_COVERED=2`（那两格还是黑盒里做不到的 `list_changed` 与 `Last-Event-ID` 续传，记账口径没变）。
* **通知风暴**：`watch_churn.sh` → `churn_run6.log`，80 秒静默会话收到 **0** 帧 `list_changed`、**3** 帧 `:keep-alive`（对照组在，"0"才不是"流没开起来"）。
* **真进程侧的"目录不动"**：hub 日志一共 20 行、`grep -cE "WARN|ERROR"` 为 **0**，`aggregated 2 of 2 upstream tool(s)` 恰好两行（每台上游首连一次），此后 14 轮健康检查一行都没再多 —— 与 #38 想要的形状一致：不重发布不是"少发通知"，而是**什么都不发生**。

### 挂进真实 MCP 客户端

这台 hub 的最终验收不是"我自己的脚本打得开"，而是**别人家的 MCP 客户端**能不能挂上并用。挂在 Qoder 上写的是 `<repo>/.qoder/settings.local.json`（local 作用域、里面就是真 token，因此不进取版本库的 `settings.json`）：

```json
{ "mcpServers": { "z-mcp-hub-250": {
    "type": "http",
    "url": "http://192.168.31.250:18095/mcp",
    "headers": { "Authorization": "Bearer <env.sh 里那把>" }
} } }
```

写入后要让客户端重连（Qoder 是 `/mcp reload`）—— 这一步在人的那侧，代理做不到。等重连之前，可以先拿**配置文件里那一份 url + headers** 自己走一遍完整客户端时序（`09-26 16:49` 在 250 那枚新 jar 上复跑，五个断言都成立）：`initialize` → 200 + `Mcp-Session-Id` → `notifications/initialized` → `tools/list` 恰好 10 条（`echo` / `get_time` / `generate_uuid` / `system_info` / `find_tools` / `registry_state` / 两台的 `text_*` / 两台的 `base64_codec`+`hash_digest`）→ `tools/call text_stats{"text":"one two three"}` 回 `words:3, utf8Bytes:13` → `hash_digest hello` 回 `sha-256 2cf24dba…` → `DELETE` 带会话头 200、旧会话再请求 404。这一段的意义在于"能挂上的形状"由**客户端会读的同一份字节**证明，而不是由我另写一份等价的命令证明 —— 顺带暴露过一次自己的量具错：`DELETE` 漏带 `Mcp-Session-Id` 时回的是 `400`，那正是规范要的"会话头必带"，红的是探针不是服务。

上面那一段仍然是**我按自己的理解手敲的 curl**。在等不到人重连的这段时间里，能拿到手的最强证据换成了**别人家的实现**：官方 python SDK（`modelcontextprotocol/python-sdk` 1.27.1，与本仓零共享代码 —— 它自带 pydantic 模型、自己校验 `Mcp-Session-Id`、自己发 `notifications/initialized`）驱动 250 上那台 hub，脚本 `~/.cache/zmcp_server_gauges/interop_sdk.py` → `interop_sdk_run5/6/7.log`，三轮都是 **33 条具名 PASS / 0 FAIL / 2 条 NOT_COVERED**。它答的是四类只有第三方客户端才答得了的问题：

* **别人的 schema 层能不能读到我们的字段**：`_meta` 在 pydantic 那里落到 `Tool.meta`，`text_stats` 到客户端手里是 `{"z-mcp/server":"text","z-mcp/origins":"z-mcp-text,z-mcp"}` —— 来源链**穿过一个不相干的实现**到达了消费者，这件事本仓自己的测试结构上证明不了（它和我共享同一份解析代码）。
* **期望值一律现场派生，不敲数字**：支持版本集取自服务端自己的 `GET /mcp/info.protocolVersions`（它列 4 个，协商到 `2025-11-25`）；"hub 该比叶子多一跳"取的是**叶子那台当场广告的那串**；"该出现 `server__x` 前缀名的集合"由两台叶子的目录求交算出来（本部署交集为空 ⇒ 前缀名集合也必须为空）；广告条数与 `/mcp/info.toolCount` 两把独立的尺对账（10 == 10）。
* **错误走哪条通道**：未知工具 ⇒ JSON-RPC `-32602`（**报错而不是挂住**）；入参不合规 ⇒ 按 2025-11-25（SEP-1303）作为**执行错误**回 `isError`，且消息点名了缺哪个键 —— `$: missing required property 'text'`、`$: unknown property 'not_in_schema' not allowed`；同一趟里带正对照（`echo {"text":"ping-pong"}` 正常回显），否则"全都 isError"也能混过这一格。
* **会话生命周期与闸**：官方客户端收尾真的会 `DELETE` —— 三次完整开合之后服务端会话数与本轮起点相同；`prompts`/`resources` 两处"广告了能力就该答得出、没广告就不该答"两条一致；数据面匿名 `initialize` ⇒ 401，错 token ⇒ `Bearer realm="z-mcp", error="invalid_token"`（部署面上就是这个形状）。

跑第一版时两条 FAIL **都是我的判据错**，改法值得记下来，因为它是这个仓反复犯的那一种：① `transport` 我写成 `stream-http`，服务端一直是 `streamable-http`；② 我给"缺必填参数" expect 的是 **JSON-RPC 错误**（`McpError`），而实现按 SEP-1303 走 `isError` 执行错误 —— 于是一条正确的行为被判成缺陷。第 ② 条的正确修法不是把期望改成 `isError` 就完事，而是**两条通道同时量**并补正对照，因为"只 expect 一条通道"的判据在另一种实现下会同样假红。另记一笔免得后人误读：跨轮次 `sessions` 基线会从 1 涨到 3、4，那是正对照那把裸 `initialize` **故意不 DELETE** 留下的会话，`ttlSeconds=3600` 而 `sweepIfNeeded()` 只在 `create()` 时触发（最坏 60 s 一次），所以它按小时才退；这条断言刻意写成**同一轮内的差值**，不比跨轮绝对值 —— 它不是泄漏。两条 NOT_COVERED 也照实记：目录变化通知在部署面上造不出来（控制面只有 GET），`Last-Event-ID` 续传官方 SDK 根本不发 —— 这两维由本地真 socket 层的 `ZMcpSseStreamTest` 钉住，不在本门禁的账上。


## 内置工具与资源

工具：`echo`、`get_time`（`iso` / `epoch` / `iso_local`）、`generate_uuid`、`system_info`。
资源：`z-mcp://tools`、`z-mcp://servers`、`z-mcp://self` —— 注册表自身可读，声明 `capabilities.resources` 才不是谎报。

这四件加三条资源是 hub 的底盘（`builtin-tools-enabled=true` 时才登记）；`text` / `codec` 两个角色各自的领域工具见「独立进程宿主」。

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
mvn -B -ntp verify      # JDK 8（基线）或 JDK 17（发布给 Boot 3 生态时消费者跑的版本）
```

295 个测试（core 203 + starter 61 + server 24 + admin 7），0 failures、0 errors、0 skipped；JDK 8 与 JDK 17 各一次 `clean verify` 实测通过（量的是 `90f18482affef148` 那棵树、76 文件，构建前后各现算一枚、四枚同值；日志 `~/.cache/zmcp_teardown/verify7_corretto-1.8.0_482.log` 与 `verify7_ms-17.0.18.log`，四格读数是从各自日志的 `-- in <类>` 行现算的，两边逐格相同；上一版量的是 `ab97d7fdc5d4a7f8` 的 294 —— 这一轮多出来的一条就是 `ExternalServerManagerTest` 里那支 `churn_only_in_the_rejected_part_does_not_re_publish_the_accepted_part`，其余都是判定形状与注释的改动，所以 295 与 294 不是同一枚字节）。**CI 的 JDK 与本机不是同一份字节**（矩阵是 Temurin `8.0.504-1` / `17.0.20-1`，本机 `1.8.0_482` / `ms-17.0.18`）⇒ 本机全绿外推不出"CI 也绿"，稳定性只认 CI 那把尺。这一把尺刚在 `53ce20b`（就是上面那枚 `90f18482affef148` 的树，#37 的断言改造与 #38 的签名修复都在里面）上落下：run `36252082358` 两个 job 各自 `28` 类 / `295` 测试 / `0 failures 0 errors 0 skipped`，逐模块 core 203 + admin 7 + starter 61 + server 24 与本机 verify7 **逐格相同**，两份日志各自 `BUILD SUCCESS`（Maven 自报 `Total time: 55.945 s` / `01:00 min`），JDK 身份从 job 日志的 `Java_Temurin-Hotspot_jdk/8.0.504-1` 与 `/17.0.20-1` 两行读到 —— 日志留档 `~/.cache/zmcp_teardown/ci/53ce20b_<job>.log`（拉法：`gh run view --log --job <id>`，必须在仓内跑，否则 gh 没有 repo 上下文而**静默产出空文件**）。判"这轮真的跑在 8 上"要读 `mvn -version` 里的 `Java version: 1.8.0_482` 那一行 —— 本机 PATH 上的 `java` 是 25，`java -version` 给出的结论是错的。每份日志开头自报 JVM（`openjdk version "1.8.0_482"` / `"17.0.18" 2026-01-20 LTS`）、结尾自报 `exit=0`，并且**每次运行前后各取一枚整树聚合哈希**（仓内所有 `.java` / `.yml` / `pom.xml` 逐个 `md5 -r` 再 `sha256`，压成 16 位）：上一轮（09-26 18:3x，摘要 `~/.cache/zmcp_server_gauges/pair_summary_6.txt`）四枚全是 `3c22289a735f6b15`（再上一轮 `1eda79e7ed6a65a7` 是 core 195 / starter 54 那棵树，即 Content-Type 闸进树之前），构建结束后另取一枚现算的仍是同一个值 ⇒ 两个 JDK 量的就是现在这棵树。加这枚哈希是因为上一轮它恰恰不成立 —— `z-mcp-server/pom.xml` 在 15:54 被改过又改回，JDK 8 那次跑在改前、JDK 17 那次跑在改后，两枚"同一条命令同样绿"的日志其实量的不是同一棵树，而 mtime 之外的证据一条都拿不出来（哈希按内容算，改回去就落回同一个值）。这一行的守卫自己也错过一次：先前用 `md5 -q a b c` 存进变量再 `$($M1)` 执行，zsh 不分词 ⇒ 哈希行**静默为空**（看着像"没打印"，不是"对不上"）；现在哈希只由函数现算现打印，不落变量。并发那组不按"跑一次绿"结算：取消这条链上三个类（`McpCancellationTest` + `HttpJsonRpcExchangeTest` + `ZMcpCancellationTest`，JDK 8）连跑 10 轮 ⇒ 10/10 全绿、每轮 tally 都是 core 20 + starter 3，而且**这一支每一轮前后各取一枚整树哈希**（`repeat_cancellation.sh` 打印 `hash=前/后`，本轮 10×2 枚全 `3c22289a735f6b15`，与上面 JDK 那四枚同一值；脚本 `~/.cache/zmcp_logs/repeat_cancellation.sh` → `repeat_cancellation/summary.txt`，09-26 18:24）；`HttpJsonRpcExchangeTest` 单类再连跑 15 轮 ⇒ 15/15 全绿且收尾探针 `WARN z-mcp-test-httpserver-stop` 累计 **0** 次（`~/.cache/zmcp_logs/teardown_loop.sh` → `teardown/summary.txt`，18:25）。**逐轮哈希只有取消那支有** —— `teardown_loop.sh` 只记 rc/tally/warn，它那 15 轮"量的就是这棵树"是靠整个窗口收尾后现算的一枚 `3c22289a735f6b15`（70 文件，与上面 JDK 四枚同一值）兜的，不是逐轮兜的；要把这半句也升成逐轮证据，得把哈希补进那支脚本。这两个循环此前只有输出没有脚本（01:2x 那份 summary 里连"跑的是哪三个类"都没记，core 那格还写着 21 —— 今天按名字重量是 20），所以现在把类名钉进脚本里：**复现不出来的绿不算证据**。CI 见 `.github/workflows/ci.yml`（JDK 8 + 17 矩阵，每份 job 带 `timeout-minutes: 20`）。

**上一段那个总数是被 CI 追着改出来的**（写过的值依次是 283 → 286 → 292，每改一次都是因为树真的动了，不是因为读数读错）。它随 `a8cee6a` push 上去之后，CI run `36237383572` 判了 2 条红，而这两条在本机一台安静的机器上全绿 ⇒ **"两个 JDK 各一次 `clean verify` 通过"从来不覆盖时序**，它只说明单轮、宽松负载下成立。红的两条都是跨线程时序：

* `McpCancellationTest`（starter 那份同名类无关，红的是 core 这条）—— 中断是**投递**给另一条线程的，不是同步发生的：`future.cancel(true)` 一返回，等回执的那条线程就能拿到 `CancellationException`，而工具线程可能还没从 `await()` 里醒过来、还没置上它自己的标志。原来那两句 `assertTrue(gate.interrupted)` 撞的就是这段窗口（CI 上红在 0.011 秒那一条）。现在换成有上限的等待（`awaitInterrupted`，5 秒封顶），**判据没变**：中断始终没送到就等满然后照样红。猎物仍然在 —— 把 `InFlight.cancel` 里那句 `future.cancel(true)` 改成 `cancel(false)` ⇒ 盘上实测恰好两条具名红（`a_cancelled_tool_call_never_reports_a_result`、`numeric_and_textual_ids_are_different_requests`）。
* `ZMcpSseKeepAliveTest` —— 一条 `Premature EOF`，同一趟日志里有 Tomcat 的 `Encountered a non-recycled response and recycled it forcedly.`。顺着这条查出的产品侧缺陷是真的（但"它就是那条红的因"**没有证实**）：SSE 写出失败时只把 emitter 从名单里 `remove`，而 Spring 的 `send()` 在写失败时只置 `sendFailed` 并把异常原样抛回来、**不 complete 那条异步请求** —— 于是一次"客户端已经跑掉"的广播会留下一个再没人写、却要挂到 `sse.timeout-ms` 才闭合的异步上下文，容器直到它终于回收才把请求资源还回去。现在 `McpSessionStore.emit()` 与 `McpSessionStore.sendKeepAlive()` 两个写出点各补了 `complete()`，并且**一处一点配一条用例**：摘掉心跳那处的 `complete()` ⇒ 只有 `a_stream_that_cannot_be_written_to_is_dropped_by_the_heartbeat` 红；摘掉广播那处的 ⇒ 只有 `a_broadcast_to_a_client_that_hung_up_closes_it_without_disturbing_the_others` 红（脚本 `~/.cache/zmcp_ci_flake/inject_complete_probes.sh`，每支跑前后按 md5 还原对账 `b34dfacd0a9f`）。广播那条同时钉住另一半：摘 emitter 不许把事件本身弄丢，别的客户端仍然按那个 id 续得上（补出来的 id 与广播时逐字相同）。
* **复现率要记在账上，别把"改完"说成"修好了"**：这两条红本机一条都没复现过 —— 取消链在 20 个 `yes` 负载下 0/12 红（macOS）、`ZMcpSseKeepAliveTest` 本机 0/8、在 250（Linux，`git archive a8cee6a` 的树与本机逐字节对账同哈希）2 个 nice 负载下 0/10（`~/.cache/zmcp_ci_flake/*_wrap.log`、`250:~/zmcp-flake/logs/keepalive_wrap.log`）。所以这一版结论只能等 push 之后 CI 的读数来兑现 —— **`7f92f61` 的 CI run `36239267480` 两个 job 都绿**，且两份日志里四格 tally 挨个出现（`201 / 7 / 55 / 23`，全部 `Failures: 0, Skipped: 0`）⇒ reactor 真的跑到了 `z-mcp-server` 那一格，不是"上游一红、下游没跑"被读成通过。这仍然是**单轮**读数：它只说明这一版在 CI 的负载下没再撞到那两段窗口，不说明窗口已经被证明不存在（本机连 0/12 都没撞上过）。
* 第三处是量具自己的：`z-mcp-server` 的等待助手 `Boot.until` 原来会拿**传输错**（`429 rate limit exceeded`、连接重置）覆盖掉上一次真读到的目录 —— 这条等待每 100 ms 打一次 `tools/list`，两轮之间就能把自家限流预算（默认 300/min）打穿，于是同一个变异体连跑 3 轮有 2 轮的红消息只剩"实得 429"，"目录里到底是 12 条还是 18 条"那张照片丢了（就是上面 J2 记的那种不稳定）。现在观察位只由真读到的值占据，传输错单独计一次数报在后面。两条新用例（`BootWaitTest`）＋两支注入：让传输错重新覆盖观察 ⇒ 两条红；把失败原因从消息里摘掉 ⇒ 两条红（脚本 `~/.cache/zmcp_ci_flake/inject_boot_wait.sh`，还原对账 `4322e8b7533c`）。
* **会掐掉一条 SSE 流的五个出口现在各留一行字**（`d01f736` 三处 + `0da3159` 最后一处）：`McpSessionStore.emit()` 写出失败、`sendKeepAlive()` 写出失败、`closeEmitters()`（会话被终止或被回收）、`JsonRpcController.stream()` 开流时 `attach` 抛错 ⇒ 500，以及**空闲超时被容器按 `sse.timeout-ms` 收走**。最后这一处此前一个字都不说，而它在生产上最常见（笔记本休眠、客户端挂着长连接不发请求、反代按住上游连接池）；它沉默，服务端日志里就只剩"流结束了"，与"中间层掐了"是同一种症状。前三处缺的是同一件事的另一半：**客户端读到 `Premature EOF` 时，判红消息里没有任何一句说得出是谁收的流**。
* 于是给"判红要带出服务端的话"这条线索配了守卫（`fe95a7a`、`0da3159`）。捕获用 `ServerSideLogCapture`（logback `ListAppender`，只在 `z-mcp-starter` 那一侧装得起来 —— core 的测试 classpath 只有 `slf4j-api`，是个 NOP logger）。三支变异各咬一处：摘掉服务端那行收流日志 ⇒ 两支守卫**同时**红在"服务端要留下一行"；把 `ZMcpSseKeepAliveTest` 或 `ZMcpSseStreamTest` 各自 `frame()` 结尾的 `+ serverSide()` 摘掉 ⇒ 只有那一支红在"判红消息得把服务端同期的话带出来"（两处的 `frame()` 是各自独立的代码，共用 appender 不等于共用守卫 —— 上一版就是这么漏的：我只给 KeepAlive 装了带话，CI 却红在 StreamTest）。空闲超时那一路另配 `ZMcpSseIdleStreamTimeoutTest`（独立上下文，`timeout-ms=1200` 且 `keep-alive-seconds=0`：超时与心跳都是**开流那一刻**读配置的，沿用别处的 15000/30 永远撞不到这条路），它的牙也量过 —— 摘掉 `log.warn` ⇒ 红；把时长换成心跳秒数 ⇒ 同样红（断言里连 `1200` 这个数字一起要，"留了字但说错数"和没说一样害排障）；`onTimeout` 与 `onCompletion` 里的两次 `removeEmitter` **一起**摘 ⇒ 红在"会话不该还挂着死流"。但**单摘任一处都不红**（三支等价变异实测全绿）：那两行互为冗余，留着不是因为 Spring 不会自己收流，而是"客户端读到 EOF"与"`onCompletion` 落地"谁先谁后没有保证 —— 这条依据写进了两处源码注释，免得下一个人把其中一行当死代码删了。
* **`Premature EOF` 这条 CI-only 红仍然没修，但它已经能自报家门了。** 台账：run `36239426990`（树 `46fa646`，只在 jdk17 那格红）两条 = `ZMcpSseKeepAliveTest.heartbeats_do_not_move_the_position_a_client_resumes_from:136->frame:253 » IO Premature EOF` ＋ `ZMcpAutoConfigurationTest.closing_the_context_stops_the_health_check_thread:199`；run `36242758636`（树 `f8ccbd2`，同样只在 jdk17）一条 = `ZMcpSseStreamTest.a_stream_that_dropped_out_gets_back_what_it_missed:199->frame:504 » IO Premature EOF`。同一趟里的第二条红不是时序噪声而是真缺陷，已由 `1ffcb95` 根因到 `ExternalServerManager.destroy()`（只把 scheduler 置 null，于是迟到的首轮 `syncAll()` 能在已销毁的 bean 上再把周期线程起回来）并配了一条按构造必抓的 core 用例。两点读法：① 这条 `Premature EOF` 只在 jdk17 那一格出现过，jdk8 两遍都绿；② 它落在**两个不同的类**上 ⇒ 病在共用的那套裸 socket 读帧形状，不在某一条用例的逻辑。复现率照记不隐瞒：三个 SSE 类（每轮 16 条）在 ms-17.0.18 上连跑 12 轮 ⇒ 12/12 全绿、`Premature EOF` 累计 **0** 次（`~/.cache/zmcp_teardown/flake_loop2/run{1..12}.log`，每轮那个 16/0/0/0 是从各自日志的 `-- in <类>` 行现算的，不是抄循环自己打印的结论 —— 循环脚本先前用 `Skipped: 0$` 过滤聚合行，一红就把"红"读成"绿"，已改）。
* **守卫上树之后的第一次兑现就是它自己挣来的**：run `36244995856`（树 `0da3159`）jdk8 绿、jdk17 又红在同一条 `a_stream_that_dropped_out_gets_back_what_it_missed`（这次落在 `:222->frame:577`），而判红消息头一回带出了服务端同期说过的话 —— 原文就三行：`DEBUG … stream opened, replayed 0 buffered event(s)` ⇒ `WARN … dropped an SSE stream after a send failure: ClientAbortException: Broken pipe` ⇒ `DEBUG … stream opened, replayed 1 buffered event(s)`，然后客户端读到 `id:3` 就撞上 EOF。**这三行把方向定死了**：中间那句是**第一条流**被弃用后写失败时摘掉的（预期行为，`7f92f61` 那批日志正在岗），第三句说明续传那条流 `attach` 的写出**没抛**、服务端自认为已经把整帧交出去了 —— 五个出口一个都没说话，也就是说**流不是我们这五处收的**。字节丢在 Spring 写完与 socket 交付之间那一段，同一趟 CI 日志里还留着 Tomcat 的 `Encountered a non-recycled response and recycled it forcedly.`（12:42:51.195），指向"把一条写失败的异步请求 `complete()` 掉之后，那对 request/response 没被正常回收，下一次分配到它的请求写坏了"这一族。⇒ 这一条从"归因不到任何代码"升级成"有一个可证的假设待验"，下一支要打的是：失败写出后改用 `completeWithError(cause)` 还是 `complete()`，以及换掉之后那条 `non-recycled` 会不会跟着消失。**这一支已经打完了，答案是否定的**（台账见下面：CI 的切开率换前换后一模一样，本机两种写法各 200 轮都切不开），改动已回退。

* **这条 `Premature EOF` 现在要复现就能复现 —— 靠把形状做成一台尺。** `ZMcpSseAbandonResumeStressTest`（`e0ca5e3` 进树）原样重复客户端那一侧的动作：开一条流 → 读一帧 → 直接 `disconnect()`（服务端还不知道对端走了）→ 制造一次会撞墙的广播 → 立刻按 `Last-Event-ID` 回来续传 → 读补发帧，中间一步都不等；默认 5 轮，`-Dz.mcp.sse.abandon.resume.rounds=N` 拉长。**进树后 CI 连着两遍都复现**：run `36246012762` 的 jdk8 格 1/5 轮（第 3 轮）切开而 jdk17 全绿；run `36246808027` 的 jdk8 格 1/5、jdk17 格 2/5（第 0、2 轮），两次都红在**补发的那一帧之内**。判红消息里服务端同期说过的话把范围钉死了：`stream opened, replayed 0 buffered event(s)` ⇒ `dropped an SSE stream after a send failure: ClientAbortException: Broken pipe` ⇒ `stream opened, replayed 1 buffered event(s)` ⇒ 然后**五个出口一个字都没再说**，客户端读到半截就没下文。同一趟日志里还多出一条以前没见过的异常 —— `org.apache.coyote.CloseNowException: Failed write`（打在刚 `attach` 完约 5 ms 的那条流上），以及 Tomcat 9.0.83 的 `Encountered a non-recycled response and recycled it forcedly.`
* 顺着这台尺证伪掉两条猜测：① **"裸 `HttpURLConnection` 把弃掉的那条 socket 交给了下一次请求"** —— 一支只读探针在 JDK 8 与 17 上各 20 轮，服务端数到的连接数与请求数都是 40/40，没有复用；② **"换成 `completeWithError(ex)` 收尾能把那对 request/response 洗干净"** —— `ResponseBodyEmitter.completeWithError` 实测走 `Handler.completeWithError` → `DeferredResult.setErrorResult`（对 spring-webmvc 5.3.31 `javap` 看过字节码），换过去之后**CI 的切开率一模一样**（jdk8 1/5、jdk17 2/5），本机两种写法各 200 轮 ⇒ **0 次切开、0 行 ERROR**（区别只在 WARN 条数：`complete()` 那支 396 条、`completeWithError(ex)` 那支 355 条，而两边都没有 `already committed` / `NotUsable` / `recycl` 任何一行）。另外本机把形状拉满 `-Dz.mcp.sse.abandon.resume.rounds=2000`（约 5 ms/轮，10.3 秒跑完）⇒ 同样 **0 次切开**。也就是说这台机器上"弃流→立刻续传"这个形状本身是干净的，剩下能解释 CI 的只有 runner 的负载与容器状态。
* **门禁因此拆成两半，不再让 main 为一条容器层面的红常红**：产品自己承诺的那一半 —— "流被切开不丢事件：按最后一个完整帧续传，那条事件必须还在" —— 是**硬断言**；容器层面的切开率只**打印**出来记账（`[known-open] 弃流→立刻续传: rounds=5 torn=N lost=0`，CI 上它非零、本机它是 0，两边都得看得见同一个数）。硬断言的另一半有确定性来源：新增的 `a_stream_cut_off_in_the_middle_of_a_frame_can_still_be_resumed` 每轮都把"切在帧中"做成确定动作（只 `readLine()` 一行就撒手，于是客户端手里多一段没头没尾的 `id:`，而 `SseEmitter` 确实会把一帧拆成 `id:`/`data:` 多个片段写出），再按上一个完整帧续 —— 所以循环那支里的重试分支不是只在 CI 才走的死代码。
* **这台尺的牙是量过的**（`~/.cache/zmcp_teardown/prey8.py`：M0 基线 0 行红，三支变异各红 **2/2**，跑完按 md5 `d635aaf7b5d913e1f413dfdc4da39337` 还原对账）：P1 把补发过滤 `e.id > from` 写成 `>=`（把客户端已经收过的那条再补一遍）⇒ 两条红；P2 事件取了号却不入缓冲 ⇒ 两条红（续传拿 400）；P3 补发时不带原来那个 id ⇒ 两条红。**而 P1 第一次跑的时候只红了一条，缺的恰好是这台尺的核心断言**：`oneRound` 那边把 `lost++` 写漏了，于是"有 N 次没兑现"永远是 `0 == 0`。这与上面「全项行里藏着死卫兵」是同一族，而这一次的卫兵是我自己这一轮刚写的 —— 新增断言不经注入实测就不算门禁。
* **同一趟 run `36246808027` 的 jdk17 格还有一条红，它既不是这台尺、当时也还没修**：`ZMcpAutoConfigurationTest.closing_the_context_stops_the_health_check_thread:199`（同一趟的 jdk8 格那个类 10 条全绿）。`1ffcb95` 把根因（迟到的首轮 `syncAll()` 能在已销毁的 bean 上把周期线程再起回来）堵住了，可**判据本身仍然时间耦合**：它问的是"`ctx.close()` 之后 1.5 秒内有没有一条名叫 `z-mcp-server-health` 的线程"，而这个名字是全局固定的、`shutdownNow()` 又只发中断不等终止 —— 红只证明"那一刻确实有一条这个名字的线程活着"，是本次那条 worker 没醒透、还是同 JVM 里前一个 context 的残留，**说不清**。现在换成两半各自说得清的量（见下面三条）。

* **拆分上树后的第一遍 CI 就是它要的形状**：run `36248381016`（树 `3bb953b`，只动了这份 README）两个 job 都 success，`TOTAL 294 / failures 0 / errors 0` 且四格逐格可见；同一趟的 jdk17 格照常打印 `[known-open] 弃流→立刻续传: rounds=5 torn=1 lost=0` ⇒ 容器确实**又切了一次**，而事件一条没丢、门禁没红。该红的是"丢了事件"，该看见的是"被切了几次"，这两件事从此不在同一句断言里。
* 上一趟 `36248206476` 唯一那条红（`ZMcpSseStreamTest.a_stream_that_dropped_out_gets_back_what_it_missed:222->frame:577`，`Premature EOF(已读到: id:3)`）是拆分的另一半没跟上：压力尺学会了重试，这条单次读没有。现在它把"补发帧 + priming 帧"当**一对**取，任一帧没读全就整对作废重连（上限三次，因为承诺是在同一条连接上判先后，半截帧不能只换后半），并把 priming 的 id 从逐字相等 `seen + 2` 降成**严格递增** —— 每次 `attach` 都要为 priming 现取一个新号，重试一次号就往前跳一格，而真正要守的只是"客户端的位置只增不减"（priming 若复用补发那条的 id，客户端会把自己的位置说小，下次续传重收一条）。这一路的切开率同样进读数：`[known-open] 续传整对帧重取: torn=N attempts=M`（本机两个 JDK 各一次全量 `clean verify` 里它都是 `torn=0 attempts=1`，即一次读成 —— 与 CI 的 jdk17 格形成对照）。
* **这一轮重构的回归面是量具自己**：`frame()` 由"当场判红"改成"委托 `frameOrNull()`，由调用方决定重试还是判红"，`Torn` 的三条消息逐字保留 —— 因为 `a_stream_the_server_closed_names_the_server_in_its_own_red` 断言的正是"红消息里要带服务端同期的话"。第四支探针（`~/.cache/zmcp_teardown/prey9.py`）打的就是这一处：把 `frame()` 里的 `+ serverSide()` 摘掉 ⇒ 只红那一条（必红 1/1）。三支产品变异（P1 补发过滤写成 `>=`、P2 取号却不入缓冲、P3 补发不带原来那个 id）现在**各红 3/3**（一条在 `ZMcpSseStreamTest`、两条在压力尺），M0 基线 0 行红，两个被测文件跑完各自按 md5 还原对账（`McpSessionStore.java` `d635aaf7b5d913e1f413dfdc4da39337` / `ZMcpSseStreamTest.java` `d373f6b4f6d97afbfcdc01c489a42eab`）。
* 这条重试要写清楚代价：**它在安静的本机一次都触发不了**（本机 2000 轮 0 次切开），所以三支变异证的是"重写之后判定还在"，不是"重试那几行被实测走过" —— 后者只有 CI 的负载会给，目前它在 jdk17 格 5 轮里给过 1 次。

* **上一条那个"说不清"的判据已经换掉了**，`closing_the_context_stops_the_health_check_thread` 现在问两件各自量得动的东西：**产品承诺**那一半是数出来的 —— 起一台真的 JDK `HttpServer` 上游（`port 0` 让内核挑端口，只数 POST，永远答 500，于是每一轮同步恰好落在一次握手上、轮与轮之间没有别的东西在打它），**先等它被问到 ≥2 次**才允许往下走（这一步专防空跑：少了它，"关闭之后计数没动"可以是一次什么都没量的绿），`ctx.close()` 之后读一次、再等两个多周期读第二次，两次必须相等；**资源收口**那一半按身份而不是按名字 —— 开上下文**之前**先拍一张同名线程的快照，只把这一批新冒出来的算到自己头上（`Thread` 没覆写 `equals`/`hashCode`，放进 Set 就是按对象身份比），逐条 `join(3_000)` 后要求已终止。"迟到的首轮同步"那一族竞态不在这里量，它由 core 的用例按构造钉着（见下面 N3）。

* **宽限期必须是定长的，这条是被一次挂死教出来的**：第一版写成"等计数自己安静下来才算"（连续 `quiet` 毫秒不变 ⇒ 判稳），于是"摘掉 `scheduler.shutdownNow()`"那支变异不再判红而是**永不返回** —— 周期任务还在跑，quiet 窗口每拍都被复位，主线程卡在 `Thread.sleep` 里，实测绕了 **466 秒**（`~/.cache/zmcp_teardown/hang_prey10_N1_jdk25.txt` 那条 main 栈）。现在换成定长的"等 2 秒 → 读 → 等 2.5 秒 → 读"：2 秒是客户端总超时（300 ms 连接 + 300 ms 读）的三倍，够吸收关闭前最后一拍还在路上的那一次；这种形状没有可复位的窗口，慢只慢 4.5 秒，且一定出结论。同一个道理 core 那侧的注释早就写过（"上限而不是无超时 await：无界的等只会把'摘掉守卫'的变异从判红变成挂死，把量具一起拖走"）—— 这一轮是我自己在 starter 里违反了它。

* **这台尺的牙落在哪一层，是预测过再量的**（`~/.cache/zmcp_teardown/prey10c.py`，JDK 8 与 JDK 17 各一遍八次运行，两个 JDK 都"全部符合预期"）：N1 摘 `destroy()` 里的 `scheduler.shutdownNow()` ⇒ **两层各红**那条点名的用例，starter 的读数是 `expected:<4> but was:<6>`（2.5 秒窗口里正好漏出两个周期，这是请求数不是线程状态）；N2 摘 `@Bean(destroyMethod = "destroy")` ⇒ **只有 starter 红**，core 21 条全绿（它自己显式调 `destroy()`，那层的门还在）；N3 摘 `destroyed = true` ⇒ **starter 应当且确实全绿**（这一层没有"迟到的同步"这个输入，摘掉门是等价变异），core 红 —— 该用例把健康检查间隔配成 300 秒，周期任务在里面**永远不会自己触发**，那次迟到同步由测试亲自调 `syncAll()` 复现，于是红绿完全由调用次序决定、不赌时序。所以归属写死了：**starter 量 destroyMethod 装配 + `shutdownNow()` 真的停得住，core 量 `destroyed` 是终点。**

* **这台尺在干净树上的稳定性是 12 轮而不是 1 轮**（`health_loop.sh`，两个 JDK 各 6 轮）：12/12 `rc=0`、每轮 `tests=10 failures=0 errors=0`，且**点名的那条用例每轮都确实在 surefire 报告里** —— 读数只吃 XML 不吃日志文本，因为上一版脚本用了 `mvn -q`，而 `-q` 之下"没有红行"与"一条都没跑"在输出里长得一模一样（那一版的每轮读数就是空字符串，被当成绿）。每轮前后各取一枚整树哈希，24 枚全为 `454ace201ddfd65e`。

* **跑变异的地方换了，因为上一轮在共享工作树上出过一次事故**：量具被 SIGTERM 打断时 Python 的 `finally` 跑不到，带着 N1 变异的 `ExternalServerManager.java` 就留在了工作树上；另一个会话的 sweep（`4ab39a9`，标题"提交工作树现有改动并推送"）把它连同两个 `.bak` 一起提交并推送 —— **变异体上了远端**。`0386aec` 按 md5 把它还原（`819bbd4a2d7d7b6a38a79791e95f926f`，与 `8ab298a` 那笔的同一个 blob 逐字节相同）。此后凡是打 `src/main` 的量具只在副本里跑：`git archive HEAD | tar -x` 到 `~/.cache/zmcp_prey/tree`，再把本次未提交的那台尺覆盖进去 —— 覆盖后两棵树的源码聚合哈希相同（`454ace201ddfd65e`、76 文件），所以"副本量的就是这棵树"是算出来的，不是假设。那趟污染树的 CI（run `36249921686`）的结局是 `cancelled`，14:51 起跑、15:11 止，正好 20 分钟 = 每份 job 的 `timeout-minutes`；同一分支上干净那笔 `0386aec` 两个 job 都 `success` —— **挂死的树不会自己变绿，但也不会伪装成红**，判 CI 要认 `conclusion` 那个词，"没跑完"与"跑完且失败"是两种账。

* **这一轮的双 JDK 全量自己红了一格，而那一红是有猎物的**：`verify6` 的 jdk17 那次 `HubAggregationTest.two_hubs_that_aggregate_each_other_reach_a_fixed_point` 报"12 条只剩 10 条"（缺 `origin__registry_state`、`origin__system_info`），同一棵树的 jdk8 那次全绿。第一反应仍是"runner 负载"，但这次读数的**形状**不对：不是半截帧，是**条目退回去** —— 而且失败落在 `awaitCatalog` 最后那行 `assertEquals` 上，也就是 `Boot.until` 曾经量到 12 条、下一口读只剩 10 条。顺着这条去读代码，根因不在容器：`publish()` 的签名取在环检测**之前**，对岸聚合来的复制品一抖就把"收进来的那部分逐字未变"判成"目录变了"，于是先 `dropTools()` 再重登同一批 —— 而注册表是 `tools.remove()` 之后才 `fireChanged`，**那一次 fire 里读目录就是缺的**（单元尺观察到的是 `[]`，比真进程尺那次的"少 2 条"更干净）。细节与残留记在「聚合」那节第二条。
* 修完的账：真进程尺 `HubAggregationTest` 连跑 8 轮全绿（`~/.cache/zmcp_teardown/hub_loop.sh` → `hub_loop/summary.txt`，每轮 `tests=5 failures=0 errors=0`、两条点名用例都在报告里、每轮前后各一枚整树哈希同为 `90f18482affef148`）；新用例 `churn_only_in_the_rejected_part_does_not_re_publish_the_accepted_part` 在修之前**确实红**（`expected:<1> but was:<3>`；把两条断言换序再跑一次，先红的是"不许有空洞"那句，读数 `[[]]` ⇒ 空洞不是推断出来的），修之后整类 22 条全绿；两支反向探针 `~/.cache/zmcp_teardown/prey11.py` 在 JDK 17 上逐格符合预期 —— **Q2 把签名退化成"只取名字"时只有这条新用例红**（那句阳性对照是唯一还在盯内容变化的守卫，少了它这条尺就等于"永远不重发布"），Q3 摘掉环检测过滤则新用例与既有那条环尺一起红；M0 基线 22 条全绿，跑完按 md5 `fab2f93ee85b984c09d2c901b6d8b78c` 还原对账。**"洞在不在"是确定的，"读不读得到"才看时序** —— 本机单跑 8 轮一次没撞上，全量并跑时撞上了。

间歇缺陷不按"跑一次绿"结算。stdio 那一组先前在**同一份代码**上 8 次里挂 2 次（两个不同的竞态），修完按 `for i in 1..12` 复跑 ⇒ 12/12。单次绿只用来发现，不用来定罪也不用来赦免。

发布走 `.github/workflows/publish-central.yml`，**只有推 `v<x.y.z>` 标签（或手动 dispatch 并显式给 version）才会触发**，push 到 main 不发任何东西。版本号先过形状校验再落 `GITHUB_OUTPUT`：通配式 `[0-9]*.[0-9]*.[0-9]*` 的 `*` 会吞掉分号，实测 `0.2.0;rm -rf /` 能蒙过去（而这个值后面要进 URL 和 maven 命令行），所以换成严格的 `x.y.z[-后缀]` 正则；随后拿它探一次 `repo1.maven.org/…/z-mcp-core/<ver>/z-mcp-core-<ver>.pom`，**200 就拒绝发布** —— 中央仓库的坐标是永久占位，抬号是唯一出路（本机实跑这段脚本：`0.1.2` 那支被拦、`0.2.0` 是 404 放行）。credentials 与 GPG 由 `actions/setup-java@v6` 写进 `settings.xml`，口令经 `gpg.passphraseEnvName`（本仓钉 `maven-gpg-plugin` 3.2.7，≥3.2.0 才支持）从环境里取，**不出现在 `mvn` 的 argv 上** —— 同一台 runner 上任何一步 `ps` 都读不到它。

`-pl` 是**白名单**而不是可选优化，这一点用 reactor 顺序量过（本机离线跑 `mvn -B -ntp -o -Pcentral -Drevision=0.0.0-probe validate`，只到 `validate` 为止 —— 不上传、不签名，所以这条测量本身没有发布副作用）。不带 `-pl` 时 reactor 里是六件：`z-mcp`[pom] + `z-mcp-api` / `z-mcp-core` / `z-mcp-admin` / `z-mcp-starter` / **`z-mcp-server`**[jar]；带上 `-pl z-mcp-api,z-mcp-core,z-mcp-starter,z-mcp-admin -am` 之后是五件（四件产物 + 聚合根 pom），`z-mcp-server` 不在表上。为什么这值得钉：根 pom 的 `central` profile 把 source/javadoc/gpg/central-publishing **注进每一个模块**，于是"凡是列进 `<modules>` 的东西自动上中央仓库"，而 `z-mcp-server` 是一枚可执行进程 —— 坐标占位是永久的、没人会 import 它、发上去只是把一个进程伪装成库。新增模块因此默认不进发布名单，要对外发布必须显式加进来。不用 `maven.deploy.skip` 兜这一条：它对 central-publishing 的上传不生效（兄弟仓实测），写了属性却没真的跳过是最难查的那种"发布成功"。

测试刻意不用 mock 打靶：

* `StdioJsonRpcExchangeTest` 起**真实子进程**，验证响应乱序与夹带通知时仍按 id 配对、stderr 灌 350 KB 不死锁（管道缓冲 64 KB）、子进程退出能立刻让挂起的请求失败；且退出之后再发请求要马上以"传输已关闭"收口 —— 不能把裸的 `Broken pipe` 交给调用方，也不能把请求写进没人读的缓冲区白等一个读超时（这条是实测跑出来的竞态，不是设想出来的）；
  另外钉住两条收口规则：等待槽必须在**写管道之前**登记（否则一个已经热起来的上游可以抢在登记前回答，那行响应会被当"无人认领"丢掉，调用方白等一整个读超时）；上游只是**关掉了 stdout**（进程还活着）时传输也算死 —— 这种情况 reaper 永远看不见，只有 reader 能收口，`a_server_that_blinds_its_stdout_is_dead_even_while_the_process_lives` 用"错误信息里不该出现 exit code"来证明收口的确实是 reader；
* `HttpJsonRpcExchangeTest` 起**真实 `com.sun.net.httpserver`**，验证请求体逐字节原样、`Accept` 头、404 的响应体可读（`getErrorStream`）、卡住的上游会在读超时处断掉；它的**收尾自己也是量具的一部分**：JDK 8 的 `ServerImpl.stop(delay)` 先做 `schan.close()`（`ServerImpl.java:227`）、之后才置 `finished`，而那次 close 在本机一次全量运行里实测卡死在 native `preClose0` 再也没回来 —— 主线程 RUNNABLE 停在内核、dispatcher（JDK 自己 new 的，非守护、连名字都没有）停在 `select()`，`SIGKILL` 只能把进程停在 `?E`，整个 surefire fork 永久挂住（两份 jstack 存在 `~/.cache/zmcp_logs/hang_stack_1.txt` / `_2.txt`）。现在 `stop(0)` 跑在守护线程上、主线程最多等 10 秒，超时只打一行可 grep 的 `WARN z-mcp-test-httpserver-stop` 而**不判红**（那是 JDK 与内核的毛病，不是被测代码的）：断言在那一刻已经跑完，没道理让收尾决定 CI 的寿命。这枚守卫按注入自证 —— 把 stop 动作换成永不返回 ⇒ 11 条 WARN、整类 113 秒跑完、0 failures（没有守卫的话这就是第二次无限挂起）。同一次改动顺手关掉 `@Before` 里每例新建、从不关闭的 `fixedThreadPool(2)`（每例漏两枚非守护线程），CI 侧再补 `timeout-minutes: 20` 兜底；
* `McpRemoteClientTest` 覆盖握手时序、`notifications/initialized` 必发且不带 `id`、404 只重新握手一次而 401 永不重连、游标分页到 `MAX_PAGES` 上限时拒绝而非静默截断；
* `ZMcpAutoConfigurationTest` 证明配置真的被读取并且真的产生行为（包括异步首连、用户自己的 bean 优先、**容器关闭之后上游不再被问一次** —— 判据是数出来的请求数而不是数线程，见上面那三条）；
* `ZMcpHttpEndpointTest` + `ZMcpAdminEndpointTest` 起**真的 Tomcat**（`WebEnvironment.RANDOM_PORT`，测试 scope 里加 `spring-boot-starter-web`）。理由是 `base-path` 与 `expose-admin` 由 Spring 直接绑到 URL 上，`ApplicationContextRunner` 只看得到 bean 在不在、看不到请求进不进得来 —— 而下游接的恰恰是路径。这里验的是：`${base-path}/mcp` 上 `initialize` 能拿到 `Mcp-Session-Id` 而不带前缀的 `/mcp` 是 404；控制面跟着同一个前缀搬（只搬数据面等于把让给遗留模块的 `/mcp` 又自己占回去）；`notifications/initialized` 之前不给工具目录；`Accept` 少了 event-stream 回 406 且响应体仍是 JSON-RPC error；`DELETE` 之后同一个会话 id 立刻失效。
* `ZMcpListChangedNotificationTest` 钉的是**承诺兑现**那一层：capabilities 里广告了 `listChanged=true` 的三类目录，注册动作要真的变成流上对应那一类的通知 —— 而且只变成那一类。它挂在一个真的 `SseEmitter` 子类上收帧，顺带钉住通知不带 `id` 字段、每条通知各带一个递增的 event id、以及"会话没建流时广播是空操作"。
* `ZMcpAdminAuthTest` 证的是同一件事的另一半：**配了 token 之后控制面还开不开**。这里有个量具陷阱值得记下来 —— JDK 的 `HttpURLConnection` 把 `Origin` 列为受限请求头、客户端会把它**静默丢掉**，所以第一版用 `TestRestTemplate` 打跨站请求，读到的是"200，闸没生效"（假阴），换成裸 socket 手写请求行才量出真实的 403。同理，带请求体的 POST 撞 401 时 `HttpURLConnection` 会抛 `cannot retry due to server authentication, in streaming mode`（客户端的锅，不是服务端的），对称性探针因此改用无体的 `DELETE` 与 `GET`。这条探针最初只比**状态码**，所以 401 的挑战头在 `GET`/`DELETE` 那两路整个丢掉时它照样是绿的 —— 现在两扇门三个入口的 `WWW-Authenticate` 值要求逐字相等（见「有意边界」）。
* `McpSessionStreamBufferTest` 在会话对象这一层量续传的形状：挂一个只记账的 `SseEmitter` 子类，数它按什么顺序拿到哪些帧。它钉住开流的 priming 帧（带 id、`data` 为空、带 `retry`；`retry-ms=0` 时这一条必须不出现）、补发"恰好是漏掉的那些、且一条不重"、续上之后实时事件仍照收、缓冲停在配置值上（内存上界的唯一量具是 `bufferedCount()`）、`buffer-size=0` 时拒续传但流照开、客户端跑到服务端前面时不被赶走、以及一个**并发**性质：广播正好落在 `attach` 中间时要恰好送达一次 —— 那条不靠 sleep 猜时序，而是让记账 emitter 在第一次 `send`（即 priming，跑在临界区内）里放广播进来、并确认它确实被挡在会话锁外（`Thread.State.BLOCKED`），否则这条用例自己就会偶发红。心跳另加四条，全部**直接调 `store.tick()`**（不靠 sleep 猜周期）：一帧 `:keep-alive`、不带 `id` 也不带 `data`、心跳之后第一条真实通知的 id 仍紧接 priming；五拍心跳之后 `bufferedCount()` 仍是那两条、从 `Last-Event-ID: 0` 回来补出的还是 `{"n":1},{"n":2}`；写不出去的流（一个每次都抛 `IOException` 的 `Boom` emitter）被心跳摘掉而活着的继续收；`keep-alive-seconds=0` 时线程数一分不变、配 41 时恰好多一条且重复调用幂等、`close()` 之后那条线程在 5 秒内消失（数线程只认名字前缀 `z-mcp-sse-keepalive-`）。
* `ZMcpSseKeepAliveTest` 是心跳这一组里**唯一不自己敲 `tick()`** 的一层，因为上面那四条量的是"心跳这件事做对了没有"，量不到"有没有人真的在按周期敲它"：调度器建不起来、开流时没人调用 `startKeepAliveIfNeeded()`、配置没接上 —— 单元层全部照绿，而生产上"安静流被反代的空闲超时掐掉"这个症状会一模一样地回来（注入探针 K5 实测就是这个形状：core 14 条全绿、真 socket 3 条全红）。这一类把 `keep-alive-seconds` 配成 1，然后什么通知都不发，光等线上自己掉帧：安静流上要落下注释帧（`:` 开头、无 `id`、无 `data`），其后第一条真实通知的 id 仍是 `priming+1`；等两拍心跳再断流，用旧位置回来续传仍要补到断流期间那条通知（心跳占号的话这里直接是 `400`）；一条客户端已经跑掉的流不能把另一条的心跳一起带走 —— 这一条读**两拍**不是一拍，因为 `scheduleWithFixedDelay` 在任务抛出异常时是**永久**停摆的，只等一帧的话"整个线程被打死"和"正常"看起来一模一样。

* `ZMcpSseStreamTest` 补的是上一类的空洞：`GET /mcp` 自打写出来就没有被真的请求碰过。这里不装 emitter、不装 mock，真的在 Tomcat 上开一条流、握手后接着从 socket 里读帧，直到看见注册工具推出的那一帧为止（15 秒读不到就判红，不静默通过）。同一组里钉住 GET 侧的门槛：`Accept` 不含流 ⇒ **405**（json、`json;q=0.9`、`text/html` 三种写法各量一次，`*/*` 作正对照必须绿）、不带会话 ⇒ 400、会话不认识 ⇒ 404。续传也在真 socket 上量：开流第一帧要给出可续传的坐标（`id` + `retry`），据此断流、在离线期间注册一个**别类目**的资源（不能用工具 —— `notifications/tools/list_changed` 不带工具名，补发的那条和实时的那条在帧里长得一模一样，量不出"补"这个动作），重连时先等到那条 `resources/list_changed`、再等到 id 恰好在它之后的新 priming 帧；一条不带 `Last-Event-ID` 的新流即使背后有缓冲也不许补发任何东西；已经被淘汰掉的位置在线上就是 `400`，非数字的 `Last-Event-ID` 同样是 `400`。另有两条把 **logging** 也拉到真 socket 上量：工具抛异常要既在 POST 回执里 `isError`、又在流上落一条 `notifications/message`；以及"`setLevel(error)` 之后那条 warning 必须被压住"—— 这条负向断言不等超时，而是再注册一个工具当**记号**：同一会话的 emitter 是 FIFO，压不住的日志必然排在记号前面，所以"下一帧就是 list_changed"才是证据。量具上记四条：这条用例**不能**复用报状态码的 `code()` 助手（它 `disconnect` 之后流就关了）；每条用例都得显式设 `Accept`，因为 `HttpURLConnection` 会自己塞一串含 `*/*` 的默认值，不设就是在量客户端的默认值而不是服务端的闸；帧原文拼进断言消息前要压成一行，因为 surefire 在换行处截断消息 —— 不压的话红消息只剩 `id:1` 半截，而红消息后半段常常还藏着第二个缺陷；每条开过流的用例收尾要 `DELETE` 掉会话（`terminate(id)`），否则服务端那个异步请求会挂满 `sse.timeout-ms` 才收，而那条被客户端丢开的连接在这期间留在 `TestRestTemplate` 的池子里 —— 下一次握手复用它就撞 `Unexpected end of file`（实测撞上过一次，那一轮整类耗时从 4 秒涨到 25 秒）。
* `McpCancellationTest` 在 handler 这一层量取消的**形状**（真线程、真 latch，不用 mock）：中断要真的落到工具线程上并以 `-3280` 收口、取消比任务开跑更早到也杀得掉（`FutureTask` 取消之后 `run()` 一次都不执行 callable）、打空的四种写法（没有这个 id、连 `requestId` 都没有、`null`、非对象的 `requestId`）都不许动到那条真在飞的、数字 `5` 打不动字符串 `"5"`、别的会话拿着同型同值的 id 也打不动、迟到的取消不产生第二次作答也不留登记、`tool-timeout-seconds=0` 时工具在调用线程上跑（中断不了它，但服务端仍要承认"这条被取消过"而不是发一份成功结果）、会话都不存在时安静接受而不是 NPE、以及每轮之后在飞表必须是空的（`inFlightCount()` 是"登记/注销有没有漏"的唯一量具）。两个量具陷阱记在这里：`started` 那枚闩是"数尽即返回"的，同一条用例里第二次复用同一枚 gate 会让"猎物在飞"变成假断言 ⇒ 第二段换 gate、换工具名；而"没命中"这类负向断言靠的是同一条款子里"猎物照常答完 + 一次都没被打断"那两句，光一句 `assertFalse(interrupted)` 只是在赌时序。
* `ZMcpCancellationTest` 把取消拉到真 Tomcat 上，因为上面那九条是直接调 `handler.handle(...)` 的：它们量得到"命中之后做了什么"，量不到"取消到底找不找得到那条请求"。三件事只有真 HTTP 能证 —— `RequestContext` 必须每条请求一个新的（做成字段就会被迟到的请求顶掉 `requestKey`，取消打在完全无关的那条上）、寻址靠头里的 `Mcp-Session-Id`（那条用例故意让攻击者用**同型同值**的 id，这样挡住它的只可能是会话归属）、被取消的那条连接要拿回一条**完整**的 JSON-RPC 错误包络（`id` 对得上、`-3280` 在、不带 `result`）而不是一个断掉的 socket。第三条钉的是"工具体吞掉中断、坚持返回一个值"的形状：`FutureTask` 已经进了取消态，那份迟到的答案不得被当成成功发回去 —— 少那个 `catch (CancellationException)`，客户端看到的就是 `isError` 的"工具失败了"。`tool-timeout-seconds=30` 是量具的一部分：配成 `0` 工具就跑在 Tomcat 自己的线程上，第一条用例正是红在"中断没落地"。
* `StdioJsonRpcExchangeTest` 为这条路径补了一枚钉子（等子进程回答的中途被中断）：要在传输层就收口成 `IOException`、消息点名 `interrupted while waiting for stdio id=…`、把中断位还回去（`Thread.currentThread().interrupt()` 那句话以前没有任何针看着），并且这一刀之后同一条传输还能答下一个请求。取消把这条路从"只有超时会走"变成了常态。

新写的检查要先证明它会红才算数，所以这些检查是按注入 bug 验收的：把 `@RequestMapping("${z.mcp.base-path:}")` 改成空串 ⇒ 13 条里 12 条红（第 13 条是"控制面默认关"，与前缀无关，本就该绿）；把控制面钉回裸 `/mcp/admin` ⇒ 恰好 5 条红（4 条 socket + 1 条钉注解字符串的单测）；把工具清单的归属字段退回 `null` ⇒ 恰好 1 条红；把控制面上的那道闸拆掉 ⇒ 恰好 4 条红（匿名 401、错 token 401、跨站 Origin 403、数据面/控制面对称），而"好 token 打得开"这条本就该绿，`ZMcpAdminEndpointTest` 那 4 条无 token 用例也全绿 —— 后者同时证明**不配 token 时闸是空操作**，默认行为没被这次改动改掉，13 条数据面用例亦全绿。第一版里"会话不认识 ⇒ 404"这条在前缀被打坏时**仍然绿**，因为路由没命中也是 404 —— 补上响应体必须含 `unknown or expired session` 之后它才真的咬得住。

下面几段里"基线 N 条全绿"（心跳那组 221）是**那一刀当时**的 reactor 总数，不是现在的总数 —— 现在的只认上一节那枚 `~/.cache/zmcp_teardown/verify7_summary.txt`（`90f18482affef148`、76 文件；再往前是 `verify5_summary.txt` 的 `ab97d7fdc5d4a7f8`、`verify4_summary.txt` 的 `2223668fe0ea43bc`，以及 `pair_summary_5.txt` / `pair_summary_6.txt`，那几枚量的是 294 / 294 / 292 与 `de7bf96a3e6c9b7c`、`3c22289a735f6b15` 那几轮）。保留旧数是因为每一段的红集是按它那次代谢的类集合预测的，换成今天的总数就对不上账；`z-mcp-server` 那一格随这两枚 JDK 又各跑过一遍（两份日志里模块行各 `SUCCESS [ 16.367 s]` / `SUCCESS [ 20.498 s]`，reactor 自报 `Total time: 42.223 s` / `47.719 s`、`Finished at 2026-09-26T23:23:24+08:00` / `23:24:13+08:00`），24 条（5+11+2+5+1）在两份日志里逐格可见且逐格相同，它的数就是现值。

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

`z-mcp-server` 那 21 条的注入验收是**按配置文件**下的刀（脚本 `~/.cache/zmcp_server_gauges/inject_roles.sh`，日志同目录 `inject/P*.log`），因为这个模块的部署语义全在 YAML 的三行字上，而 YAML 打错一个字符不会有任何编译期信号。下面三档 09-26 17:24~17:26 量过一次（那棵树是 `1eda79e7ed6a65a7`），**18:3x 在当前树（`3c22289a735f6b15`）上重跑一遍，四档读数与具名红集逐条相同**（复量跑在 `~/.cache/zmcp_roles_check` 的隔离副本里：探针要按字节改 `src/main/resources/*.yml`，而共享工作树上正有别的会话在构建，不该留下任何"半套树"的窗口；副本与主树同哈希、四档跑完两侧现算仍是 `3c22289a735f6b15`。日志 `~/.cache/zmcp_server_gauges/inject_roles_tree6/`，包装输出 `~/.cache/zmcp_roles_check/inject_roles_tree6_wrap.log`。这一版下面记的就是复量读数）（基线对照零改动 = 21 条全绿、`exit=0`；上一轮那批读数是 20 条时的，只留了形状没留数）：

* **`bearer-tokens: ${Z_MCP_BEARER_TOKENS:}` 写成 `[""]`**（就是"含一个空串的白名单"，配置面上与"空列表"只差两个引号）⇒ 实测 21 条里红 18：1 条失败 + 17 条错误，红的正是那些不带 token 的握手 —— 也就是说"未设变量 = 门开着"这条默认一旦被换成"门锁死"，**几乎没有任何一条用例能安静地走过去**（本轮只剩 3 条绿的）。这 18 条里 `BearerGateTest.an_unset_token_variable_leaves_the_door_open_instead_of_locking_it_shut` 是唯一以断言失败收场的那一条，它单独就是这条默认的钉子（另外两句 `expected:<[]> but was:<[<blank>]>` 形状的东西都在别的用例的副作用里才显形，所以那一层"绑出来的 List 到底长什么样"的断言不能省）。这一档顺带证明了新加的 `two_hubs_that_aggregate_each_other_reach_a_fixed_point` 不是白搭车的一票：它就在红名单里（`:109`），门一锁死它的两次握手就过不去。
* **`builtin-tools-enabled: false` 从 text 角色里漏掉** ⇒ 预期 1 条红、实测 6 条：`RolesTest.the_text_role_advertises_exactly_its_own_two_tools` 按预期红在目录形状（叶子开始广告 `echo`/`get_time`/`system_info`），多出的 5 条（2 条 `BearerGateTest` + 3 条 `HubAggregationTest`）是同一个依赖 —— 它们等的是"目录精确等于 6 内置 + 2 上游工具"，叶子多吐 4 条它们就等不到。这一档的账记成"1 + 5 同依赖"，不算 5 枚独立钉子。
* **`spring.profiles.active` 从 `${Z_MCP_ROLE:hub}` 改成写死 `hub`** ⇒ **注入不出，21 条全绿**。原因不是实现漏了什么，而是**这套测试根本没走那行 YAML**：`Boot.app(...)` 一律在命令行上显式给 `--spring.profiles.active=<role>`，命令行参数优先级高于 yml，所以把 yml 里那行拆掉，单测一条都看不见。真正量到 `Z_MCP_ROLE` 的只有上面那台 fat-jar 冒烟（`The following 1 profile is active: "text"` 那一句），因此"角色靠环境变量选"这件事的证据面是**冒烟脚本而不是测试套件** —— 改了 `application.yml` 的角色默认值，`mvn verify` 不会提醒你，只有真起进程会。要把它拉进套件得让某个用例改成不传 `--spring.profiles.active`、只给环境变量，那是另一层工本，这一版选择写在这里。

三档跑完按字节还原并回读：两枚文件与探针前的 `.bak` md5 逐字相同（脚本自己校验，不一致就当场停），整树聚合哈希回到 `1eda79e7ed6a65a7` —— 与**当时**上面那两次 JDK `clean verify` 前后那四枚同一值 ⇒ 这轮注入没在树上留任何东西（**这三档在 Content-Type 闸进树后（`3c22289a735f6b15`）于隔离副本里复量过一遍，四档读数与具名红集逐条不变**（P0 21 绿 / P1 1 失败+17 错误 / P2 21 绿 / P3 6 条红；P3 那"1 + 5 同依赖"的分解也照旧：`RolesTest.the_text_role…:40` + 2 条 `BearerGateTest` + 3 条 `HubAggregationTest`））。**这一句是 17:25 量的**：中途有一次 `find … | md5 -r | shasum` 打出 `ed0b3a34c7e8ffb6`，那不是树坏了，是 P3 的改动还活着、探针正在跑 —— 判"还原完成"必须在最后一档的 mvn 退出之后取，早取一步就会把在飞状态当成残留（而反向更糟：在飞时看到坏哈希就去改源码，等于改在探针的备份上）。

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
* 聚合防环有**两层**（见「独立进程宿主」一节末尾）：其一，上游握手回来的 `serverInfo.name` 等于本机 `z.mcp.server-name` 就直接拒（自指/复制品）；其二，不同名的两台 hub 互为上游时靠 `_meta["z-mcp/origins"]` 来源链拒收自己抄出去的那批条目 —— 后者由两个真上下文的六轮不动点用例钉住，不是单测自证，并且 09-26 17:17 起重发的三台真进程上，广告出来的链已实测为 `z-mcp-text,z-mcp` 这一族（见「17:17 重发」一节）。仍然成立的那半句是：来源链是 z-mcp 自己的约定，**第三方 hub 若剥掉 `_meta` 就等于没链可依**，协议里没有 `Max-Forwards` 那一类字段可借。所以"hub 只聚合叶子"仍是推荐的部署口径，但它现在是省心的默认，而不是唯一不长的走法。
* `session.enabled=false`（无状态模式）下没有可寻址的会话，取消通知因此只能是空操作。
* `security.bearer-tokens` 为空即完全放开鉴权 —— 只应出现在本机/内网可信环境。
* 鉴权是**静态 Bearer 白名单**（`security.bearer-tokens`）。协议授权章节里的 OAuth 2.1 那一套（`.well-known` 元数据、动态注册、PKCE、令牌轮换）没有实现 —— 要对外暴露 `/mcp`，请放在做授权的网关后面，别把白名单当账号系统。
* 闸只有一把：数据面、控制面与那台存活探针共用 `TransportSecurityGuard.checkRequest(HttpServletRequest)`，一共五个调用点（`JsonRpcController` 的 `:82` POST / `:237` GET 流 / `:319` DELETE / `:349` `/mcp/info`，加 `AdminController:91` 一处盖住它的五个 mapping），因此配了 `bearer-tokens` 之后 `/mcp/admin/*` 同样 401（带 `WWW-Authenticate`），跨站 Origin 同样 403。0.2.0 之前它只挡 `/mcp`，控制面把上游 endpoint、`lastError`、全部工具 Schema 这些拓扑信息白送给任何能连上端口的人 —— 而配置者以为配了 token 就锁上了。修完控制面之后 `GET /mcp/info` 是**下一个同形的洞**：它报版本号、支持集的协议版本、工具/资源/prompt/会话计数，而它当时不经任何判断（实测匿名请求拿到 200，`Authorization` 与 `Origin` 都不看）。这条的口径是"探针不是白名单的例外"：负载均衡器与监控系统本来就在内网，把 token 配给它和配给 `/mcp` 是同一件事，而"为了探针把某一维放松"正是让闸变成一堆例外的开始。注入验收（`~/.cache/zmcp_guards/mutate_round2.py` 的 S1/S2/S3）：把闸整个摘掉 ⇒ 匿名读到 200，红在第一句；状态码留着、只丢挑战头 ⇒ 红在"挑战形状必须与数据面、控制面同形"那一句（证明这条用例不是只看状态码）；只挡没凭证、放跨站 Origin 过去 ⇒ 红在 403 那一句。
* 401 的 `WWW-Authenticate` 按 RFC 6750 §3.1 分三种形状：没带凭证 ⇒ `Bearer realm="z-mcp"`（裸挑战，叫客户端去取凭证）；带了 `Authorization` 但读不出令牌（非 Bearer 方案、或只有方案名没有值）⇒ `error="invalid_request"`（请求不成形，重试无意义）；令牌成形但不在白名单 ⇒ `error="invalid_token"`（可以换新令牌再来）。0.2.0 之前三者合成一句 `missing or invalid bearer token` + 一个 `invalid_request`，于是"token 配错了"这种最常见的部署事故会被报成"你请求姿势不对"，客户端的合理反应就变成拿同一份错配置一直重试。三个入口（`POST /mcp`、`GET /mcp` 的流、`DELETE /mcp`）与控制面共用同一个 `denied(v)` 出口 —— 这一维最初是漏的：只有 POST 带挑战，`GET`/`DELETE` 回裸 401，而从流那一路进来的客户端恰恰最需要知道"该怎么带凭证"；`ZMcpAdminAuthTest` 的对称性探针原先只比状态码，所以量不出来。挑战里**不带** `resource_metadata="..."`：那要求本仓真能端出 `/.well-known/oauth-protected-resource`，而静态白名单没有授权服务器可指，指出去就是一个 404 的广告（见上一条边界）。形状本身由 `TransportSecurityGuardAuthTest`（单元）钉，传播由 `JsonRpcControllerTransportTest.every_data_plane_method_carries_the_same_challenge`（三个入口）与 `ZMcpAdminAuthTest`（真容器、两扇门）钉。注入验收：三种形状合回一个值 ⇒ 3 条红；`invalid_request` 换成 `invalid_token` ⇒ 3 条红；空白名单改成"拒绝一切" ⇒ 25 条红；把 `GET`/`DELETE` 的挑战头摘掉 ⇒ 2 条红（controller 与真容器各一条）。
* 控制面只有 JSON，没有页面。`z-mcp-admin` 里那份 `templates/z-mcp-admin/index.html` 是死资源：没有任何代码引用它，而 Spring Boot 只会自动服务 `static/`（`templates/` 需要模板引擎），所以它连"打开就能看"都做不到。要控制台界面就自己拿 `/mcp/admin/*` 渲染。
* 下游 z-opc **目前没有消费本仓的任何构件**。它的两个 pom 里留着 `<z-mcp.version>0.1.2</z-mcp.version>`（根 pom:97、`z-middleware-integration-test/pom.xml:64`），但全仓没有一处声明 `z-mcp-*` 依赖 —— 实测 `grep -rn "artifactId>z-mcp" --include='pom.xml' z-opc` 在 z-opc 里 0 命中；250 上在跑的那个 `z-opc-main-starter-1.0.0-SNAPSHOT-boot.jar`（08-21 构建，473 个内嵌 jar，其中 21 个是本机自建的 `z-*` 构件 —— 这是"文件名里根本没有 groupId，但 `z-` 前缀确实数得见"的阳性对照）里 `BOOT-INF/lib/z-mcp*.jar` 也是 0。z-opc 的 MCP 面是自己那一套 `com.zifang.z.agent.mcp`（`z-agent-mcp-center`，68 个 java 文件），对前端暴露的形状是 `/mcp/server/{list,tools/list,tools/call,test,create,update,delete}`（`z-opc-main-starter-frontend/src/agent/api/routes.js:23-29` 逐条对上），协议版本硬编在 `2024-11-05`（`McpEndpointController.java:135`、`McpAliasController.java:45`）。两件事因此同时成立：**本仓改动不影响任何在跑的东西**（"发布前必须验下游构建"这句目前没有人需要执行），而**工作区里真在用的那个 MCP 实现落后本仓四个 revision**，它需要的形状（上游 server 清单、工具清单、代理调用、连通性测试）本仓已经有一套同域的件（`servers[]` 配置、`McpRemoteClient`、`/mcp/admin/servers`）。把哪一侧并到另一侧是跨仓的架构决定，不在本仓能自己动的范围里。

## 许可

MIT，见 `LICENSE`。
