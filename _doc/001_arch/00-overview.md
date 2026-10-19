# 项目总览

## 1. 项目简介

z-mcp 是 z-opc-foundation 的 **MCP（Model Context Protocol）服务端 + 上游聚合网关**：
给一个已存在的 Spring Boot 应用挂上 `/mcp`，它同时成为两样东西。

- **MCP server** —— 业务工具经 `McpRegistry` 注册后可被任意 MCP 客户端调用（Streamable HTTP）。
- **MCP 网关** —— 把别处已存在的 MCP server（HTTP 上游或本地 stdio 子进程）聚合成**一份**工具目录，
  撞名自动加前缀，上游失联自动把它的工具从目录里撤走。

它解决的是「存量 Java 8 服务接不上 MCP」这件事：官方 `io.modelcontextprotocol.sdk:mcp` 要求
Java 17 + Project Reactor，而本仓编译目标是 Java 8，也不想把 Reactor 塞进中间件层的依赖树。
所以协议层与传输层自己实现，只用 JDK 8 能用的东西 —— HTTP 客户端走 `HttpURLConnection`
（`java.net.http` 是 11+），stdio 子进程的 stderr 由独立线程排空（`Redirect.DISCARD` 是 9+，
且丢弃 stderr 等于丢掉唯一的诊断线索），SSE 用 Spring MVC 的 `SseEmitter`。

**Maven 坐标：** `io.github.yuku123:z-mcp:${revision}`（当前 `0.2.1`，根 POM `packaging=pom`），
父项目 `io.github.yuku123:z-boot-parent:1.1.0`（`<relativePath/>` 留空）。

**技术栈：** Java 8 + Spring Boot 2.7.18 + Maven 多模块（5 个 reactor 模块）；
协议面为手写 JSON-RPC 2.0 over Streamable HTTP + SSE。

## 2. 模块架构

```
z-mcp/
├── pom.xml                      # 聚合 POM：父 z-boot-parent:1.1.0，5 个 reactor 模块
├── z-mcp-api/                   # 契约层（10 文件 / 933 行）：JSON-RPC 与 MCP 的 DTO
│                               #   api/dto（JsonRpcRequest / JsonRpcResponse / McpToolDto / McpResourceDto /
│                               #    McpPromptDto / McpServerDto / CallToolResult / ContentBlock / ToolAnnotations）
│                               #   api/exception（McpException）
├── z-mcp-core/                  # 协议与传输实现（15 文件 / 6528 行，本仓最重的一块）
│                               #   protocol（McpProtocolHandler / McpSchema 版本协商）
│                               #   controller（JsonRpcController：POST / GET / DELETE 同址 /mcp）
│                               #   session（McpSessionStore：会话签发 / TTL / 上限 / 事件补发）
│                               #   security（TransportSecurityGuard：同源校验 / Bearer 白名单 / 限流）
│                               #   registry（McpRegistry：工具等五张表）
│                               #   validation（JsonSchemaValidator）
│                               #   client（ExternalServerManager / McpRemoteClient / 两种出站传输 / Wire）
│                               #   builtin（BuiltinTools）、properties（McpProperties）、json
├── z-mcp-starter/               # 自动装配（3 文件）：ZMcpAutoConfiguration + 桥接与别名 Controller
├── z-mcp-admin/                 # 控制面（2 文件）：AdminController + AdminQueryService，只读 JSON 视图
├── z-mcp-server/                # 可执行进程（5 文件）：ZMcpServerApplication + hub / text / codec 三种角色
├── tools/                       # 仓内脚本（ci-tally.py / readme-sha-audit.py / verify_central.sh 等）
├── _frontend/z-mcp-suit/        # 前端壳（React 19 + antd 6 + @yuku123/z-frontend-common）
├── .github/                     # CI（含 publish-central.yml）
└── LICENSE
```

## 3. 核心功能

### 3.1 协议方法分发与版本协商（`z-mcp-core/protocol`）

