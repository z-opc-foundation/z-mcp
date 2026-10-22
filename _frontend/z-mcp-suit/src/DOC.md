# z-mcp-suit

z-mcp（MCP 服务端 + 上游聚合网关）的**独立运行壳**（lead 005 §9.1 suit）：目前是一个「服务台」形态的控制台，
提供登录、首页快捷入口与服务状态（actuator 探针）三块能力；**MCP 控制面 / 工具目录页面尚未在本壳落地**。

- **npm 包名**：`@yuku123/z-mcp-suit`（`private: true`，**不发布 npm**）
- **宿主接入**：无（独立壳）。仓内对外出口是 `src/index.js`（页面清单 + 文档页）
- **后端**：`z-mcp-server`（可运行的 Spring Boot 应用）+ `z-mcp-admin`（控制面）/ `z-mcp-starter`（聚合面），
  但本壳**当前页面没有调用任何业务 API**；dev proxy 的 `/api` 指向 `http://localhost:8888`（平台侧通用地址）
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（**应用层**，基座内联进 bundle）

> ⚠ 本仓**没有** `z-mcp-component` 包：页面代码住在本壳 `src/` 下，
> 因此本文档描述的是**本壳自身的能力**。

---

## 一、能力概览

路由前缀 `/z-mcp`，菜单与路由都在 `src/pages-manifest.jsx` 里声明，`src/App.jsx` 读数组自己组装。

| 页面 | 路由 | 源文件 | 能力 |
|------|------|--------|------|
| 首页 | `/z-mcp/home` | `src/HomePage.jsx` | 欢迎语 + 按 `menuItems` 渲染的快捷入口卡片 |
| 服务状态 | `/z-mcp/status` | `src/StatusPage.jsx` + `src/Status.jsx` | 读 actuator 探针显示健康状态 / 构建信息，10 秒轮询 |
| 登录 | `/z-mcp/login` | `src/LoginPage.jsx` | mock 登录（**直接在 `App.jsx` 里注册路由，不在 `routes` 数组**） |
| 组件文档 | `/z-mcp/docs` | `src/pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

## 二、导出面

```js
// 仓内出口（src/index.js）
import {menuItems, routes, appMeta, ComponentDocPage} from './src/index.js'

// 单个页面（直接引源文件）
import HomePage from './src/HomePage.jsx'
import StatusPage from './src/StatusPage.jsx'
```

`src/index.js` 是本壳**唯一的 barrel**：把 `src/pages-manifest.jsx` 的清单整份转发，再具名再导出 `ComponentDocPage`
（与其它仓「文档页统一叫 `ComponentDocPage`」的口径一致，宿主/主壳可以按同一个名字取）。

## 三、API 端点

### 3.1 本壳实际调用的

下表路径是本壳页面**实际调用**的路径。⚠ **本壳当前没有任何业务 API 调用** —— 只有 actuator 探针。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/actuator/health` | 健康探针（`Status` 内直接 `fetch`） |
| GET | `/actuator/info` | 构建元信息（`Status` 内直接 `fetch`） |
| GET | `/actuator/mappings` | HTTP 路由清单（`Status` 页仅提供跳转链接） |
| GET | `/actuator/env` | 环境变量与配置属性（`Status` 页仅提供跳转链接） |

### 3.2 本仓后端提供、但本壳尚未消费

**控制面**（`z-mcp-admin` 的 `AdminController`，挂载在 `${z.mcp.base-path:}/mcp/admin`，
需 `z.mcp.expose-admin=true` 才开启，默认 base-path 为空串）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/mcp/admin/overview` | 总览 |
| GET | `/mcp/admin/tools` | 全部工具（JSON Schema） |
| GET | `/mcp/admin/servers` | 上游 server 清单（含 endpoint / lastError） |
| GET | `/mcp/admin/servers/state` | 上游状态 |
| GET | `/mcp/admin/health` | 健康 |

控制面走数据面同一把闸 `TransportSecurityGuard`：**令牌列表为空（默认）时这两扇门都照旧放行**，
配了 `security.bearer-tokens` 才挡。

**聚合面**：`z-mcp-starter` 的 `McpAliasController` 另有一组 `/api/mcp/**` 聚合端点
（server 清单 / tools 列表 / tools 调用 / admin 转发）。
⚠ 该文件近期在改动中，具体路径以源码为准。

⚠ 本壳**一个都没有调用**。

## 四、页面说明

### 4.1 首页

登录后着陆页。顶部欢迎语读 `localStorage.userInfo`，下方按 `menuItems`（剔除自身）渲染快捷入口卡片，点击即 `navigate(m.key)`。

### 4.2 服务状态

读 `/actuator/health` + `/actuator/info`，展示健康状态、构建版本、构建时间，并列出 actuator 常用端点跳转。
**后端未起时优雅降级**（loading skeleton + warn，不抛红）；每 10 秒轮询一次。

### 4.3 登录

**mock 登录**：硬编码 `admin / 123456`，成功后写 `localStorage.token`（值形如 `mock-admin`）与
`localStorage.userInfo`，再整页跳 `/z-mcp/home`。 CTC/SSO 接入后应换共享 `LoginPage` 的 `loginApi`。

### 4.4 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| dev proxy `/api` | `http://localhost:8888` | 平台侧通用地址；**本壳当前页面尚未使用** |
| dev server port | `3029` | `vite.config.js` 的 `server.port` |
| `LOCAL_SIBLINGS=1` | 关 | 开启后走 `alias: {}`（本仓目前无兄弟包别名） |
| token 存储键 | `token` | `localStorage` |
| userInfo 存储键 | `userInfo` | `localStorage`，JSON 串；解析失败降级为 `{name: raw}` |
| `appTitle` | `mcp 服务台` | `App.jsx` 里 `AppLayout` 的标题 |

## 六、已知限制

- **本壳没有业务能力**：菜单里目前只有首页与服务状态，**没有 MCP 工具目录 / 上游 server 列表 / 调用调试页面**。
  后端 `/mcp/admin/*` 与 `/api/mcp/**` 在本壳里一个都没被调用。
- **登录是硬编码 mock**（`admin / 123456`），没有真实鉴权；`App.jsx` 的 `ProtectedShell` 只判断 `localStorage.token` 是否存在。
- `Status` 页用相对路径 `fetch('/actuator/health')`，**要求前后端同域托管**；分域部署会 404（此时降级为 UNKNOWN）。
- `Status` 页 10 秒轮询一次 actuator，页面常开时会持续打后端。
- 后端控制面默认**不开**（`z.mcp.expose-admin=true` 才开），且默认**不鉴权**（bearer 令牌列表为空即放行）——
  真要接控制台页面，这两个开关要先想清楚。
- dev proxy 的 `/api` 目标 `:8888` 是平台侧通用地址，**与 z-mcp 仓本身无关**（本仓的服务是 `z-mcp-server`）。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.0（未发布） | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 + 菜单末项 |
| 0.1.0 | 首版：首页 / 服务状态 / mock 登录 + manifest |
