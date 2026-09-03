# 第一阶段前后端接口契约

> 状态：草案 v0.3（2026-09-03）  
> 依据：[一阶段需求文档.md](../../一阶段需求文档.md)  
> 已实现对齐：`POST /register`、`POST /login`、`POST /refresh`、`POST /logout`、统一错误体与双 Cookie 认证  
> 用途：前端可按本文 Mock；后端未实现接口须按本文交付，变更需同步更新本文

## 1. 文档目标与范围

### 1.1 目标

约定第一阶段全部 HTTP 接口的：路径、方法、鉴权、请求/响应字段、错误码与关键业务语义，使前后端可并行开发。

### 1.2 范围内

| 模块 | 能力 |
| --- | --- |
| 注册 | 邮箱注册 |
| 认证 | 登录、退出、401/403 行为 |
| 会话 | 创建、列表、详情、改标题、删除 |
| 消息 | 发送（含模板回复）、历史消息列表 |
| 知识库 | 创建者视角 CRUD（编辑者） |
| 文档 | 文本型文档 CRUD（编辑者） |
| 授权 | 知识库成员授权/取消（可延后实现，接口先占位约定） |

### 1.3 范围外（第一阶段不做）

- 真实大模型、RAG、文档解析、文件上传/对象存储
- 手机号注册与真实短信验证码、找回密码、第三方登录
- 消息队列（真实模型接入前不实现）
- 管理员后台、客户访问知识库管理端

### 1.4 与现有代码的关系

| 接口 | 代码状态 | 说明 |
| --- | --- | --- |
| `POST /register` | 已实现 | 字段与错误码以代码与本文一致为准 |
| `POST /login` | 已实现 | 查库、密码校验、禁用检查、Access/Refresh 会话创建已接通 |
| `POST /refresh` | 已实现 | Refresh 严格轮换、重放撤销与双 Cookie 更新 |
| `POST /logout` | 已实现 | 当前 Access 加入黑名单、当前 Refresh 会话撤销并清除 Cookie |
| 会话 / 消息 | 骨架已落地 | 路由、DTO、表结构与异常码已建；业务方法仍为 TODO（501） |
| 知识库 / 文档 / 授权 | 未实现 | 本文为前后端共同契约 |

---

## 2. 通用约定

### 2.1 Base URL 与协议

- 开发环境示例：`http://localhost:8080`
- 有 JSON 请求体的接口使用 `Content-Type: application/json`（无文件上传）；无请求体的 GET/DELETE/退出接口不要求该请求头
- 时间字段：ISO-8601 字符串，UTC 或带时区偏移，例如 `2026-07-17T08:30:00Z`
- ID 类型：数据库和后端内部可使用 `long`，但 **JSON 中统一序列化为十进制字符串**，避免 JavaScript 超过 `2^53-1` 后丢失精度
- 路径中的 ID 也使用十进制字符串；格式非法或超出 `long` 范围时返回 `400 INVALID_PATH_PARAMETER`
- 字段命名：JSON 使用 **camelCase**（如 `createdAt`、`knowledgeBaseId`）

### 2.2 认证方式

```text
方案：JWT Access Token + 随机 Refresh Token，均放在 HttpOnly Cookie
Cookie 名：`access_token`（可配置 `security.auth.cookie.name`）、`refresh_token`（可配置 `security.auth.cookie.refresh-name`）
Access TTL：900 秒（15 分钟）；Refresh 绝对 TTL：604800 秒（7 天，轮换不延长）
Cookie 属性（默认）：HttpOnly; Path=/; SameSite=Lax; Secure 本地开发可为 false
```

规则：

1. 登录成功：响应头 `Set-Cookie` 同时写入 `access_token` 与 `refresh_token`，**响应体不返回 token 明文**。
2. 普通请求只由 `access_token` 进入 Spring Security；Refresh Token 不作为 Access 凭证解析。
3. 普通接口第一次收到 `401 UNAUTHORIZED` 时，前端对 `/refresh` 做 single-flight 刷新，成功后仅重试原请求一次。
4. Refresh 成功会轮换 Refresh Token；旧 Refresh Token 再次提交（包括并发晚到请求）会撤销整个设备会话，并统一返回 `401 UNAUTHORIZED`、清除两个 Cookie。
5. 退出登录：服务端撤销当前设备 Refresh 会话并拉黑当前 Access `jti`，随后清除两个 Cookie；接口保持幂等 `200`。
6. 未登录访问受保护接口：HTTP `401` + 统一错误体 `UNAUTHORIZED`；前端清理本地登录态并跳转登录页。
7. 已登录但角色或对已可见资源的操作权限不足（如 CUSTOMER 调知识库接口、成员尝试删除知识库）：HTTP `403` + `FORBIDDEN`。
8. 访问不存在或无权的**他人资源**（会话等）：统一返回 **`404` + `NOT_FOUND`**（避免泄露资源是否存在）。角色不足仍用 `403`。