`McpProtocolHandler` 分发 `initialize` / `ping` / `tools` / `resources` / `prompts` / `logging`
六类 JSON-RPC 方法并折叠结果。`McpSchema` 持有版本口径：实现基线 `2025-06-18`，
`LATEST_SUPPORTED = 2025-11-25`，`SUPPORTED_VERSIONS` 为 `2024-11-05` / `2025-03-26` / `2025-06-18` /
`2025-11-25` 四档，客户端未声明版本时兜底假定 `2025-03-26`。

### 3.2 Streamable HTTP 入口（`z-mcp-core/controller`）

`JsonRpcController` 把 `POST` / `GET` / `DELETE` 三个方法挂在同一地址 `/mcp`（前缀可由
`z.mcp.base-path` 改写），另加只读探针 `GET /mcp/info`。
`GET /mcp` 走 SSE，`SSE_PRIMING_SINCE = 2025-11-25` 之前的协议版本拿不到 priming 帧。

### 3.3 会话与续传（`z-mcp-core/session`）

`McpSessionStore` 负责 `Mcp-Session-Id` 的签发、TTL、上限，事件缓冲与断线后的补发，
以及 `:keep-alive` 心跳。默认口径（`z-mcp-server` 配置）：`ttl-seconds: 3600`、`max-sessions: 200`。

### 3.4 传输安全（`z-mcp-core/security`）

`TransportSecurityGuard` 做 Origin / Host 同源校验（防 DNS rebinding）、静态 Bearer 白名单
（`z.mcp.security.bearer-tokens`）、按会话限流。

### 3.5 注册中心与入参校验（`z-mcp-core/registry`、`validation`）

`McpRegistry` 维护工具 / 资源 / 资源模板 / 提示词 / server 五张表，支持整表快照交换。
`JsonSchemaValidator` 按工具声明的 `inputSchema` 校验 `tools/call` 的参数（认一个 JSON Schema 子集）。

### 3.6 上游聚合与出站传输（`z-mcp-core/client`）

`ExternalServerManager` 周期性重取上游工具目录，上游失联就把它的工具从目录里撤走；
`McpRemoteClient` 处理调用转发。两条出站腿：`HttpJsonRpcExchange`（Streamable HTTP）与
`StdioJsonRpcExchange`（本地子进程）。聚合时撞名自动加前缀。

### 3.7 内置工具与自省资源（`z-mcp-core/builtin`）

4 支内置工具：`echo`、`get_time`、`generate_uuid`、`system_info`；
3 条自省资源：`z-mcp://tools`（全部工具及其 JSON Schema）、`z-mcp://servers`（已接入的外部 server 及其工具数）、
`z-mcp://self`（本 server 身份与协议支持集）。`z.mcp.builtin-tools-enabled` 可关。

### 3.8 自动装配与控制面

- `z-mcp-starter` 的 `ZMcpAutoConfiguration` 同时提供 Boot 2 的 `spring.factories` 与
  Boot 3 的 `AutoConfiguration.imports` 两套注册。
- `z-mcp-admin` 的 `AdminController` 挂在 `/mcp/admin/*`，提供 `overview` / `tools` / `servers` /
  `servers/state` / `health` 五个只读视图；由 `z.mcp.expose-admin` 开关控制是否注册。

### 3.9 可执行进程的三种角色（`z-mcp-server`）

`ZMcpServerApplication` 一个 fat jar 装三种角色，由 Spring profile 切换：
`hub`（`HubTools`，目录服务能力）、`text`（`TextTools`）、`codec`（`CodecTools`）。

## 4. 对外集成 / 上下游

**对外 HTTP 边界：** MCP 数据面在 `/mcp`（`POST` / `GET` SSE / `DELETE`），只读探针 `/mcp/info`，
控制面 `/mcp/admin/*`。作为库引入时，端口与 context-path 随宿主应用。

**独立进程的三个端口（`z-mcp-server` 的 application*.yml）：**

