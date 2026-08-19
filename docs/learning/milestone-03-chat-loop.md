# 里程碑 03：聊天闭环（骨架）

本文对应聊天垂直切片的**工程骨架与关键设计**：会话/消息表、分层包、HTTP 路由、错误码、以及 `MessageService.send` 的单事务与幂等流程注释。

> **项目事实（截至 2026-07-25）**  
> 包 `yangsirly.rag_agent.chat`、Flyway `V2`、H2 测试 schema、Controller 路由与 `ChatExceptionHandler` 已接通；Spring 上下文可加载。  
> **业务方法体仍为 TODO**：调用会抛 `UnsupportedOperationException`，由异常处理器映射为 **HTTP 501 `NOT_IMPLEMENTED`**，**不是**契约中的 201/200 聊天成功响应。  
> 集成测试、MySQL 上的 V2 迁移验证、完整发送幂等并发测试均未做。实现业务并验证后，应把本文状态从“骨架”改为“已实现”，并补上实测命令与结果。

相关契约：[phase-1-api.md](../api/phase-1-api.md) 第 5、6 节；需求：[一阶段需求文档.md](../../一阶段需求文档.md) 里程碑 3。

---

## 1. 阅读范围与对应代码

| 职责 | 文件 |
| --- | --- |
| 创建/读会话 HTTP | [ConversationController.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationController.java) |
| 发送/历史 HTTP | [MessageController.java](../../src/main/java/yangsirly/rag_agent/chat/MessageController.java) |
| 会话业务（含里程碑 4 方法占位） | [ConversationService.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationService.java) |
| 发送 + 历史业务（核心闭环） | [MessageService.java](../../src/main/java/yangsirly/rag_agent/chat/MessageService.java) |
| 表映射 | [ConversationEntity.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationEntity.java)、[MessageEntity.java](../../src/main/java/yangsirly/rag_agent/chat/MessageEntity.java) |
| 持久化 | [ConversationMapper.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationMapper.java)、[MessageMapper.java](../../src/main/java/yangsirly/rag_agent/chat/MessageMapper.java) |
| 异常与映射 | [ConversationNotFoundException.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationNotFoundException.java)、[IdempotencyConflictException.java](../../src/main/java/yangsirly/rag_agent/chat/IdempotencyConflictException.java)、[ChatExceptionHandler.java](../../src/main/java/yangsirly/rag_agent/chat/ChatExceptionHandler.java) |
| 时钟 Bean | [ChatConfiguration.java](../../src/main/java/yangsirly/rag_agent/chat/ChatConfiguration.java) |
| MySQL 迁移 | [V2__create_conversations_and_messages.sql](../../src/main/resources/db/migration/V2__create_conversations_and_messages.sql) |
| H2 测试表 | [schema.sql](../../src/test/resources/schema.sql) |
| 身份来源（复用里程碑 2） | [AuthenticatedUser.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthenticatedUser.java) |

DTO / Command 同包：`CreateConversationRequest`、`SendMessageRequest`、`SendMessageCommand`、`ConversationResponse`、`MessageView`、`SendMessageResponse`、`MessageListResponse` 等。

### 1.1 适用版本

与 [README.md](./README.md) 技术基线一致：Spring Boot 4.1.0、MyBatis-Plus 3.5.17、Flyway + MySQL、测试库 H2、Java 25。

---

## 2. 当前实现范围

### 2.1 已落地的工程能力

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 包分层与 Bean 注册 | 已接通 | Controller / Service / Mapper / ExceptionHandler / Clock |
| 表结构契约 | 已写入迁移 | conversations、messages、唯一约束、CHECK、ON DELETE CASCADE |
| HTTP 路径 | 已注册 | 见下表；业务未实现时 501 |
| 错误码类型 | 已预留 | `NOT_FOUND`、`IDEMPOTENCY_CONFLICT`、`INVALID_*`、骨架 `NOT_IMPLEMENTED` |
| 身份边界 | 已约定 | userId 只来自 `Authentication` 的 `AuthenticatedUser`，不信任 body |
| 上下文加载 | 已验证 | `RagAgentApplicationTests` 通过（2026-07-25） |

### 2.2 路由一览（契约路径已挂上）

