# 里程碑 03：聊天闭环

本文对应聊天垂直切片。它从「用户点击发送」这个具体动作出发，沿着真实代码走一遍：请求如何变成命令、一次发送如何在单个事务里落下一对消息、`clientMessageId` 如何在超时重试和并发双发下保持"一次发送只留一对消息"、以及异常如何映射成契约里的 HTTP 错误码。

> **项目事实（截至 2026-08-21）**
> 里程碑 3 的业务代码已落地：`ConversationService.create` / `getOwned`、`MessageService.send` / `listMessages` 均有完整实现，不再是骨架期的 TODO。里程碑 4 的列表 / 改标题 / 删除仍是占位方法（抛 `UnsupportedOperationException` → 501）。
> **聊天模块目前没有任何自动化测试**：`src/test` 下不存在聊天相关测试类，`send` / `listMessages` 的行为从未在测试中执行过；Flyway `V2` 迁移也未在本机 MySQL 上实测（测试路径使用 H2 的等价 `schema.sql`）。本文区分"代码事实"、"契约要求"和"待验证推断"，凡未执行的实验都明确标注。

相关契约：[phase-1-api.md](../api/phase-1-api.md) 第 5、6 节；需求：[一阶段需求文档.md](../../一阶段需求文档.md) 里程碑 3。

---

## 1. 阅读范围与对应代码

| 职责 | 文件 |
| --- | --- |
| 创建/读会话 HTTP | [ConversationController.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationController.java) |
| 发送/历史 HTTP | [MessageController.java](../../src/main/java/yangsirly/rag_agent/chat/MessageController.java) |
| 会话业务（含里程碑 4 占位） | [ConversationService.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationService.java) |
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

与 [README.md](./README.md) 技术基线一致：Spring Boot 4.1.0、MyBatis-Plus 3.5.17、Flyway + MySQL Connector/J、测试库 H2（MODE=MySQL）、Java 25。框架或数据库升级后，涉及隔离级别、异常翻译和约束行为的结论应重新验证。

---

## 2. 当前实现范围

### 2.1 路由与真实行为（2026-08-21 核对）

| 方法 | 路径 | 当前行为 |
| --- | --- | --- |
| POST | `/conversations` | **已实现**：创建会话，201；标题非法 400 |
| GET | `/conversations/{id}` | **已实现**：本人 200；不存在或非本人 404 |
| POST | `/conversations/{id}/messages` | **已实现**：首次 201；幂等重试 200；内容冲突 409；会话不可见 404 |
| GET | `/conversations/{id}/messages` | **已实现**：200，最新页优先、页内正序 |
| GET | `/conversations` | 里程碑 4 占位 → 501 |
| PATCH | `/conversations/{id}` | 里程碑 4 占位 → 501 |
| DELETE | `/conversations/{id}` | 里程碑 4 占位 → 501 |

匿名访问以上任何路径由里程碑 2 的过滤器链拦截，返回 `401 UNAUTHORIZED`（[SecurityConfiguration.java](../../src/main/java/yangsirly/rag_agent/authentication/SecurityConfiguration.java) 的 `anyRequest().authenticated()`）。

注意：上表的"已实现"指**代码存在且上下文能装配**；由于没有聊天测试，这些分支从未被自动化验证过（见第 10 节）。

### 2.2 明确未实现 / 未验证

- 聊天自动化测试：Service 单元测试、HTTP 集成测试均不存在
- 本机 MySQL 上执行 Flyway V2，核对 CHECK / 唯一约束 / 级联删除与 H2 行为一致
- 并发双发同一 `clientMessageId` 的真实触发（包括第 4.6 节列出的隔离级别风险）
- 里程碑 4：会话列表、改标题、物理删除

### 2.3 目标外部行为（契约验收，待测试固化）

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

模板固定文案（与契约一致，`MessageService.TEMPLATE_REPLY`）：

```text
已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。
```

---

## 3. 数据模型：两张表如何承载一次对话

### 3.1 conversations

```text
id, user_id, title, created_at, updated_at
KEY idx_conversations_user_updated (user_id, updated_at, id)  -- 里程碑 4 会话列表
FK user_id → users(id)
```