| 角色 | 端口 | 配置文件 |
|---|---|---|
| hub（默认） | `18095` | `z-mcp-server/src/main/resources/application.yml` |
| text | `18096` | `z-mcp-server/src/main/resources/application-text.yml` |
| codec | `18097` | `z-mcp-server/src/main/resources/application-codec.yml` |

三者均可被环境变量 `Z_MCP_PORT` 覆盖；角色由 `Z_MCP_ROLE` 决定（`application.yml` 里
`spring.profiles.active: ${Z_MCP_ROLE:hub}`）。

**配置前缀：** `z.mcp.*`（`McpProperties`），总开关 `z.mcp.enabled` 默认 **false** ——
作为库引入时不设 `z.mcp.enabled=true` 就不装配。

**上游依赖：** 父链 `z-boot-parent` → 地板 `z-boot-dependencies` + 兄弟仓权威表 `z-boot-fleet`；
本仓 POM 内不引官方 `io.modelcontextprotocol.sdk:mcp`（那件要求 Java 17 + Reactor，是本仓自己实现的根因）。

**下游消费方：** 任何能作为 MCP 客户端的服务（挂上 `/mcp` 即可被调用）；
控制面供运维看目录与健康状态。

## 5. 技术特点

1. **协议层自研是 Java 8 的必然结果，不是偏好：** 官方 SDK 要 Java 17 + Project Reactor，
   本仓要在中间件层不引入 Reactor 的前提下提供同等能力，于是 HTTP 客户端退回 `HttpURLConnection`、
   SSE 退回 Spring MVC 的 `SseEmitter`、stdio 子进程 stderr 用独立线程排空。
2. **协议版本是显式口径而非「能跑就行」：** `McpSchema` 钉住四档支持版本、基线 `2025-06-18`、
   最高 `2025-11-25`、兜底假定 `2025-03-26`，SSE priming 也有独立的版本闸
   （`SSE_PRIMING_SINCE = 2025-11-25`）。
3. **一个地址三种 HTTP 方法：** `POST` / `GET` / `DELETE` 同挂 `/mcp`（Streamable HTTP 规范），
   会话语义靠 `Mcp-Session-Id` 头与 `DELETE` 终止。
4. **上游失联即撤工具：** `ExternalServerManager` 周期性重取目录，失联的 server 它的工具自动从
   聚合目录里撤走 —— 目录里永远不会挂着一个调不通的工具。
5. **撞名加前缀而非互相覆盖：** 聚合多个上游时工具名冲突自动加前缀，保留可追溯性。
6. **安全三件套叠在同一条链上：** Origin / Host 同源校验防 DNS rebinding、静态 Bearer 白名单、
   按会话限流 —— 都在 `TransportSecurityGuard` 一处收口。
7. **库与进程两种消费方式共用一套 core：** `z-mcp-starter` 给宿主应用挂 `/mcp`；
   `z-mcp-server` 用同一套 core 起独立进程，只是额外加载角色工具与控制面。
8. **每一条协议约定都配了对应测试：** `src/test` 共 38 个 Java 文件，
   握手禁用态、版本协商、参数校验、上游撤工具等各有独立用例。

## 6. 目录结构

本仓 Java 包根为 `com.zifang.z.mcp`，按 Maven 模块切分：

```
com.zifang.z.mcp
├── api/            # z-mcp-api：dto（9 个）+ exception（McpException）
├── core/           # z-mcp-core
│                   #   protocol / controller / session / security / registry / validation /
│                   #   client / builtin / properties / json
├── starter/        # z-mcp-starter：autoconfig（ZMcpAutoConfiguration）+
│                   #   bridge（McpToolRegistryBindConfig 工具注册绑定 + McpAliasController 别名）
├── admin/          # z-mcp-admin：controller（AdminController）+ service（AdminQueryService）
└── server/         # z-mcp-server：ZMcpServerApplication + role/{HubTools,TextTools,CodecTools,ToolArgs}
```

**规模（`src/main/java` 实测）：** core 15 文件 / 6528 行，api 10 文件 / 933 行，
server 5 文件 / 498 行，starter 3 文件 / 404 行，admin 2 文件 / 197 行。