| 方法 | 路径 | 里程碑 | 骨架行为 |
| --- | --- | --- | --- |
| POST | `/conversations` | 3 | 501 TODO |
| GET | `/conversations/{id}` | 3 | 501 TODO |
| POST | `/conversations/{id}/messages` | 3（核心） | 501 TODO |
| GET | `/conversations/{id}/messages` | 3 | 501 TODO |
| GET | `/conversations` | 4 | 501 占位 |
| PATCH | `/conversations/{id}` | 4 | 501 占位 |
| DELETE | `/conversations/{id}` | 4 | 501 占位 |

匿名访问以上路径仍由里程碑 2 的过滤器链返回 `401 UNAUTHORIZED`（需登录）。

### 2.3 明确未实现（实现时不要从“路由存在”推断已可用）

- `ConversationService.create` / `getOwned` / `normalizeTitle` 方法体  
- `MessageService.send` / `listMessages` 及幂等、并发冲突处理  
- 首次 201 / 幂等 200 的真实响应体  
- 消息历史“最新页优先”查询  
- 会话列表、改标题、物理删除（里程碑 4）  
- 聊天相关集成测试与 MySQL 上的 V2 实测  

### 2.4 目标外部行为（实现完成后的验收，非当前事实）

```text
Given 用户已登录
When  创建会话并发送一条带 clientMessageId 的消息
Then  库中有 1 条 USER + 1 条 ASSISTANT（模板文案固定）
And   响应同时返回两条消息；首次 HTTP 201
When  用相同 clientMessageId + 相同 content 重试
Then  返回相同消息 id；HTTP 200；库中消息数不增加
When  相同 clientMessageId + 不同 content
Then  409 IDEMPOTENCY_CONFLICT
When  访问他人会话
Then  404 NOT_FOUND（不暴露是否存在）
```

模板固定文案（与契约一致）：

```text
已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。
```

---

## 3. 目标调用链（实现后应长这样）

### 3.1 创建会话

```text
POST /conversations  { "title"? }
  → JwtAuthenticationFilter 写入 AuthenticatedUser
  → ConversationController
       userId ← authentication.principal（禁止 body 自报身份）
  → ConversationService.create  @Transactional
       ├─ normalizeTitle（缺省 →「新会话」；1～100 字）
       ├─ ConversationMapper.insert(userId, title)
       └─ ConversationView
  → 201 ConversationResponse（id 为十进制字符串）
```

### 3.2 发送消息（闭环核心）

```text
POST /conversations/{conversationId}/messages
     { clientMessageId, content }
  → MessageController
       SendMessageCommand(userId, conversationId, clientMessageId, content)
  → MessageService.send  @Transactional   ← 方案 A：整段同一事务
       │
       ├─ 1. 校验 UUID / 正文长度（codePointCount 1～10000）
       ├─ 2. findByIdAndUserId → 无则 ConversationNotFoundException
       ├─ 3. 按 clientMessageId 预查 USER
       │     ├─ 命中且 content 相同 → 加载 ASSISTANT → created=false（200）
       │     └─ 命中且 content 不同 → IdempotencyConflictException（409）
       ├─ 4. insert USER
       │     └─ DuplicateKeyException（并发）→ 再查并走幂等命中路径
       ├─ 5. insert ASSISTANT(reply_to = USER.id, TEMPLATE_REPLY)
       ├─ 6. 更新 conversation.updatedAt
       └─ 7. created=true → HTTP 201 + 两条 MessageView
```

### 3.3 历史消息

```text
GET /conversations/{id}/messages?page=0&size=50
  → 所有权校验（同上）
  → page=0 取最新一页；响应 items 内仍 createdAt ASC, id ASC
  → MessageListResponse
```

---

## 4. 信任边界与不变量

**信任边界**

- 客户端可提交 `title`、`content`、`clientMessageId`、路径上的 `conversationId`。  
- **不可信**：客户端声称的 userId / role；路径 id 是否属于自己。  
- 身份只来自 Cookie JWT → `AuthenticatedUser`（见 [milestone-02-authentication.md](./milestone-02-authentication.md)）。

**不变量（实现时必须满足）**

1. 用户只能读写**自己的**会话；他人资源对外统一 `404 NOT_FOUND`。  
2. 同一会话内一个 `clientMessageId` 最多对应 **一条 USER + 一条 ASSISTANT**。  
3. 幂等重试：`clientMessageId` + **完全相同** `content` 才复用；内容不同 → `409 IDEMPOTENCY_CONFLICT`，禁止静默覆盖。  
4. 一次发送成功后，不能只留下 USER 而没有 ASSISTANT（第一阶段靠单事务保证）。  
5. 消息 `role` 是 `USER`/`ASSISTANT`，与用户角色 `CUSTOMER`/`EDITOR` 无关。