- 会话必须归属用户；列表按最近活跃排序依赖 `updated_at`（发送消息后刷新）。
- 第一阶段**物理删除**会话；消息靠外键级联删除，避免孤儿行。

### 3.2 messages

```text
id, conversation_id, role, content,
client_message_id,   -- 仅 USER
reply_to_message_id, -- 仅 ASSISTANT
created_at
```

| 约束 | 作用 |
| --- | --- |
| `UNIQUE (conversation_id, client_message_id)` | 并发幂等的**最终保证**（第 4 节展开） |
| `UNIQUE (reply_to_message_id)` | 一条 USER 最多一条回复 |
| `ck_messages_role_fields` CHECK | 固化角色字段形态：USER 必须有 `client_message_id` 且无 `reply_to`；ASSISTANT 反之 |
| `ON DELETE CASCADE` | 删会话时删消息 |

三个值得注意的设计细节：

**① 为什么 ASSISTANT 的 `client_message_id` 为 NULL 不会撞唯一索引？**
MySQL 唯一索引中 NULL 不参与唯一性判定（SQL 里 `NULL = NULL` 结果是 NULL，不算相等）。因此多条 ASSISTANT 行的 `client_message_id = NULL` 可以共存，同样所有 USER 行的 `reply_to_message_id = NULL` 也不冲突。分工是：**CHECK 约束管字段形态，唯一索引管数量上限**。

**② 为什么 content 用 TEXT？**
utf8mb4 下 `VARCHAR(10000)` 可能触达 InnoDB 行长度限制；正文上限 10000 字（按 Unicode 码点计），用 TEXT 更稳妥。

**③ `updated_at` 有两套更新机制并存。**
迁移脚本写了 `ON UPDATE CURRENT_TIMESTAMP(6)`（数据库自动刷新），同时 `MessageService.send` 第 6 步又显式 `setUpdatedAt(now)` 后 `updateById`。显式赋值优先于 `ON UPDATE`（后者只在语句未给该列赋值时生效）。显式控制的价值：时间来自注入的 `Clock`，测试可以固定"现在"；代价是两套机制并存，读代码时容易误以为只有数据库在维护。

### 3.3 H2 测试表的对齐方式

[schema.sql](../../src/test/resources/schema.sql) 按 H2 语法简化（无 UNSIGNED / COMMENT，TEXT → CLOB），但**保留了全部唯一约束、CHECK 和级联删除的名字与语义**。这使依赖约束名的代码（如 `isClientMessageConflict`）在测试库和生产库行为一致。真正的 MySQL 迁移尚未实测，属于待办。

---

## 4. 原理一：`clientMessageId` 幂等——三态语义与并发兜底

这是本里程碑最核心的原理。

### 4.1 项目现象与问题

用户点击"发送"后，网络可能超时、连接可能中断。前端重试时，服务端如何避免把同一条消息存两次？

- 如果重试被当成新消息 → 历史里出现两对一模一样的消息；
- 如果简单拒绝重复 → 无法区分"网络重试"（应返回原结果）和"客户端 Bug 复用了 ID"（应报错）。

契约因此规定了**三种结局**：首次写入 201、相同内容重试 200 返回原消息对、相同 ID 不同内容 409。本项目用一个数据库唯一约束加一段业务比较实现了这三态。

### 4.2 具体写法与调用链

入口校验分两层（[SendMessageRequest.java](../../src/main/java/yangsirly/rag_agent/chat/SendMessageRequest.java) + [MessageService.java](../../src/main/java/yangsirly/rag_agent/chat/MessageService.java)）：

```java
// 结构层：Bean Validation 只管"有没有"
public record SendMessageRequest(
        @NotBlank String clientMessageId,
        @NotBlank String content) {
}

// 业务层：Service 校验"形不对不对"
static void validateClientMessageId(String clientMessageId) {
    ...
    try {
        UUID.fromString(clientMessageId);          // 必须是标准 UUID 形态
    } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException("clientMessageId must be a valid UUID string", ex);
    }
}
```

`send()` 的幂等相关步骤：