### 2.3 匿名可访问

| 方法 | 路径 |
| --- | --- |
| POST | `/register` |
| POST | `/login` |
| POST | `/refresh` |
| POST | `/logout` |

其余接口默认要求已认证。

### 2.4 统一成功响应习惯

- HTTP 状态码表示协议层结果（201 创建、200 成功、204 无正文删除等）。
- 除 `204 No Content` 外，成功 JSON 体必须带 `statusCode`（与 HTTP 数值一致）；资源接口额外返回业务字段。
- 列表接口返回包装对象，不用裸数组（便于后续加分页元数据）。

### 2.5 统一错误响应

```json
{
  "statusCode": 400,
  "code": "INVALID_REGISTER_REQUEST",
  "message": "Registration request fields must not be empty"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| statusCode | number | 与 HTTP 状态码一致 |
| code | string | 机器可读，前端分支判断用 |
| message | string | 人类可读；**不得**含密码、token、内部堆栈 |

### 2.6 通用错误码

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_*_REQUEST` | 请求体校验失败（缺字段、格式、长度等） |
| 401 | `UNAUTHORIZED` | 未登录 / token 无效或过期 |
| 401 | `INVALID_CREDENTIALS` | 登录失败（账号或密码错误，不区分是否存在） |
| 401 | `USER_DISABLED` | 账号已禁用，不能登录 |
| 403 | `FORBIDDEN` | 已登录但角色或权限不足 |
| 404 | `NOT_FOUND` | 资源不存在，或无权访问他人资源时对外伪装 |
| 409 | `EMAIL_ALREADY_REGISTERED` | 邮箱已注册 |
| 409 | `CONFLICT` | 业务冲突（如同创建者下知识库重名） |
| 400 | `MALFORMED_JSON` | JSON 语法错误、字段类型错误 |
| 400 | `INVALID_PATH_PARAMETER` | 路径 ID 不是合法十进制 long |
| 405 | `METHOD_NOT_ALLOWED` | 路径存在但 HTTP 方法不支持 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 有请求体的接口未使用 `application/json` |
| 500 | `INTERNAL_SERVER_ERROR` | 未预期错误；前端展示通用失败，响应不得暴露内部细节 |

### 2.7 校验与规范化

| 规则 | 约定 |
| --- | --- |
| 邮箱 | 去首尾空白 + `Locale.ROOT` 小写；最长 254；注册/登录均规范化后再查 |
| 密码 | 8～64 个 Unicode 码点；明文仅用于本次请求，禁止日志 |
| 会话标题 | 1～100 字（Unicode 码点或字符，实现与测试一致即可，文档按“字符”验收） |
| clientMessageId | 标准 UUID 字符串；同一次发送及其所有重试保持不变 |
| 消息内容 | 1～10000 字 |
| 知识库名称 | 1～100 字；同一创建者下唯一 |
| 文档标题 | 1～200 字 |
| 文档摘要 | 可选，最长 500 字 |
| 文档内容 | 1～100000 字（文本型，非上传） |

空字符串、纯空白在必填字段上均视为非法。

名称和标题在校验、唯一性比较及保存前去除首尾空白；消息与文档正文保留用户提交的原始文本，仅用 `strip()` 后是否为空判断纯空白。幂等重试要求 `content` 与首次请求完全一致，否则返回冲突。

PATCH 请求统一遵循以下语义：

- 字段缺省表示“不修改”；请求体 `{}` 返回对应模块的 `400 INVALID_*_REQUEST`。
- 必填语义字段（如 `title`、`name`、`content`）一旦出现，就不能为 `null`、空字符串或纯空白。
- 可选文本字段 `description`、`summary`：字段缺省表示不修改，传 `null` 或空白字符串表示清空并保存为 `null`。
- 第一阶段忽略未识别的 JSON 字段；前端不得依赖这些字段被保存或回显。

### 2.8 角色

| role | 说明 |
| --- | --- |
| `CUSTOMER` | 注册默认；会话与消息 |
| `EDITOR` | 初始化/脚本产生；含客户全部能力 + 知识库/文档 |

消息角色（与用户角色不同）：`USER` | `ASSISTANT`。

### 2.9 分页（第一阶段列表）

第一阶段列表采用**简单分页**（后续可换成游标）：

| 查询参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| page | number | 0 | 从 0 开始 |
| size | number | 20 | 最大 100 |

`page < 0`、`size < 1` 或 `size > 100` 返回该列表所属模块的 `400 INVALID_*_REQUEST`。

列表响应通用形状：