**失败路径（契约）**

| 场景 | HTTP | code |
| --- | --- | --- |
| 未登录 | 401 | `UNAUTHORIZED` |
| 标题/正文/UUID/分页非法 | 400 | `INVALID_CONVERSATION_REQUEST` 或 `INVALID_MESSAGE_REQUEST` |
| 路径 id 非 long | 400 | `INVALID_PATH_PARAMETER` |
| 会话不存在或非本人 | 404 | `NOT_FOUND` |
| 幂等键内容冲突 | 409 | `IDEMPOTENCY_CONFLICT` |
| 骨架未实现（当前） | 501 | `NOT_IMPLEMENTED` |

---

## 5. 数据模型：为什么这样建表

### 5.1 conversations

```text
id, user_id, title, created_at, updated_at
索引 (user_id, updated_at, id)  → 里程碑 4 会话列表
外键 user_id → users(id)
```

- 会话必须归属用户；列表按最近活跃排序依赖 `updatedAt`（发送消息后会刷新）。  
- 第一阶段**物理删除**会话；消息靠外键级联删除，避免孤儿行。

### 5.2 messages

```text
id, conversation_id, role, content,
client_message_id,   -- 仅 USER
reply_to_message_id, -- 仅 ASSISTANT
created_at
```

关键约束：

| 约束 | 作用 |
| --- | --- |
| `UNIQUE (conversation_id, client_message_id)` | 并发幂等的**最终保证** |
| `UNIQUE (reply_to_message_id)` | 一条 USER 最多一条回复 |
| `CHECK` 角色字段形态 | USER 必须有 client_message_id；ASSISTANT 必须有 reply_to |
| `ON DELETE CASCADE` | 删会话时删消息 |

**为什么 ASSISTANT 的 `client_message_id` 为 NULL 不会撞唯一索引？**  
MySQL 唯一索引允许多个 NULL（NULL 不与 NULL 相等）。因此只有 USER 行参与幂等键冲突。

**为什么 content 用 TEXT？**  
utf8mb4 下 `VARCHAR(10000)` 可能触达行长度限制；第一阶段正文上限 10000 字，用 TEXT 更稳妥。

### 5.3 与“先查后写”的关系

```text
仅业务层：if not exists then insert
  → 两个请求同时通过 exists 检查
  → 可能插入两条

业务预查（快路径）+ DB 唯一约束（兜底）
  → 最多一个写入成功
  → 失败者捕获 DuplicateKeyException，再读已有消息对
```

注册模块对邮箱用同一思路（见 [milestone-01-registration.md](./milestone-01-registration.md)）。聊天把幂等键从“邮箱”换成了 `(conversation_id, client_message_id)`。

---

## 6. 事务：方案 A 与未来边界

### 6.1 第一阶段选择：单事务（方案 A）

```text
校验所有权与幂等
  → INSERT USER
  → INSERT ASSISTANT
  → UPDATE conversations.updated_at
全部在同一个 @Transactional 方法中
```

**优点**

- 不会出现“只有用户消息、没有模板回复”的半成品。  
- 超时重试不会重复生成一对消息（配合唯一约束）。  
- 无外部 IO，事务持有时间短，第一阶段简单。

**主要代价 / 已知边界**

- 将来接入**真实大模型**时，不能把 HTTP 事务开到模型返回为止（连接占用、超时、部分失败难恢复）。  
- 届时应改为：先落 USER + 处理中状态，异步生成 ASSISTANT，或拆成多步状态机（本阶段**明确不做**）。

### 6.2 技术决策一句话（面试可用）

> 当前模板回复没有外部调用，因此用单事务保证两条消息一致；接入真实模型后改为异步状态或拆分事务。

### 6.3 事务失败时发生什么

- 任一步异常且未捕获 → 整段回滚 → 客户端可安全用**同一** `clientMessageId` 重试。  
- 若 USER 已提交而 ASSISTANT 在**另一事务**失败（错误拆分时）→ 需要补偿或修复任务；方案 A 故意避免这种状态。

---

## 7. 幂等：`clientMessageId` 语义

### 7.1 客户端职责