```java
// --- 3. 幂等预查（快速路径；并发下仍可能双双未命中，靠唯一约束兜底）---
MessageEntity existingUser = messageMapper.findUserMessageByClientMessageId(conversationId, clientMessageId);
if (existingUser != null) {
    return resolveExistingPair(existingUser, content);
}

// --- 4. 首次写入 USER ---
try {
    messageMapper.insert(userEntity);
}
catch (DuplicateKeyException ex) {
    if (isClientMessageConflict(ex)) {             // 确认撞的是幂等键约束
        // 并发下另一事务已提交：再查并走幂等命中路径
        MessageEntity raced = messageMapper.findUserMessageByClientMessageId(conversationId, clientMessageId);
        if (raced == null) {
            throw new IllegalStateException(
                    "Duplicate clientMessageId conflict but USER message was not found", ex);
        }
        return resolveExistingPair(raced, content);
    }
    throw ex;
}
```

命中后的判定（`resolveExistingPair`）：

```java
// content 与首次必须完全一致（原始串 equals，不先 strip）
if (!existingUser.getContent().equals(requestedContent)) {
    throw new IdempotencyConflictException();      // → 409 IDEMPOTENCY_CONFLICT
}
MessageEntity assistant = messageMapper.findAssistantByReplyTo(existingUser.getId());
if (assistant == null) {
    throw new IllegalStateException("USER message without ASSISTANT reply");
}
return new SendResult(false, toView(existingUser), toView(assistant));  // created=false → HTTP 200
```

对应的 Mapper 查询都限定在会话内：

```java
// MessageMapper：conversation_id + client_message_id + role 三条件
selectOne(Wrappers.<MessageEntity>lambdaQuery()
        .eq(MessageEntity::getConversationId, conversationId)
        .eq(MessageEntity::getClientMessageId, clientMessageId)
        .eq(MessageEntity::getRole, MessageRole.USER));
```

### 4.3 底层执行原理

**(a) 为什么"先查后写"不够。**
查询结果只代表查询时刻。两个并发请求都可能预查未命中，然后都尝试 INSERT。竞态窗口的分析见 [milestone-01-registration.md](./milestone-01-registration.md) 第 10.1 节（注册邮箱的同一问题），这里不重复。

**(b) InnoDB 唯一索引如何裁决并发插入。**
两个事务插入同一个 `(conversation_id, client_message_id)` 键时，后到者的 INSERT 会在唯一键冲突检测处等待先到者的事务结果：先到者提交 → 后到者收到 duplicate key 错误；先到者回滚 → 后到者插入成功。无论多少并发，**最终库里最多一行**。这就是注释里"快速路径 + 兜底"的含义：预查只是省掉无谓异常的优化，约束才是正确性的来源。

**(c) `DuplicateKeyException` 是怎么出现的。**
链路是：MySQL 返回错误 1062（SQLState 23000，消息文本含约束名，如 `Duplicate entry '...' for key 'uk_messages_conversation_client_message'`）→ JDBC 驱动包成 `SQLException` → MyBatis 包成 `PersistenceException` → MyBatis-Spring 的异常翻译器把它翻译成 Spring `DataAccessException` 体系中的 `org.springframework.dao.DuplicateKeyException`。关键限制：**Spring 的异常对象没有结构化的"约束名"字段**，约束名只藏在异常链某层的 message 文本里，所以 `isClientMessageConflict` 要沿 `getCause()` 链逐层找字符串。这与注册模块解析 `uk_users_email` 是同一模式（见 [milestone-01-registration.md](./milestone-01-registration.md) 第 10.4 节）。

**(d) 为什么幂等键的作用域是"会话内"。**
唯一约束是 `(conversation_id, client_message_id)` 而不是全局 `client_message_id`。UUID 碰撞概率可以忽略，但约束的作用不只是防碰撞：它表达了业务不变量"**同一会话内**一次发送最多一对消息"。全局唯一会把不同会话合法复用的 UUID（例如客户端按"对话轮次"生成 ID）误判成冲突。

**(e) content 比较为什么用原始串。**
`validateContent` 用 `strip()` 只判断"是否纯空白"；持久化保存用户原始输入（可含首尾空白）。`resolveExistingPair` 因此也对**原始串**做 `equals`。如果保存原文却用 strip 后的值比较，"首次带空格、重试不带"会被误判成内容冲突（409），违反契约"完全一致才复用"。