```json
{
  "statusCode": 200,
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

- 会话列表稳定排序：`updatedAt DESC, id DESC`。
- 知识库和文档列表稳定排序：`updatedAt DESC, id DESC`。
- 消息历史采用“最新页优先”：`page=0` 表示最新一页，`page=1` 表示再早一页；每页响应内部仍按 `createdAt ASC, id ASC` 返回，便于前端直接从上到下渲染。
- 简单分页期间有新数据写入时，较早页面可能发生偏移；第一阶段接受该限制，接入真实模型或长会话后再改为游标分页。

### 2.10 CORS 与 Cookie 联调注意

- 跨域前端（如 `http://localhost:5173`）需后端配置**明确的允许 Origin**，不能使用 `*`，并允许携带凭证；前端请求统一设置 `credentials: 'include'`。
- 不同端口属于跨 Origin，但在相同 scheme/host 下通常仍是同站；CORS 与 SameSite 解决的问题不同，必须分别配置和验证。
- `Secure=false` 仅限本地 HTTP 开发；部署环境必须使用 HTTPS 并设置 `Secure=true`。
- 当前代码关闭了 Spring Security CSRF 过滤器。该设置仅允许用于第一阶段受控本地开发；任何可被外部访问的部署环境，在开放写接口前必须启用 CSRF Token，或落地等价的防护（校验 `Origin`/`Referer` + 要求自定义请求头）并补充跨站请求测试。

---


## 2.7 工业级增补（2026-08 高并发重构）

### 2.7.1 限流与 429

| HTTP | code | 场景 | 头 |
| --- | --- | --- | --- |
| 429 | RATE_LIMITED | 触发限流（注册/登录 IP 10/min，发送消息 user 20/min，登录连续失败 5 次锁定 15 分钟） | Retry-After: 秒, X-RateLimit-Limit |

- 限流粒度：POST /register 按 IP；POST /login 按 IP（账号级防刷由 AuthService 连续失败锁定处理，不在 Filter 层读 JSON body）；POST /conversations/{id}/messages 按 userId。
- 超限响应体仍为统一错误体，code=RATE_LIMITED，前端应按 Retry-After 退避重试，避免立即重试。

### 2.7.2 幂等扩展

- 发送消息支持 Header Idempotency-Key: <UUID>，与 body 的 clientMessageId 等价；同时存在时以 Header 为准，不一致返回 400 INVALID_MESSAGE_REQUEST。
- 幂等语义不变：同 conversation_id + clientMessageId 仅一对消息，DB 唯一约束为最终仲裁，Redis SET NX 仅快路径。

### 2.7.3 分页与游标

- 保留 page/size offset 分页，但增加深分页保护：page*size >= 1000 时返回 400 INVALID_MESSAGE_REQUEST 或 INVALID_CONVERSATION_REQUEST。
- 新增可选 cursor（消息 id 字符串）游标分页：GET /conversations/{id}/messages?cursor=<id>&size=50 返回 cursor 之后（更晚）的消息，页内时间正序；未传 cursor 时行为与原来一致（page=0 返回最新一页，页内正序）。
- 旧前端不传 cursor 完全兼容。响应体字段与 offset 分页一致，不额外返回 cursor 游标（客户端取当前页最后一条的 id 作为下次 cursor）。

### 2.7.4 软删除可见性

- 删除会话后（会话与消息均软删除，行保留以维持外键），GET /conversations/{id}/messages 对已删会话返回 404；GET /conversations/{id} 同样 404；再次 DELETE 同一会话也返回 404。
- 消息/会话软删除字段 deleted_at 不暴露给前端。

## 3. 注册

### 3.1 注册

`POST /register`  
鉴权：匿名

**请求体**