- 一次“用户点击发送”生成一个 UUID，写入 `clientMessageId`。  
- 网络超时、连接中断后的**重试必须复用**该值，不能 `uuid()` 再生成一个。  
- 前端防抖只能改善体验，**不能**替代服务端唯一约束。

### 7.2 服务端三种结果

| 库中状态 | 请求 content | 结果 |
| --- | --- | --- |
| 无此 clientMessageId | 合法 | 首次写入，201 |
| 已有，content 相同 | 相同 | 返回原消息对，200 |
| 已有，content 不同 | 不同 | 409 `IDEMPOTENCY_CONFLICT` |

### 7.3 并发双发

```text
请求 A 与请求 B 同时：同一 conversationId + 同一 clientMessageId
  → 两者都可能预查未命中
  → 两者都尝试 INSERT USER
  → 数据库只让一个成功
  → 另一个 DuplicateKeyException
  → 再查已有对，走 200 幂等路径（content 一致时）
```

实现时注意：捕获 `DuplicateKeyException` 后要确认约束名是 `uk_messages_conversation_client_message`（与注册模块解析 `uk_users_email` 同模式），避免把无关唯一冲突误当成幂等命中。

### 7.4 content 比较要注意什么

契约要求：幂等重试时 content 与首次**完全一致**。  
校验“是否为空”用 `strip()` 判断纯空白；**持久化保存用户原始 content**（可含首尾空白）。  
因此比较幂等是否冲突时，应对**原始字符串**做 `equals`，而不是先 strip 再比（否则首次带空格、重试 strip 后相同会误判）。

---

## 8. 所有权与 404 伪装

```java
// ConversationMapper 骨架已提供
findByIdAndUserId(id, userId)
```

查询条件**同时**带 `id` 与 `userId`：

- 找不到：会话不存在 **或** 属于别人 → 同一 `ConversationNotFoundException` → `404 NOT_FOUND`。  
- 不要先 `findById` 再 `if (owner != me) throw 403`，否则“资源存在但属于他人”与“不存在”的响应时间/码差异可能被用来枚举。

与角色不足的 `403 FORBIDDEN`（如 CUSTOMER 调知识库）区分：  
**资源级不可见**用 404；**已可见资源上的操作权限不足**用 403。

---

## 9. 消息历史分页语义

第一阶段用简单 offset 分页（非游标）：

| 参数 | 默认 | 限制 |
| --- | --- | --- |
| page | 0 | ≥ 0 |
| size | 消息列表 50 | 1～100 |

**“最新页优先”**

```text
page=0 → 时间上最新的 size 条
page=1 → 再早的 size 条
每页返回前：按 createdAt ASC, id ASC 排好，方便前端从上到下渲染
前端加载更旧页时：把新页整页插到当前列表顶部
```

代价：发送新消息时，较早 page 可能发生偏移；第一阶段接受。长会话/真实模型阶段再考虑游标。

索引支持：`idx_messages_conversation_created (conversation_id, created_at, id)`。

---

## 10. 分层与模块协作

```text
MessageController / ConversationController
        │  HTTP、路径 id 解析、组装 ResponseEntity
        ▼
MessageService / ConversationService
        │  校验、事务、幂等、所有权
        ▼
MessageMapper / ConversationMapper  →  MySQL / H2
```

- **不**在 Controller 写业务规则；**不**在 Mapper 写“是否本人”之外的复杂策略。  
- Request → Command：避免 Service 依赖 Web 注解模型。  
- `Clock` 注入：便于测试固定时间，避免业务里散落 `LocalDateTime.now()`。  
- 异常处理器 `assignableTypes` 限定聊天 Controller，避免冲掉注册/登录的 `INVALID_*_REQUEST`（同里程碑 1/2 做法）。

与认证模块：

- 聊天接口默认 `authenticated()`（[SecurityConfiguration](../../src/main/java/yangsirly/rag_agent/authentication/SecurityConfiguration.java)）。  
- 不新增 permitAll。  
- 主体类型约定为 `AuthenticatedUser`。

---

## 11. 实现清单（把 TODO 填实的推荐顺序）

按可验证切片推进，而不是一次写完所有方法：

1. **`normalizeTitle` + `ConversationService.create`**  
   - 缺省标题、长度校验、insert、201 响应  
2. **`getOwned`**  
   - 本人 200 / 他人或缺失 404  