### 4.4 知识点与应用对照

| 知识点 | 项目位置 | 触发方式 | 可观察结果 | 换种写法的后果 |
| --- | --- | --- | --- | --- |
| 唯一约束作并发兜底 | V2 `uk_messages_conversation_client_message` + `send` 的 catch 分支 | 并发双发同一 ID | 仅一对消息，败者走 200 | 只靠预查 → 双写或 500 |
| 幂等键作用域 = 会话 | 约束列组合 + Mapper 三条件查询 | 不同会话用同一 UUID | 互不干扰，各自 201 | 全局唯一 → 跨会话误判 409 |
| 三态判定（201/200/409） | `resolveExistingPair` 的 content 比较 | 相同/不同 content 重试 | 200 原 id / 409 | 缺比较 → 静默返回旧消息 |
| 约束名甄别 | `isClientMessageConflict` 沿异常链找 `uk_messages_conversation_client_message` | 任何唯一冲突发生 | 只有幂等键冲突走重试路径 | 一律当幂等命中 → 掩盖其他数据错误 |
| NULL 不参与唯一性 | V2 注释 + 3.2 节① | 插入 ASSISTANT 行 | 不触发幂等键冲突 | 若强制非空 → 每条回复都要伪造 ID |
| UUID 形态校验 | `validateClientMessageId` | 提交非 UUID 字符串 | 400 `INVALID_MESSAGE_REQUEST` | 直接入库 → CHAR(36) 报错或截断 |

### 4.5 换种写法会怎样

- **删掉 try/catch（保留约束）**：并发败者的 `DuplicateKeyException` 无人处理 → 用户看到 500 而不是 200。数据仍然正确（约束兜住了），但体验和日志都变差。
- **连约束一起删**：真正的双写——历史接口出现两对相同消息，不变量被破坏且难以事后清理。
- **用 `INSERT IGNORE` / `ON DUPLICATE KEY` 替代**：会把"内容冲突"也吞掉，无法实现 409 语义；`IGNORE` 还会忽略其他错误，得不偿失。
- **先 strip 再比较 content**：首次 `" 你好 "`（存原文）、重试 `"你好"` → 判定不一致 → 误报 409。

### 4.6 验证实验

**现状：以下均未执行，属待办而非事实。**

自动化（建议新增测试类，命名可按项目习惯调整）：

```powershell
./mvnw.cmd test "-Dtest=MessageServiceTests,ChatControllerTests"
```

至少覆盖：首次 201 两对字段形态正确；同 ID 同 content 重试 200 且 id 不变；同 ID 不同 content 409；并发双发仅一对消息。

手工序列（登录后依次执行，配合 SQL 观察）：

```sql
SELECT id, role, client_message_id, reply_to_message_id FROM messages WHERE conversation_id = ?;
-- 预期：恰好两行，形态符合 ck_messages_role_fields
```

**⚠ 待验证的隔离级别风险（推断，非结论）**：MySQL 默认隔离级别是 REPEATABLE READ，普通 SELECT 读的是事务开始时建立的一致性快照。据此推断：若请求 B 的预查发生在请求 A 提交之前、而 B 的 INSERT 又因等待 A 而收到 duplicate key 错误，B 在 catch 后的**普通再查仍读旧快照**，可能拿不到 A 的行 → 走进 `IllegalStateException` → 500。H2 默认 READ COMMITTED，测试库上未必能复现。可选缓解：再查改用锁定读（`FOR SHARE`）、将该事务隔离级别设为 READ COMMITTED、或捕获后返回明确的"请重试"信号。此推断需要按第 8.2 节的并发实验验证后才能定论。

### 4.7 常见误区与面试问题

| 误区 | 正确理解 |
| --- | --- |
| 重试时换新 UUID | 新 ID = 新消息，破坏"一次发送"语义 |
| 先 select 再 insert 就够幂等 | 并发下必须靠唯一约束 |
| 捕获所有 `DuplicateKeyException` 当幂等命中 | 必须核对约束名，否则掩盖无关数据错误 |
| 只测单线程重试 | 并发双插分支从未走过，兜底逻辑等于没测 |
| USER 消息角色 ≡ CUSTOMER 用户角色 | `MessageRole` 描述发言方，用户角色描述权限，两套枚举 |