```json
{
  "email": "user@example.com",
  "password": "password1"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| email | 是 | 规范化后唯一 |
| password | 是 | 8～64 字符 |

**成功：201**

```json
{
  "statusCode": 201
}
```

说明：第一阶段**不返回**用户 id；注册成功后需再调登录。

**错误**

| HTTP | code | 条件 |
| --- | --- | --- |
| 400 | `INVALID_REGISTER_REQUEST` | 缺字段 / 空白 |
| 400 | `INVALID_REGISTER_REQUEST` | 邮箱格式非法、密码长度非法；后端必须稳定映射为 400，不得泄漏为 500 |
| 409 | `EMAIL_ALREADY_REGISTERED` | 邮箱已存在 |

**不变量**

- 新用户固定 `CUSTOMER` + `ACTIVE`，请求体不得指定 role。
- 密码 BCrypt/Argon2 哈希存储，永不回显。

---

## 4. 认证

### 4.1 登录

`POST /login`  
鉴权：匿名

**请求体**

```json
{
  "email": "user@example.com",
  "password": "password1"
}
```

**成功：200** + 两个 `Set-Cookie`：`access_token=...; HttpOnly; Path=/; SameSite=Lax; Max-Age=900` 与 `refresh_token=...; HttpOnly; Path=/; SameSite=Lax; Max-Age=604800`

```json
{
  "statusCode": 200,
  "role": "CUSTOMER"
}
```

| 字段 | 说明 |
| --- | --- |
| role | `CUSTOMER` 或 `EDITOR`，供前端路由/菜单 |

**错误**

| HTTP | code | 条件 |
| --- | --- | --- |
| 400 | `INVALID_LOGIN_REQUEST` | 缺字段 / 空白 |
| 401 | `INVALID_CREDENTIALS` | 用户不存在或密码错误（文案统一，不暴露是否存在） |
| 401 | `USER_DISABLED` | 账号禁用 |

### 4.2 刷新登录态

`POST /refresh`  
鉴权：匿名（仅读取 HttpOnly `refresh_token` Cookie）

**请求体**：无；响应体永不返回 Token。

服务端按 `sessionId.randomSecret` 解析 Refresh Token，在事务内锁定对应 `refresh_sessions` 行，使用常量时间比较 SHA-256 哈希。会话未撤销、未超过固定 7 天期限且用户仍为 `ACTIVE` 时，原子轮换 Refresh 哈希并签发新的 Access/Refresh Cookie；上一枚仍有效的 Access `jti` 同时加入黑名单。

**成功：200**

```json
{
  "statusCode": 200
}
```

失败（缺失、畸形、过期、撤销、重放、用户禁用）对外均为 `401 UNAUTHORIZED`，并清除两个 Cookie，不区分具体原因。Refresh Cookie 不能当作 Access Token 访问受保护接口。

### 4.3 退出登录

`POST /logout`  
鉴权：匿名也可调用（便于清 Cookie）

**请求体**：无

**成功：200** + 清除 `access_token`、`refresh_token`（均 `Max-Age=0`）

```json
{
  "statusCode": 200
}
```

**说明**：退出只影响当前设备会话：当前 Access `jti` 立即进入黑名单，Refresh 会话被撤销；其他设备的会话不受影响。黑名单/Redis 暂时不可用时沿用 fail-open 可用性策略，已签发 Access 最长仍可能使用至 15 分钟自然过期，但该会话不能继续刷新。

### 4.4 当前用户

`GET /me`  
鉴权：已登录

**成功：200**

```json
{
  "statusCode": 200,
  "userId": "1",
  "email": "user@example.com",
  "role": "CUSTOMER"
}
```

| HTTP | code | 条件 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | 未登录或 token 无效 |

`GET /me` 是第一阶段必做接口。应用初始化或页面刷新时，前端先调用该接口恢复用户与角色状态；Access 过期但 Refresh 有效时，客户端先自动刷新再重试 `/me`，不得仅因内存状态丢失就要求用户重新登录。

---

## 5. 会话（Conversation）

均需登录。只能操作**自己的**会话；他人会话 → `404 NOT_FOUND`。

### 5.1 创建会话

`POST /conversations`  

**请求体**

```json
{
  "title": "新会话"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| title | 否 | 缺省时服务端可用 `"新会话"`；若提供则 1～100 字 |

**成功：201**

```json
{
  "statusCode": 201,
  "id": "10",
  "title": "新会话",
  "createdAt": "2026-07-17T08:00:00Z",
  "updatedAt": "2026-07-17T08:00:00Z"
}
```

**错误**

| HTTP | code | 条件 |
| --- | --- | --- |
| 400 | `INVALID_CONVERSATION_REQUEST` | 标题超长或非法 |
| 401 | `UNAUTHORIZED` | 未登录 |

### 5.2 会话列表

`GET /conversations?page=0&size=20`  

排序：`updatedAt DESC, id DESC`。

**成功：200**

```json
{
  "statusCode": 200,
  "items": [
    {
      "id": "10",
      "title": "新会话",
      "createdAt": "2026-07-17T08:00:00Z",
      "updatedAt": "2026-07-17T08:05:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### 5.3 会话详情

`GET /conversations/{conversationId}`  

**成功：200**（结构同创建响应中的会话对象 + `statusCode`）

```json
{
  "statusCode": 200,
  "id": "10",
  "title": "新会话",
  "createdAt": "2026-07-17T08:00:00Z",
  "updatedAt": "2026-07-17T08:05:00Z"
}
```

**错误**：不存在或非本人 → `404 NOT_FOUND`

### 5.4 修改会话标题

`PATCH /conversations/{conversationId}`  

**请求体**

```json
{
  "title": "改后的标题"
}
```

**成功：200**（返回更新后的会话对象）

**错误**

| HTTP | code | 条件 |
| --- | --- | --- |
| 400 | `INVALID_CONVERSATION_REQUEST` | 标题非法 |
| 404 | `NOT_FOUND` | 不存在或非本人 |

### 5.5 删除会话

`DELETE /conversations/{conversationId}`  

**成功：204**，无响应体。

第一阶段固定采用物理删除，并在同一事务中删除其下消息；数据库外键也应阻止孤儿消息。首次删除成功返回 204，再次删除同一 ID 返回 `404 NOT_FOUND`。

**错误**：`404 NOT_FOUND`

---

## 6. 消息（Message）

### 6.1 发送消息（聊天闭环）

`POST /conversations/{conversationId}/messages`  
鉴权：已登录，且会话属于当前用户

**请求体**

```json
{
  "clientMessageId": "018f6f5a-7d5b-7c3a-a08f-5cf5b26a7a21",
  "content": "你好，系统现在能做什么？"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| clientMessageId | 是 | 客户端为一次发送动作生成的 UUID；重试时必须复用同一值 |
| content | 是 | 1～10000 字 |

**首次成功：201；相同 `clientMessageId` 重试成功：200**

一次发送在服务端**同一事务**内：

1. 按当前用户、会话和 `clientMessageId` 判断是否已处理；已处理则返回原结果  
2. 持久化带 `clientMessageId` 的 `USER` 消息  
3. 生成固定模板回复并持久化指向该用户消息的 `ASSISTANT` 消息  
4. 更新会话 `updatedAt`

**幂等不变量**：同一用户在同一会话中，一个 `clientMessageId` 最多对应一条 `USER` 消息和一条 `ASSISTANT` 回复。前端遇到超时或连接中断时，应复用原 `clientMessageId` 重试，不能生成新值。

并发提交相同 `clientMessageId` 时，由数据库唯一约束决定唯一成功写入者；另一个请求等待事务结果后返回已存在的消息对。不得通过“先查询后插入”代替唯一约束。

响应返回**两条消息**（便于前端一次渲染）：

```json
{
  "statusCode": 201,
  "userMessage": {
    "id": "100",
    "conversationId": "10",
    "clientMessageId": "018f6f5a-7d5b-7c3a-a08f-5cf5b26a7a21",
    "role": "USER",
    "content": "你好，系统现在能做什么？",
    "createdAt": "2026-07-17T08:05:00Z"
  },
  "assistantMessage": {
    "id": "101",
    "conversationId": "10",
    "replyToMessageId": "100",
    "role": "ASSISTANT",
    "content": "已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。",
    "createdAt": "2026-07-17T08:05:00.010Z"
  }
}
```

幂等重试命中已有结果时，响应结构相同，仅顶层 `statusCode` 和 HTTP 状态码为 `200`；消息 ID、内容和创建时间必须与首次结果一致。

模板文案（固定）：

```text
已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。
```

**错误**

| HTTP | code | 条件 |
| --- | --- | --- |
| 400 | `INVALID_MESSAGE_REQUEST` | `clientMessageId` 缺失或不是 UUID、内容为空或超长 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一 `clientMessageId` 已用于不同的消息内容 |
| 404 | `NOT_FOUND` | 会话不存在、已删除或非本人 |
| 401 | `UNAUTHORIZED` | 未登录 |

**第一阶段不做**：修改/删除单条消息、流式 SSE。前端防抖可以改善体验，但不能替代服务端幂等约束。

### 6.2 消息历史

`GET /conversations/{conversationId}/messages?page=0&size=50`  

该接口默认 `size=50`（仍受最大 100 限制）。分页语义：`page=0` 返回最新 50 条，`page=1` 返回再早 50 条；每页 `items` 内部按 `createdAt ASC, id ASC` 排列。前端向上加载旧页时，将新取得的整页插入当前列表顶部。

**成功：200**

```json
{
  "statusCode": 200,
  "items": [
    {
      "id": "100",
      "conversationId": "10",
      "clientMessageId": "018f6f5a-7d5b-7c3a-a08f-5cf5b26a7a21",
      "role": "USER",
      "content": "你好",
      "createdAt": "2026-07-17T08:05:00Z"
    },
    {
      "id": "101",
      "conversationId": "10",
      "replyToMessageId": "100",
      "role": "ASSISTANT",
      "content": "已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。",
      "createdAt": "2026-07-17T08:05:00.010Z"
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 2,
  "totalPages": 1
}
```

**错误**：`404 NOT_FOUND`（会话不可见）

---

## 7. 知识库（Knowledge Base）

角色：仅 `EDITOR`。`CUSTOMER` 调用写/读管理接口 → `403 FORBIDDEN`。  
当前 v0.2 交付基线仅允许创建者管理；授权能力按第 9 节作为后续小版本启用。授权路由启用前，知识库列表只返回当前用户创建的资源。

资源路径前缀：`/knowledge-bases`

### 7.1 创建知识库

`POST /knowledge-bases`  
角色：EDITOR

**请求体**

```json
{
  "name": "产品 FAQ",
  "description": "对内说明"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| name | 是 | 1～100；同一创建者下唯一 |
| description | 否 | 最长 1000；可空字符串当 null 处理 |

**成功：201**

```json
{
  "statusCode": 201,
  "id": "1",
  "name": "产品 FAQ",
  "description": "对内说明",
  "creatorId": "2",
  "ownership": "OWNER",
  "createdAt": "2026-07-17T09:00:00Z",
  "updatedAt": "2026-07-17T09:00:00Z"
}
```

**错误**

| HTTP | code | 条件 |
| --- | --- | --- |
| 400 | `INVALID_KNOWLEDGE_BASE_REQUEST` | 名称非法 |
| 403 | `FORBIDDEN` | 非 EDITOR |
| 409 | `CONFLICT` | 同创建者下名称重复 |

### 7.2 知识库列表

`GET /knowledge-bases?page=0&size=20`  
角色：EDITOR

v0.2 当前返回用户**创建的**知识库；第 9 节授权能力正式启用后，契约升版并扩展为“自己创建的 + 被授权的”。  
排序：`updatedAt DESC, id DESC`。

列表项固定返回只读字段：

| 字段 | 说明 |
| --- | --- |
| ownership | v0.2 恒为 `OWNER`；授权能力启用后允许 `OWNER` \| `MEMBER` |

**成功：200**

```json
{
  "statusCode": 200,
  "items": [
    {
      "id": "1",
      "name": "产品 FAQ",
      "description": "对内说明",
      "creatorId": "2",
      "ownership": "OWNER",
      "createdAt": "2026-07-17T09:00:00Z",
      "updatedAt": "2026-07-17T09:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### 7.3 知识库详情

`GET /knowledge-bases/{knowledgeBaseId}`  
角色：v0.2 为 EDITOR 且是创建者；授权能力启用后扩展为创建者或已授权成员

**成功：200**（对象同创建，包含 `ownership`）

**错误**：无权或无此库 → `404 NOT_FOUND`（不暴露存在性）或无权角色 `403`（未登录仍是 401）。  
约定：**已登录 EDITOR 但对某库无权限 → 404**；**CUSTOMER → 403**。

### 7.4 修改知识库

`PATCH /knowledge-bases/{knowledgeBaseId}`  
权限：v0.2 为创建者；授权能力启用后扩展为创建者或被授权编辑者

**请求体**（字段均可选，至少一项）

```json
{
  "name": "新名称",
  "description": "新描述"
}
```

**成功：200** 返回更新后对象  
**错误**：空 PATCH 或字段非法 → 400；CUSTOMER → 403；资源不存在或无可见权限 → 404；重名 → 409。

### 7.5 删除知识库

`DELETE /knowledge-bases/{knowledgeBaseId}`  
权限：**仅创建者**。授权能力启用后，被授权成员可以看见知识库，但尝试删除返回 `403 FORBIDDEN`。

**成功：204**  
级联删除其下文档与成员关系。

**错误**：无资源可见权限的 EDITOR → 404；可见但无删除权限的成员 → 403；CUSTOMER → 403。

---

## 8. 文档（Document，文本型）

路径挂在知识库下。权限继承自知识库：能管理该库 → 能 CRUD 其文档。

### 8.1 创建文档

`POST /knowledge-bases/{knowledgeBaseId}/documents`  

**请求体**

```json
{
  "title": "退货政策",
  "summary": "七天无理由",
  "content": "正文……"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| title | 是 | 1～200 |
| summary | 否 | 最长 500 |
| content | 是 | 1～100000 |

**成功：201**

```json
{
  "statusCode": 201,
  "id": "50",
  "knowledgeBaseId": "1",
  "creatorId": "2",
  "title": "退货政策",
  "summary": "七天无理由",
  "content": "正文……",
  "createdAt": "2026-07-17T09:10:00Z",
  "updatedAt": "2026-07-17T09:10:00Z"
}
```

### 8.2 文档列表

`GET /knowledge-bases/{knowledgeBaseId}/documents?page=0&size=20`  

列表项固定不返回 `content`，只返回 `id/knowledgeBaseId/title/summary/createdAt/updatedAt`；详情接口再取全文。排序为 `updatedAt DESC, id DESC`。

**成功：200** 分页；items 无全文时：

```json
{
  "statusCode": 200,
  "items": [
    {
      "id": "50",
      "knowledgeBaseId": "1",
      "title": "退货政策",
      "summary": "七天无理由",
      "createdAt": "2026-07-17T09:10:00Z",
      "updatedAt": "2026-07-17T09:10:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### 8.3 文档详情

`GET /knowledge-bases/{knowledgeBaseId}/documents/{documentId}`  

**成功：200** 含 `content`  
文档不属于该 knowledgeBaseId → `404`

### 8.4 修改文档

`PATCH /knowledge-bases/{knowledgeBaseId}/documents/{documentId}`  

**请求体**（字段均可选，但至少出现一项）

```json
{
  "title": "…",
  "summary": "…",
  "content": "…"
}
```

**成功：200** 返回完整文档

空请求体、`{}`、非法标题或内容返回 `400 INVALID_DOCUMENT_REQUEST`；`summary: null` 或空白字符串表示清空摘要。

### 8.5 删除文档

`DELETE /knowledge-bases/{knowledgeBaseId}/documents/{documentId}`  

**成功：204**

**错误码模式**：与知识库一致（CUSTOMER 403；无库权限 404；校验失败 400）。

---

## 9. 知识库授权（后续小版本启用）

v0.2 先交付“创建者管理自己的知识库”，以下路由**暂不注册、不返回占位 501**，前端必须隐藏授权入口。后端准备实现授权时，应先将本文升版并把第 11 节状态改为“已实现”，再开放路由和前端 UI。

以下字段和行为是已经确定的后续契约，不再保留 userId/email 等实现分支。

### 9.1 授权编辑者

`POST /knowledge-bases/{knowledgeBaseId}/members`  
权限：仅创建者

**请求体**

```json
{
  "email": "editor.b@example.com"
}
```

规则：后端按注册规则规范化邮箱；被授权用户必须存在且为 `EDITOR`；不可授权自己；不可重复；创建者不必写入成员表。使用邮箱作为输入是因为第一阶段没有编辑者搜索接口，前端无法可靠获得数据库 userId。

**成功：201**

```json
{
  "statusCode": 201,
  "id": "1",
  "knowledgeBaseId": "1",
  "userId": "3",
  "email": "editor.b@example.com",
  "permission": "EDIT",
  "grantedBy": "2",
  "createdAt": "2026-07-17T10:00:00Z"
}
```

**错误**：目标邮箱非法、用户不存在、目标不是 EDITOR 或授权自己 → 400；已授权成员尝试继续授权 → 403；对知识库无可见权限的 EDITOR → 404；重复授权 → 409。

### 9.2 成员列表

`GET /knowledge-bases/{knowledgeBaseId}/members`  
权限：仅创建者；已授权成员调用返回 403，无资源可见权限的 EDITOR 返回 404。

**成功：200**

```json
{
  "statusCode": 200,
  "items": [
    {
      "userId": "3",
      "email": "editor.b@example.com",
      "permission": "EDIT",
      "grantedAt": "2026-07-17T10:00:00Z"
    }
  ]
}
```

### 9.3 取消授权

`DELETE /knowledge-bases/{knowledgeBaseId}/members/{userId}`  
权限：仅创建者  

**成功：204**

目标不是当前成员或知识库不可见时返回 404；已授权成员尝试取消他人授权时返回 403。

---

## 10. 前端对接要点

### 10.1 登录态

1. 登录成功：保存 `/login` 响应中的 `role` 到内存状态，不存 token；两个 token 均由 HttpOnly Cookie 管理。
2. 请求统一 `credentials: 'include'`（跨域时）。
3. 普通接口第一次 `401` + `code === 'UNAUTHORIZED'`：模块级 single-flight 调 `/refresh`；刷新成功后原请求最多重试一次。
4. `/login`、`/refresh`、`/logout` 不触发自动刷新；刷新失败时清 role、查询缓存并跳转登录页。
5. `403`：提示无权限，不强制登出。
6. 应用初始化或刷新页面：调用 `GET /me`，自动受益于上述刷新机制。
7. 发送消息发生超时或网络错误：保留原 `clientMessageId` 重试；只有用户主动发起一条新消息时才生成新 UUID。

### 10.2 建议页面与接口映射

| 页面 | 主要接口 |
| --- | --- |
| 注册 | `POST /register` → 成功后去登录 |
| 登录 | `POST /login` |
| 聊天侧栏 | `GET /conversations`，`POST /conversations`，`PATCH/DELETE` |
| 聊天主区 | `GET .../messages`，`POST .../messages` |
| 知识库列表 | `GET /knowledge-bases`（仅 EDITOR 菜单可见） |
| 知识库详情 | 文档列表/CRUD |
| 授权（后续小版本） | members 系列；当前不展示入口 |

### 10.3 Mock 建议

- 用与本文一致的路径和 `code`。
- 登录 Mock 时设置可写 Cookie 或开发代理同源，避免前后端 Cookie 联调脱节。
- 模板回复文案写死为第 6.1 节固定字符串。
- 消息 Mock 必须模拟 `clientMessageId` 重试命中原消息对，不能每次都生成新消息。

---

## 11. 接口一览表

| 方法 | 路径 | 鉴权 | 角色 | 实现状态 |
| --- | --- | --- | --- | --- |
| POST | `/register` | 匿名 | - | 已实现 |
| POST | `/login` | 匿名 | - | 已实现 |
| POST | `/refresh` | 匿名 | - | 已实现 |
| POST | `/logout` | 匿名 | - | 已实现 |
| GET | `/me` | 登录 | 任意 | 已实现 |
| POST | `/conversations` | 登录 | 任意 | 待实现 |
| GET | `/conversations` | 登录 | 任意 | 待实现 |
| GET | `/conversations/{id}` | 登录 | 任意 | 待实现 |
| PATCH | `/conversations/{id}` | 登录 | 任意 | 待实现 |
| DELETE | `/conversations/{id}` | 登录 | 任意 | 待实现 |
| POST | `/conversations/{id}/messages` | 登录 | 任意 | 待实现 |
| GET | `/conversations/{id}/messages` | 登录 | 任意 | 待实现 |
| POST | `/knowledge-bases` | 登录 | EDITOR | 待实现 |
| GET | `/knowledge-bases` | 登录 | EDITOR | 待实现 |
| GET | `/knowledge-bases/{id}` | 登录 | EDITOR | 待实现 |
| PATCH | `/knowledge-bases/{id}` | 登录 | EDITOR | 待实现 |
| DELETE | `/knowledge-bases/{id}` | 登录 | EDITOR | 待实现 |
| POST | `/knowledge-bases/{id}/documents` | 登录 | EDITOR | 待实现 |
| GET | `/knowledge-bases/{id}/documents` | 登录 | EDITOR | 待实现 |
| GET | `/knowledge-bases/{id}/documents/{docId}` | 登录 | EDITOR | 待实现 |
| PATCH | `/knowledge-bases/{id}/documents/{docId}` | 登录 | EDITOR | 待实现 |
| DELETE | `/knowledge-bases/{id}/documents/{docId}` | 登录 | EDITOR | 待实现 |
| POST | `/knowledge-bases/{id}/members` | 登录 | OWNER | 后续小版本，当前不暴露 |
| GET | `/knowledge-bases/{id}/members` | 登录 | OWNER | 后续小版本，当前不暴露 |
| DELETE | `/knowledge-bases/{id}/members/{userId}` | 登录 | OWNER | 后续小版本，当前不暴露 |

---

## 12. 已固定决策（v0.3，继承 v0.2）

1. 删除类接口固定使用 HTTP `204` 且无响应体；重复删除返回 404。
2. 无权访问他人会话/知识库对外统一 `404`，角色不足（CUSTOMER 调 EDITOR API）用 `403`。
3. 第一阶段仅邮箱注册/登录；手机号接口不出现。
4. `GET /me` 是一期必做接口，用于 HttpOnly Cookie 场景下刷新恢复登录态。
5. v0.2 不暴露知识库授权路由；授权能力启用前，UI 只展示“我创建的知识库”。
6. 消息发送使用 `clientMessageId` + 数据库唯一约束实现幂等；前端防抖只改善交互体验。
7. 列表分页 `page` 从 0 开始；消息列表的第 0 页特指最新一页。
8. JSON 中所有数据库 ID 固定使用十进制字符串。
9. 双 Token：Access 15 分钟、Refresh 固定 7 天；Refresh 严格轮换，旧 Token 重放撤销当前设备会话。
10. 退出登录拉黑当前 Access `jti`、撤销当前 Refresh 会话并清除两个 Cookie；多设备会话互不影响。

---

## 13. 变更流程

1. 任一方需要改路径、字段或错误码：先改本文并升小版本（如 v0.3），再改代码/Mock。  
2. 已实现接口以测试与本文冲突时：**以可运行测试 + 本文同步更新**为准，禁止只改一端。  
3. 后端落地某模块后，把第 11 节「实现状态」更新为已实现，并在 PR/提交说明中引用章节号。

---

## 14. 验收对照（接口视角）

| 验收场景 | 依赖接口 |
| --- | --- |
| 注册并登录进入系统 | `POST /register` → `POST /login` |
| 创建会话发问见模板回复且可重开 | `POST /conversations` → `POST .../messages` → `GET .../messages` |
| 相同消息请求重试不重复写入 | 使用相同 `clientMessageId` 连续调用 `POST .../messages`，两次返回相同消息 ID |
| 用户 B 不能动用户 A 的会话 | B 调 A 的 id → 404，A 数据不变 |
| EDITOR 管理知识库与文档 | 第 7、8 节 |
| CUSTOMER 调知识库写接口 | 403 |
| 授权版本启用后 B 可改文档、C 不可 | 第 9 节 + 文档 PATCH |
