# 里程碑：一阶段生产级前端

> 对应代码：`frontend/`  
> 契约：`docs/api/phase-1-api.md` v0.2  
> 适用版本：React 19、Vite 8、TanStack Query 5、Axios、Zod 4、MSW 2

## 目录

1. [服务端状态与客户端状态](#服务端状态与客户端状态)
2. [HttpOnly Cookie 与启动恢复](#httponly-cookie与启动恢复)
3. [反向代理与 /api 前缀](#反向代理与-api-前缀)
4. [MSW 契约测试](#msw-契约测试)
5. [clientMessageId 重试机制](#clientmessageid-重试)
6. [Unicode 长度](#unicode-长度)
7. [面试问题](#面试问题)
8. [小实验](#小实验)

---

## 服务端状态与客户端状态

### 项目现象

用户信息、会话列表、消息、知识库都来自 HTTP API；主题偏好、诊断抽屉开关是浏览器本地 UI 偏好。

### 核心概念

| 类型 | 唯一来源 | 本项目落点 |
| --- | --- | --- |
| 服务端状态 | 后端 + 网络 | TanStack Query Cache |
| 客户端状态 | 浏览器 UI | Zustand（仅主题/诊断/移动导航） |

### 为什么这样划分

- 服务端状态有缓存失效、重试、并发更新问题；Query 已内置。
- 若把会话列表再抄进 Zustand，会出现「两份真相」：页面显示与服务器不一致。
- 认证视图 `useAuthStore` 放内存是为了路由守卫读取方便，**不持久化 token**；刷新靠 `/me` 恢复。

### 省略后的故障

- 登录后把 user 写入 localStorage：XSS 时扩大凭据面；且 HttpOnly token 本来就不该由 JS 读写。
- 发送消息成功后只改本地数组、不 invalidate Query：刷新后消息消失或顺序错乱。

### 对应代码

- `src/shared/api/query-client.ts`
- `src/shared/store/ui-store.ts`
- `src/features/auth/auth-store.ts`

---

## HttpOnly Cookie 与启动恢复

### 项目现象

登录成功响应体只有 `role`，**没有 token 字段**；浏览器自动保存 `access_token` Cookie。刷新页面后前端内存清空，但 Cookie 仍在。

### 底层过程

```text
POST /login → Set-Cookie: access_token=...; HttpOnly
后续请求 → 浏览器自动带 Cookie
刷新 → GET /me（withCredentials）
  ├─ 200：恢复 userId/email/role
  └─ 401 UNAUTHORIZED：进入登录页
```

### 关键不变量

- 只有 `401 + code===UNAUTHORIZED` 才清理登录态并跳转登录页。
- `INVALID_CREDENTIALS` / `USER_DISABLED` 留在登录表单展示，**不**当会话过期。
- `403 FORBIDDEN` 不退出登录。

### 对应代码

- `src/features/auth/hooks/useAuthBootstrap.ts`
- `src/shared/api/client.ts` 响应拦截器
- `src/app/router.tsx` 的 `BootstrapGate` / `UnauthorizedBridge`

### 常见误区

- 把 token 存 localStorage「更方便」。
- 任何 401 都跳登录（会把输错密码误伤成退出）。

---

## 反向代理与 /api 前缀

### 问题

SPA 有页面路由 `/login`，后端也有 `POST /login`。若前端与后端同域同路径，开发服务器会混淆。

### 方案

前端一律请求 `/api/*`：

- Vite dev：`/api/login` → 代理改写为后端 `/login`
- Nginx：`location /api/` → `proxy_pass http://backend:8080/`（注意尾部 `/` 会去掉 `/api` 前缀）

### 对应配置

- `frontend/vite.config.ts`
- `frontend/nginx.conf`

### 代价

部署时必须保证代理与 Cookie Domain/Path 正确；本地跨端口还要后端 CORS `Allow-Credentials`。

---

## MSW 契约测试

### 作用

在无后端时用 **同一路径、同一错误码、同一分页语义** 模拟 v0.2 契约，使页面与契约测试可先验收。

### 结构

- `src/mocks/data/store.ts`：内存状态仓库
- `src/mocks/handlers/*`：HTTP handlers
- `src/mocks/browser.ts`：开发时 Service Worker
- `src/mocks/server.ts`：Vitest 用 Node server

### 动态导入

`main.tsx` 仅在 `VITE_API_MODE=mock` 时 `import("@/mocks/browser")`，生产 real 构建默认不加载 MSW。

### 验证

`src/mocks/handlers/contract.test.ts` 覆盖注册登录、幂等、403、知识库 CRUD、重复删除 404。

---

## clientMessageId 重试

### 不变量

同一用户、同一会话、同一 `clientMessageId` 最多对应一条 USER + 一条 ASSISTANT。

### 前端规则

1. 用户**主动**点发送时：`crypto.randomUUID()` 一次。
2. 超时/网络失败点「重试」：**复用**原 ID，不重新生成。
3. 成功后以服务端返回的消息对替换本地「发送中」气泡。

### 失败场景

若重试时生成新 UUID，弱网会插入两条相同内容的用户消息与两条模板回复。

### 对应代码

- `src/features/chat/pages/ChatPage.tsx`
- `src/shared/lib/id.ts`
- Mock：`src/mocks/handlers/conversations.ts`

---

## Unicode 长度

JavaScript `string.length` 按 UTF-16 码元计数，emoji 等可能长度为 2。  
密码规则要求 **Unicode 码点** 8～64：使用 `[...str].length`。

对应：`src/shared/lib/unicode.ts` 与单元测试。

---

## 面试问题

1. 为什么 Access Token 放 HttpOnly Cookie 而不是 localStorage？XSS 与 CSRF 各怎么防？
2. TanStack Query 的 `staleTime` 与 `invalidateQueries` 分别解决什么问题？
3. 消息幂等为什么必须由服务端唯一约束保证，前端防抖不够？
4. 反向代理去掉 `/api` 前缀时，`proxy_pass` 带不带尾部 `/` 有何区别？
5. 为什么 `INVALID_CREDENTIALS` 不能触发全局登出逻辑？

---

## 小实验

1. Mock 登录 `customer@example.com`，发送消息后打开诊断抽屉，记录 `X-Client-Request-Id`。
2. 在 DevTools 删掉 `access_token` Cookie，刷新页面，观察是否回到登录页。
3. 修改 `ChatPage`：让重试也 `randomUUID()`，用 Mock 断网模拟，观察是否出现重复消息（再改回正确实现）。
4. 将 `VITE_API_MODE=real` 并启动后端，对比未实现接口的 404 与 Mock 全绿差异。

---

## 验证记录（实现时）

- 以仓库内 Vitest / Playwright / `npm run build` 实际结果为准，见任务交接「验证」一节。