3. **`validateClientMessageId` / `validateContent` + `MessageService.send` 首次路径**  
   - 无并发、无重试：USER + ASSISTANT + updatedAt + 201  
4. **幂等命中路径**  
   - 相同 id 相同 content → 200；不同 content → 409  
5. **并发 DuplicateKey 路径**  
   - 双请求同 id（可用并行测试或脚本）  
6. **`listMessages`**  
   - 最新页优先 + 页内正序  
7. **集成测试**  
   - 登录 Cookie → 创建 → 发送 → 重试 → 历史 → 越权 404  
8. **里程碑 4**  
   - list / rename / delete  

实现完成后：删除或收窄 `ChatExceptionHandler` 对 `UnsupportedOperationException` → 501 的映射，避免掩盖真 bug。

---

## 12. 验证

### 12.1 骨架阶段已执行（2026-07-25）

```powershell
./mvnw.cmd -q test "-Dtest=RagAgentApplicationTests"
```

结果：通过。说明：H2 能建 `conversations`/`messages`，聊天相关 Bean 可注入，无循环依赖。

### 12.2 业务实现后建议补充

```powershell
# 示例：待新增测试类名可按项目习惯调整
./mvnw.cmd test "-Dtest=MessageServiceTests,ChatControllerTests,RagAgentApplicationTests"
```

至少覆盖：

- 创建会话 + 发送 → 两条消息 + 模板文案  
- 相同 `clientMessageId` 重试 → id 不变、行数不增  
- 相同 id 不同 content → 409  
- 他人 `conversationId` → 404  
- 匿名 → 401  
- 非法 UUID / 空正文 → 400  

有条件时再在本机 MySQL 跑 Flyway V2，确认 CHECK / 唯一约束 / 级联删除与 H2 行为一致。

---

## 13. 常见误区

| 误区 | 正确理解 |
| --- | --- |
| 路由在了就能聊天 | 当前业务是 TODO，真实行为是 501 |
| 先 select 再 insert 就够幂等 | 并发下必须靠唯一约束 |
| 重试时换新 UUID | 会当成新消息，破坏“一次发送”语义 |
| 他人会话返回 403 | 本项目资源级统一 404，降低枚举 |
| USER 角色和 CUSTOMER 搞混 | 消息角色 vs 用户角色是两套枚举 |
| 把模型调用放进同一 DB 事务 | 第一阶段模板可以；真模型必须拆 |
| 只测单线程重试 | 漏掉并发双插，唯一约束分支从未走过 |

---

## 14. 面试问题（结合本骨架）

1. 为什么 `(conversation_id, client_message_id)` 唯一，而不是全局唯一 `client_message_id`？  
2. 单事务写 USER+ASSISTANT 在什么前提下合理？接入流式大模型时你会怎么改状态机？  
3. 捕获 `DuplicateKeyException` 后为什么还要再查库，而不是直接对客户端报 409？  
4. 删除会话时消息如何保证不残留？应用层删和 `ON DELETE CASCADE` 各有什么风险？  
5. “最新页优先”和普通 `ORDER BY created_at DESC` 分页有何不同？前端应如何拼接？

---

## 15. 小实验（实现 send 之后再做）

1. 登录后创建会话，发送一条消息，用 SQL 确认 `messages` 两行的 `role` / `client_message_id` / `reply_to_message_id` 形态符合 CHECK。  
2. 用同一 `clientMessageId` 再 POST 一次，观察 HTTP 从 201 变为 200，且 `id` 不变。  
3. 故意改 content 再 POST，确认 409，且库中仍只有一对消息。  
4. （可选）两个线程同时发送同一 id，确认最终只有一对消息、无 500。

---

## 16. 与前后端契约的关系

- 前端 Mock 与类型已按 [phase-1-api.md](../api/phase-1-api.md) 编写（见 `frontend/src/features/chat`）。  
- 后端骨架路径与字段名对齐契约；**在业务 TODO 完成前，真实后端不能替代 MSW Mock 做联调成功路径**。  
- 实现时若调整默认标题、模板文案或分页默认值，必须同步契约与前端 schema。

---

## 17. 状态变更记录

| 日期 | 状态 | 说明 |
| --- | --- | --- |
| 2026-07-25 | 骨架已落地 | 包、V2、路由、异常、流程 TODO；上下文测试通过；业务未实现 |
| （待填） | 业务已实现 | send/list/create 完成并有集成测试后更新本文第 2、12 节 |