面试题：

1. 为什么唯一键是 `(conversation_id, client_message_id)` 而不是全局 `client_message_id`？
2. 捕获 `DuplicateKeyException` 后为什么要再查库，而不是直接对客户端报 200 或 409？
3. 两个请求并发携带同一 `clientMessageId` 但 content 不同，最终结果是什么？谁决定赢家？
4. 如果把预查去掉只留约束，系统行为有什么变化？反过来呢？

### 4.8 小实验

1. 把 `resolveExistingPair` 的 `equals` 改成先 `strip()` 再比较，构造"首次带首尾空格、重试不带"的请求，观察 409 误报；改回来确认恢复。
2. 删掉 `send` 里的 try/catch，用两个线程同时发同一 ID，观察败者得到 500 而非 200。
3. （验证 4.6 节风险）在本机 MySQL 上用两线程 + 人为延迟（如在预查后断点）复现"再查为 null"路径，确认或推翻 REPEATABLE READ 快照推断。

---

## 5. 原理二：单事务消息对（方案 A）

### 5.1 项目现象与问题

一次发送必须留下**成对**的消息：一条 USER、一条指向它的 ASSISTANT。如果写完 USER 后程序崩溃，库里就剩一条"没有回复的用户消息"——前端渲染断裂，幂等再查也会走进 `IllegalStateException`（`resolveExistingPair` 要求配对 ASSISTANT 存在）。第一阶段用**一个数据库事务包住全部写入**来排除这种半成品状态。

### 5.2 具体写法与调用链

```java
@Transactional
public SendResult send(SendMessageCommand command) {
    // 1. 校验入参（无 DB 操作）
    // 2. findByIdAndUserId 所有权校验（读）
    // 3. 幂等预查（读）
    // 4. insert USER          ← 事务从这里开始产生写入
    // 5. insert ASSISTANT(reply_to = USER.id)
    // 6. updateById 刷新 conversation.updatedAt
    // 7. return SendResult(created=true, ...)
}
```

三个写操作（4、5、6）在同一个事务里，要么全部提交，要么全部回滚。对比之下，`listMessages` 标注的是 `@Transactional(readOnly = true)`——纯读路径向 Spring 和驱动声明不会写入。

### 5.3 底层执行原理

代理如何开启/提交/回滚事务、"INSERT 已执行为何还能回滚"，已在 [milestone-01-registration.md](./milestone-01-registration.md) 第 9 节讲透，此处只讲**这个特定事务边界内的行为**：

- **回滚的触发条件是"异常穿出代理"**。方法内部任何未被捕获的运行时异常（`ConversationNotFoundException`、`IdempotencyConflictException`、`IllegalStateException`……）都会让拦截器回滚整个事务——包括早已执行成功的 `insert USER`。典型场景：第 5 步 ASSISTANT 插入意外失败 → 第 4 步的 USER 行一并消失，库里不留痕迹，客户端可用同一 `clientMessageId` 安全重试。
- **被 catch 住的异常不触发回滚**。第 4 步捕获 `DuplicateKeyException` 后代码继续走幂等命中路径，事务拦截器看不到这个异常。InnoDB 对 duplicate key 错误只做**语句级回滚**（失败的 INSERT 自己撤销），事务本身继续可用，最终正常提交（该路径没有任何写入残留）。
- **事务边界 = 方法边界**。校验、预查这些读操作也在事务内，多消耗一点事务时长，但换来"预查和写入看到同一份一致性视图"的简单性。

### 5.4 知识点与应用对照

| 知识点 | 项目位置 | 触发方式 | 可观察结果 | 换种写法的后果 |
| --- | --- | --- | --- | --- |
| 单事务原子性 | `send` 上的 `@Transactional` | 任一步失败 | 三次写入同生共死 | 拆开 → 可能只剩 USER 行 |
| 异常穿出代理才回滚 | 步骤 4 的 catch-and-continue | 并发 duplicate key | 败者正常返回 200 | 误以为 catch 也会回滚 → 设计出错误的补偿逻辑 |
| `readOnly = true` | `listMessages` / `getOwned` | 历史查询 | 声明意图；驱动可做读优化 | 读写不分 → 误用写连接做纯读 |
| 时间来自 `Clock` | `ChatConfiguration` 提供 `Clock.systemUTC()` | 测试固定时间 | 可复现的 createdAt/updatedAt | 散落 `LocalDateTime.now()` → 时间相关断言不可控 |

### 5.5 换种写法会怎样

- **去掉 `@Transactional`**：每条语句自动提交。在第 4、5 步之间注入故障（如让 ASSISTANT 插入违反 `uk_messages_reply_to`），库里就会留下一条永久孤儿 USER 消息——这正是 4.1 节描述的半成品。
- **把未来真实的模型 HTTP 调用放进这个事务**：大模型响应可能要数秒到数十秒，数据库连接和行锁全程被占用，连接池耗尽、超时连锁。所以类注释里写明：接入真实模型后必须改为异步状态或拆分事务（第一阶段**明确不做**，属于已知边界而非遗漏）。

一句话版本（面试可用）：**模板回复没有外部 IO，单事务保证两条消息一致；接入真实模型后改为异步状态机。**

### 5.6 验证实验

**未执行，待办。** 计划中的故障注入：在集成测试里临时于第 5 步前抛出运行时异常，断言 `messages` 表零残留；恢复后断言正常路径两行齐全。

### 5.7 常见误区与面试问题

- 误区："insert 执行了就是存进去了" —— 执行 ≠ 提交，回滚会撤销（详见里程碑 01 笔记第 9.2 节）。
- 误区："catch 了异常事务就不干净了" —— 只有穿出代理的异常才触发回滚。
- 面试：什么前提下"单事务写 USER+ASSISTANT"是合理的？接入流式大模型时你会怎么改状态机？

### 5.8 小实验

写一个（未来的）集成测试：Mock 或配置让第 5 步必然失败，断言 `messages` 表没有该 `client_message_id` 的任何行；随后用同一 ID 正常重发，确认 201 且两行齐全。

---

## 6. 原理三：所有权查询与 404 伪装

### 6.1 项目现象与问题

用户 A 拿着别人会话的 id 调接口，系统应该返回什么？如果对"存在但属于别人"和"不存在"给出不同响应（比如 403 vs 404），攻击者可以借此**枚举**哪些会话 id 存在。本项目统一返回 404。

### 6.2 具体写法与调用链

```java
// ConversationMapper：一条 SQL 同时带 id 和 userId
default ConversationEntity findByIdAndUserId(Long id, Long userId) {
    return selectOne(Wrappers.<ConversationEntity>lambdaQuery()
            .eq(ConversationEntity::getId, id)
            .eq(ConversationEntity::getUserId, userId));
}

// Service：null 统一抛 NotFound，不区分原因
ConversationEntity conversation = conversationMapper.findByIdAndUserId(conversationId, userId);
if (conversation == null) {
    throw new ConversationNotFoundException();     // → 404 NOT_FOUND
}
```

`send`、`listMessages`、`getOwned` 走的都是这一个入口。userId 来自 JWT 解出的 `AuthenticatedUser`（见 [milestone-02-authentication.md](./milestone-02-authentication.md)），不由请求体提供。

### 6.3 底层执行原理

`WHERE id = ? AND user_id = ?` 让"是否存在"和"是否属于你"在同一次查询、同一个响应里坍缩成一个结果。与之配套的是错误码语义的分工：**资源级不可见用 404**（你无权知道它存在与否）；**已可见资源上的操作权限不足才用 403**（如 CUSTOMER 调知识库管理接口，资源类别本身对角色可见）。

### 6.4 知识点与应用对照

| 知识点 | 项目位置 | 触发方式 | 可观察结果 | 换种写法的后果 |
| --- | --- | --- | --- | --- |
| 单查询所有权过滤 | `findByIdAndUserId` | 用他人 id 调任意接口 | 与不存在的 id 完全相同的 404 | 先查再判 → 响应差异泄露存在性 |
| 身份只取自 principal | Controller 的 `requireUser` + Command 组装 | 伪造 body 中的身份字段 | 无效：userId 来自 JWT | 信任 body → 水平越权 |

### 6.5 换种写法会怎样

先 `findById` 再 `if (owner != me) throw 403`：响应码（403 vs 404）甚至响应时间的细微差异都能被用来扫描存量会话 id。对面向 C 端的资源接口，这是常见的安全考点。

### 6.6 验证实验

**未执行，待办**：集成测试中用账号 B 的 Cookie 访问账号 A 创建的会话，断言 404 且响应体与"id 不存在"完全一致。

### 6.7 常见误区与面试问题

- 误区："别人的资源就该返回 403" —— 取决于资源可见性模型；私有资源用 404 防枚举。
- 面试：403 和 404 在授权设计里如何取舍？什么时候暴露"存在但无权"反而是对的（如协作文档的申请访问）？

### 6.8 小实验

用两个账号分别请求同一 `conversationId`，对比响应的状态码、body 和耗时；再把查询改成两步写法，观察差异。

---

## 7. 消息历史：最新页优先分页

### 7.1 项目写法

```java
// MessageMapper.pageNewestFirst：先取"时间上最新的一页"
.orderByDesc(MessageEntity::getCreatedAt)
.orderByDesc(MessageEntity::getId)
.last("LIMIT " + offset + ", " + size);

// MessageService.listMessages：内存中反转为正序再返回
List<MessageEntity> pageItems = new ArrayList<>(newestFirst);
Collections.reverse(pageItems);
```

语义（与契约 6.2 一致）：

```text
page=0 → 时间上最新的 size 条
page=1 → 再早的 size 条
每页 items 内部：createdAt ASC, id ASC（前端从上到下直接渲染）
前端加载更旧页：整页插到列表顶部
totalElements = countByConversationId；totalPages 向上取整
```

### 7.2 为什么这样写

- 聊天界面打开时默认展示**最新**消息，所以 page=0 必须锚定"最新"端，而不是最旧端。
- 排序键带上 `id` 作为 tie-breaker：同一时刻（`DATETIME(6)` 精度内）的多条消息顺序稳定，分页不重不漏。
- 复合索引 `idx_messages_conversation_created (conversation_id, created_at, id)` 同时覆盖过滤列和排序列，避免 filesort。
- `.last("LIMIT ...")` 是 MyBatis-Plus 拼接原生 SQL 片段的逃生口；这里 `offset`/`size` 是 Service 校验过的 int，无注入风险，但这个口子本身只应在这种受控场景使用。
- 代价：offset 分页在深页时要扫过并丢弃前面所有行，且新消息到达会使较早页整体偏移。第一阶段接受；长会话/真实模型阶段再考虑游标。

---

## 8. 错误映射：异常如何变成 HTTP 响应

### 8.1 映射表

[ChatExceptionHandler.java](../../src/main/java/yangsirly/rag_agent/chat/ChatExceptionHandler.java) 用 `@RestControllerAdvice(assignableTypes = {...})` 限定在两个聊天 Controller 上，避免覆盖注册/登录模块的同名错误码。

| 异常 | HTTP | code | 来源举例 |
| --- | --- | --- | --- |
| `MethodArgumentNotValidException` | 400 | `INVALID_MESSAGE_REQUEST` | `SendMessageRequest` 的 `@NotBlank` 失败 |
| `IllegalArgumentException`（消息含 "path parameter"） | 400 | `INVALID_PATH_PARAMETER` | `/conversations/abc` |
| `IllegalArgumentException`（消息含 "title"/"conversation"） | 400 | `INVALID_CONVERSATION_REQUEST` | 标题超长 |
| `IllegalArgumentException`（其余） | 400 | `INVALID_MESSAGE_REQUEST` | 正文超长、UUID 非法、分页参数越界 |
| `ConversationNotFoundException` | 404 | `NOT_FOUND` | 会话不存在或非本人 |
| `IdempotencyConflictException` | 409 | `IDEMPOTENCY_CONFLICT` | 幂等键复用于不同内容 |
| `UnsupportedOperationException` | 501 | `NOT_IMPLEMENTED` | 目前仅里程碑 4 占位方法 |

### 8.2 已知简化与边界

- **`IllegalArgumentException` 按异常 message 字符串分流错误码**，是代码注释里明说的临时做法（"实现阶段可引入专用异常类型以消除字符串判断"）。脆弱点：将来有人改了异常消息文案，错误码就会悄悄漂移。改进方向是引入专用异常类型，属于后续任务，不在本里程碑范围。
- `MethodArgumentNotValidException` 目前只会由 `SendMessageRequest` 触发（只有它挂了 `@Valid`），所以统一映射为消息错误码是安全的；会话创建的 title 校验发生在 Service，走的是 `IllegalArgumentException` 分支。
- 501 处理器的存在理由：把"未实现"与"实现 Bug"（500）区分开。里程碑 4 完成后应删除或收窄它，避免掩盖真 Bug。

---

## 9. 分层与协作边界

```text
MessageController / ConversationController
        │  HTTP、路径 id 解析、组装 ResponseEntity
        ▼
MessageService / ConversationService
        │  校验、事务、幂等、所有权
        ▼
MessageMapper / ConversationMapper  →  MySQL / H2
```

- Controller 不写业务规则；Request → Command 的转换让 Service 不依赖 Web 注解模型（四个类型各司其职的理由见 [milestone-01-registration.md](./milestone-01-registration.md) 第 4 节，聊天模块沿用同一模式，不再重复）。
- `Clock` 由 [ChatConfiguration.java](../../src/main/java/yangsirly/rag_agent/chat/ChatConfiguration.java) 提供（`Clock.systemUTC()`），`formatUtc` 把 `LocalDateTime` 按 UTC 墙钟格式化为 ISO-8601。两者必须成对理解：时钟是 UTC，序列化也按 UTC 解释，契约里的 `Z` 结尾时间戳才正确。
- 消息实体用静态工厂 `MessageEntity.userMessage(...)` / `assistantReply(...)` 强制角色与字段形态绑定，构造函数私有化后，代码层面造不出违反 CHECK 形态的对象。

---

## 10. 验证记录

### 10.1 已执行（2026-08-21）

```powershell
./mvnw.cmd test "-Dtest=RagAgentApplicationTests"   # 通过：上下文可加载，H2 建表成功，聊天 Bean 可注入
./mvnw.cmd test                                      # Tests run: 109, Failures: 0, Errors: 0, Skipped: 1
```

Skipped 的 1 个是 `MySqlRegistrationIntegrationTests`（需显式开启 MySQL Profile，属注册模块）。

**必须强调**：以上只能证明"代码能装配"。聊天业务的全部行为分支（201/200/409/404/400、并发兜底、分页语义）**没有任何自动化验证**，第 4～7 节描述的行为是"代码 + 契约推导出的预期"，不是已测事实。

### 10.2 待补验证清单

1. `MessageServiceTests`：首次路径、幂等命中、409、UUID/长度校验（可 Mock Mapper 或用 H2）
2. `ChatControllerTests`（HTTP 集成，H2）：登录 Cookie → 创建 → 发送 → 重试 → 历史 → 越权 404 → 匿名 401 → 非法入参 400
3. 并发双发测试（同 ID 同 content / 不同 content 各一组）
4. 本机 MySQL 执行 Flyway V2，核对 CHECK / 唯一约束 / 级联删除与 H2 一致
5. 第 4.6 节隔离级别风险的复现实验

---

## 11. 剩余工作清单（推荐顺序）

1. 补 `MessageServiceTests` + `ChatControllerTests`，固化第 2.3 节验收行为
2. 并发双发测试 + 隔离级别风险验证（必要时引入锁定读或 READ COMMITTED）
3. 本机 MySQL 实测 V2 迁移
4. 里程碑 4：list / rename / delete（完成后删除 501 处理器）
5. 引入专用校验异常类型，替换 `IllegalArgumentException` 字符串分流

---

## 12. 状态变更记录

| 日期 | 状态 | 说明 |
| --- | --- | --- |
| 2026-07-25 | 骨架落地 | 包、V2、路由、异常接通；业务方法体为 TODO（501） |
| 2026-08-21 | 业务实现核对 | `create/getOwned/send/listMessages` 已实现；按新版 AGENTS.md 第 2.5 节重写本文；全量测试通过但聊天专项测试仍缺；标记 REPEATABLE READ 再查风险为待验证 |
