# 里程碑 06：工业级高并发加固

> **项目事实与架构演进**
> 本里程碑完成了一次系统的工业级高并发加固：在保持“注册 → 登录 → 会话管理 → 消息收发与幂等”业务语义完全不变的前提下，将系统从传统的单机 `Spring Boot + MySQL + JWT Cookie` 架构，改造为支持多实例集群部署、具备高并发防御能力的生产级架构。
> 核心改进：引入 Redis（多实例共享限流 / 幂等快速拦截 / JWT 即时吊销黑名单 / 登录失败防爆破计数），数据库以“批量软删除”替代“外键级联硬删除”，划定细粒度编程式事务边界消除长连接占用，并提供全链路 TraceId 追踪。
> 本文严格对标项目统一规范的 8 段骨架，以**只有 Java 基础的开发者**为目标读者，从项目真实写法出发，逐层讲透框架、容器、JVM、Servlet、Spring、MyBatis-Plus、MySQL 与 Redis 的底层执行机制。

相关契约与需求：`docs/api/phase-1-api.md` §2.7 工业增补、`一阶段需求文档.md` 第二章 非功能需求。  
数据库迁移脚本：`V3__add_soft_delete_to_messages.sql`、`V4__add_industrial_columns.sql`、`V5__add_soft_delete_to_conversations.sql`。

---

## 1. 项目现象与问题：为什么要做这一轮高并发改造

很多初学 Java 的开发者在写单机 Web 应用时，往往习惯直接使用 `@Transactional` 包裹整个 Service 方法，利用 MySQL 的 `ON DELETE CASCADE` 级联删除子表，依靠简单的 `SELECT -> INSERT` 做查重，认为“功能跑通了就是完成了”。但在真实的生产环境和高并发流量下，这些看似省事的写法会引发严重的系统级崩溃。

### 1.1 改造前的 5 大核心痛点

1. **`ON DELETE CASCADE` 级联大事务拖垮数据库**  
   - **现象**：用户删除一个包含成千上万条消息的长会话时，MySQL 需要在同一个事务内同时给 `conversations` 表和 `messages` 表的大量数据行加排他锁（X锁）。
   - **后果**：事务持有锁的时间极长，容易引发锁等待超时（Lock wait timeout）和死锁（Deadlock），阻塞其他用户的正常读写；且大量日志瞬间涌入 binlog 和 undo log，造成主从复制延迟甚至数据库假死。
2. **JWT 令牌无法即时吊销（退出登录漏洞）**  
   - **现象**：JWT（JSON Web Token）是无状态的，退出登录接口 `/logout` 仅仅是通过设置 `Set-Cookie: maxAge=0` 让浏览器删除了本地 Cookie。
   - **后果**：如果攻击者或中间人在此前复制了该 Token，在 Token 自然过期（例如 30 分钟）之前，只要在请求头或脚本中携带该 Token，依然能随意调用 `/me` 或发送消息接口。在多实例集群下，服务端无法主动将某个已泄露的 Token 宣告作废。
3. **消息发送幂等性仅靠“先查后写”，并发下数据库被击穿**  
   - **现象**：客户端网络抖动超时进行重试，或者前端短时间内并发双发相同的 `clientMessageId`。旧代码在单事务内先执行 `SELECT` 查重，查不到再 `INSERT`。
   - **后果**：在并发双发时，两个线程同时执行 `SELECT` 都会发现“记录不存在”，随后双双执行 `INSERT`。虽然数据库唯一索引会拦截其中一个并抛出唯一键冲突，但如果异常处理不当，会产生 500 内部错误，且高并发请求全部直接打在 MySQL 上，没有前置拦截。
4. **无防刷保护，密码爆破与恶意请求直接穿透到 MySQL**  
   - **现象**：注册、登录和发消息接口没有任何速率限制；登录失败没有限制阈值。
   - **后果**：黑客可以使用脚本每秒发起数万次登录请求爆破用户密码。如果每次错误密码都直接执行 `UPDATE users SET failed_login_count = ...`，MySQL 磁盘 I/O 和行锁会被瞬间打满，导致整站瘫痪。
5. **线程池与连接池未做容量规划，长事务导致连接耗尽**  
   - **现象**：Tomcat 默认 200 个处理线程，而 HikariCP 数据库连接池默认或未精确规划；如果在标注了 `@Transactional` 的大方法里执行远程调用、Redis 访问或复杂业务校验，物理数据库连接会被一直霸占。
   - **后果**：少量并发请求就会把物理数据库连接池借光，导致其余线程全部在 `getConnection()` 上排队超时（`ConnectionTimeoutException`），发生全站雪崩。

---

## 2. 具体写法与调用链

### 2.1 数据层改造：双侧软删除与 RESTRICT 外键

数据层通过 Flyway 迁移脚本实现了从“物理级联删除”到“受控批量软删除”的演进：

| 文件 | 核心改动 | 解决的问题 / 设计意图 |
| --- | --- | --- |
| `V3__add_soft_delete_to_messages.sql` | `messages.deleted_at DATETIME(6) NULL` + 索引 `idx_messages_conversation_deleted`；外键改为 `ON DELETE RESTRICT` | 为消息增加软删除标记；阻止数据库底层自动级联硬删除。 |
| `V4__add_industrial_columns.sql` | `messages.status VARCHAR(20) DEFAULT 'DONE'` + CHECK 约束；`users.failed_login_count / lock_until` | 消息表预留异步状态字段；用户表增加防爆破锁定的最终持久化字段。 |
| `V5__add_soft_delete_to_conversations.sql` | `conversations.deleted_at DATETIME(6) NULL` + 覆盖索引 `idx_conversations_user_updated (user_id, deleted_at, updated_at)` | **修复 V3 遗留的致命 Bug**：若会话物理删除而消息软删除，软删的消息行仍引用会话 ID，RESTRICT 外键会直接阻止删会话抛 500。会话也软删后，物理行保留，外键始终完整。 |

#### 会话删除调用链（[ConversationService.java](../../src/main/java/yangsirly/rag_agent/chat/ConversationService.java:150)）

删除操作的方法签名上**坚决不加 `@Transactional`**，采用“分批独立小事务”策略：

```text
HTTP DELETE /conversations/{id}
   │
   ▼
ConversationService.delete(userId, conversationId)  [无 @Transactional]
   │
   ├─ 1. findByIdAndUserId 校验会话所有权 (deleted_at IS NULL)
   │
   ├─ 2. 循环分批软删消息：
   │     while (true) {
   │         Integer affected = transactionTemplate.execute(status ->
   │             messageMapper.softDeleteBatch(conversationId, now, 1000)
   │         ); // 单条 UPDATE ... LIMIT 1000，每批是一个独立提交的极短事务！
   │         if (affected < 1000) break;
   │     }
   │
   └─ 3. 会话软删：
         conversationMapper.softDeleteById(conversationId, now) // 单行 UPDATE，极短事务
```

> **为什么不能用一个 `@Transactional` 包住整个 `while` 循环？**  
> 如果外层加了 `@Transactional`，即使你在循环里写了分批更新，底层依然处于同一个数据库事务中。所有被更新的消息行锁都会一直持有，直到整个方法结束才会统一提交并释放锁。这就只是“把一条大 SQL 拆成了多条小 SQL”，完全没有达到“缩短持锁时间、释放数据库资源”的目的。

---

### 2.2 接口限流与 Servlet 过滤器链

系统针对不同维度的风险，在架构的不同层级布置了限流防护网：

| 维度 | 拦截位置 | 作用 Key | 默认阈值 | 为什么放在该层？ |
| --- | --- | --- | --- | --- |
| **IP 注册限流** | `RateLimitFilter` (Servlet 过滤器) | `register:ip:{ip}` | 10次 / 分钟 | 最外层拦截，未认证的刷频流量在进入 Spring Security 和 Controller 之前直接丢弃。 |
| **IP 登录限流** | `RateLimitFilter` (Servlet 过滤器) | `login:ip:{ip}` | 10次 / 分钟 | 保护认证接口不被同一 IP 的暴力脚本打满。 |
| **用户发消息限流** | `MessageService.send` (业务层) | `send:user:{userId}` | 20次 / 分钟 | 发消息需要先通过 JWT 认证拿到 `userId`，因此在业务 Service 内部执行。 |

```text
客户端 HTTP 请求
   │
   ▼ [HIGHEST_PRECEDENCE]
1. TraceIdFilter：提取或生成 16 位十六进制 traceId 注入 MDC 和响应头
   │
   ▼ [HIGHEST_PRECEDENCE + 1]
2. RateLimitFilter：检查 POST /register 与 POST /login 的 IP 限流
   │  └─ 若超限：直接写入 429 + Retry-After + X-RateLimit-Limit JSON 响应，中断请求链
   │
   ▼
3. Spring Security 过滤器链（JwtAuthenticationFilter 解析 JWT 并校验 Redis 黑名单）
   │
   ▼
4. DispatcherServlet -> Controller -> Service 业务逻辑
```

---

### 2.3 分布式消息发送与幂等时序

在 [MessageService.java](../../src/main/java/yangsirly/rag_agent/chat/MessageService.java:102) 中，发送逻辑将**校验与读操作移到事务外**，将**写入操作包裹在编程式事务中**：

```text
MessageService.send(command)  [方法本身无 @Transactional]
   │
   ├─ 1. 参数格式校验 (UUID 格式、文本长度)
   ├─ 2. 事务外执行用户限流检查: rateLimiter.tryAcquire("send:user:" + userId)
   ├─ 3. 事务外查询会话归属: conversationMapper.findByIdAndUserId()
   │
   ├─ 4. Redis 快路径拦截 (SET idmp:conv:{id}:client:{uuid} "1" NX EX 30s)
   │     └─ 若 key 已存在 (返回 false)：说明 30s 内已发过，直接查库返回 resolveExistingPair
   │
   ├─ 5. DB 幂等预查: messageMapper.findUserMessageByClientMessageId()
   │     └─ 若已存在：校验 content 是否一致，返回原消息对 (200) 或抛出 409 冲突
   │
   ├─ 6. 写路径（通过 TransactionTemplate 开启极小写事务）：
   │     transactionTemplate.execute(status -> {
   │         messageMapper.insert(userMessage);       // 1) 写入 USER 消息
   │         messageMapper.insert(assistantMessage);  // 2) 写入 ASSISTANT 模板回复
   │         conversationMapper.updateById(conv);     // 3) 刷新会话 updatedAt
   │     });
   │
   └─ 7. 并发冲突兜底：
         若步骤 6 捕获到 DuplicateKeyException（说明在步骤 5 之后有并发胜者抢先写入）：
         当前事务已完全回滚 -> 在事务外用独立的新快照重查 DB -> resolveExistingPair
```

---

### 2.4 认证加固与登录防刷

- **Token 即时吊销**（[RedisTokenBlacklist.java](../../src/main/java/yangsirly/rag_agent/authentication/RedisTokenBlacklist.java)）：
  - 签发 JWT 时生成唯一标识 `jti`（UUID）。
  - 用户调用 `/logout` 时，服务端计算 Token 剩余存活秒数 `ttl = exp - now`，在 Redis 中写入 `SET blacklist:jti:{jti} "1" EX {ttl}`。
  - `JwtAuthenticationFilter` 在每次解析 Token 时先校验 Redis 中是否存在该 `jti`，命中则直接作为匿名请求拒绝。
- **登录防爆破与账号锁定**（[AuthService.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthService.java:119)）：
  - `login` 方法**不加 `@Transactional`**。
  - 密码错误时，优先执行 Redis 原子自增 `INCR login:fail:{email}` 并设置 15 分钟 TTL（避免每次输错密码都写 MySQL 磁盘）。
  - 失败次数达到阈值（默认 5 次）时，才触发一次数据库操作：写入 `users.lock_until = now + 15分钟`。
  - 登录时先检查 `lock_until`，处于锁定期内直接抛出 `RateLimitExceededException` 映射为 `429 Too Many Requests`。

---

### 2.5 关于 RabbitMQ 通道的取舍说明

在早期的重构探索中，曾尝试引入 `chat/messaging/`（`MessageProducer`、`MessageConsumer` 空 `@RabbitListener`、`AsyncMessageService`）和 `spring-boot-starter-amqp`。但经严格审查后发现三个硬伤：
1. `MessageProducer`/`MessageConsumer` 上使用了 `@ConditionalOnBean(RabbitTemplate.class)`，由于被扫描组件早于 Spring Boot 自动配置，**条件恒为 false，消费者和队列在运行时根本不会被装配**。
2. `MessageConsumer.onMessage` 只是空方法，即使被激活也只是 ACK 并丢弃消息。
3. 当前系统仍处于第一阶段（模板快速回复），没有真实的大模型远程调用耗时，引入 MQ 属于无用包装。

因此，严格遵循 `AGENTS.md` 的“不为假想场景创建多层包装”原则，**坚决移除了 RabbitMQ 相关脚手架**。未来接入真实大模型时，再设计完整的 `PENDING -> MQ -> 模型推理 -> DONE` 状态机。

---

## 3. 底层执行原理

本节面向只有 Java 基础的同学，深入剖析上述代码在 **JVM、Spring 容器、Servlet 规范、MySQL 存储引擎、HikariCP 连接池与 Redis** 中的真实底层运行过程。

---

### 3.1 MySQL InnoDB 锁机制、MVCC 快照读与长事务危害

#### 3.1.1 什么是 MVCC 与 Read View？
MySQL 的 InnoDB 存储引擎使用 **MVCC（Multi-Version Concurrency Control，多版本并发控制）** 来实现无锁读。每行记录背后都有隐藏列：`trx_id`（最后修改该行的事务 ID）和 `roll_pointer`（指向 undo log 中该行历史版本的指针）。

当一个事务执行 `SELECT` 时，InnoDB 会为该事务生成一个 **Read View（读视图）**，记录当前活跃（未提交）的事务 ID 列表。
- **`READ COMMITTED` (读已提交)**：事务中**每一次**执行 `SELECT` 都会生成一个新的 Read View。因此能读到其他事务刚提交的数据。
- **`REPEATABLE READ` (可重复读，MySQL 默认)**：事务在**第一次**执行 `SELECT` 时生成 Read View，并在**整个事务生命周期中一直沿用这个快照**！

#### 3.1.2 “事务内捕获唯一键异常后重查”的致命陷阱
来看在 MySQL 默认的 `REPEATABLE READ` 隔离级别下，如果整个 `send()` 方法加了 `@Transactional` 会发生什么：

```text
时间线      线程 A (并发请求 1)                    线程 B (并发请求 2)
 ───      ────────────────────────             ────────────────────────
 T1       开启事务 1
 T2       执行 SELECT 查重 (无记录)
          [此时 InnoDB 为事务 1 建立了 Read View]
 T3                                            开启事务 2，执行 INSERT 成功并提交！
 T4       执行 INSERT -> 触发唯一约束冲突！
          捕获 DuplicateKeyException
 T5       试图在事务 1 内部再次 SELECT 查出胜者的数据
          【灾难发生】：由于事务 1 沿用 T2 的 Read View，
          它根据 undo log 依然只能看到 T2 时的世界（看不到事务 2 在 T3 提交的数据）！
          SELECT 返回 null -> 抛出 IllegalStateException -> 导致 500 崩溃！
```

**为什么我们的改造能彻底解决这个问题？**  
在改造后，`MessageService.send()` 本身没有 `@Transactional`。前面的读操作和最后的重查都在**自动提交（Auto-Commit）**模式下运行。每次 `SELECT` 都是独立的语句，都会获取最新的 Read View，因此在 `DuplicateKeyException` 发生后，重查能够立刻读到并发胜者提交的行，顺利进入 `resolveExistingPair` 返回 200！

#### 3.1.3 为什么物理级联删除（CASCADE）必须消灭？
当执行 `DELETE FROM conversations WHERE id = 1` 且外键为 `ON DELETE CASCADE` 时：
1. InnoDB 会隐式开启大事务，首先给 `conversations` 行加排他锁（X 锁）。
2. 随后 InnoDB 遍历所有外键关联的 `messages` 行，为每一条消息加行级 X 锁，并执行物理删除。
3. 如果一个会话有数万条消息，该事务需要申请并持有数万个行锁，不仅耗尽锁内存，还会让所有并发访问该会话相关消息的线程陷入长时间锁等待甚至死锁。
4. 所有被删数据的 undo log 和 binlog 瞬间暴涨。

改为软删除批处理后：
- 单次执行 `UPDATE messages SET deleted_at = ? WHERE conversation_id = ? AND deleted_at IS NULL LIMIT 1000`。
- 每批只锁 1000 行，执行完毕立即提交事务并释放行锁。数据库资源得以喘息，其他业务请求不会被饿死。

---

### 3.2 HikariCP 连接池与 Tomcat 线程模型：长连接事务与资源饥饿

```text
                      ┌────────────────────────────────────────┐
                      │        Tomcat Worker 线程池 (200)       │
                      │  Thread-1  Thread-2 ... Thread-200     │
                      └──────────────────┬─────────────────────┘
                                         │ 请求进来
                                         ▼
                 ┌──────────────────────────────────────────────────┐
                 │ Spring @Transactional 边界 (若范围过大)          │
                 │ 1. 从 HikariCP 借走物理 Connection               │
                 │ 2. 执行耗时的远程/Redis/限流操作 (占用连接不释放) │
                 │ 3. 执行少量 SQL                                  │
                 │ 4. 提交事务，归还 Connection                      │
                 └───────────────────────┬──────────────────────────┘
                                         │
                                         ▼
                      ┌────────────────────────────────────────┐
                      │       HikariCP 数据库连接池 (仅 20 个)    │
                      │  Conn-1   Conn-2  ...  Conn-20         │
                      └────────────────────────────────────────┘
```

#### 3.2.1 Spring 事务如何绑定数据库连接？
在 Spring 中，当一个方法被 `@Transactional` 注解修饰时，底层 AOP 拦截器（`TransactionInterceptor`）会通过 `TransactionSynchronizationManager` 将从 HikariCP 连接池借出的 JDBC `Connection` 对象绑定到当前线程的 `ThreadLocal` 中。该连接会被一直占用，直到整个方法执行完毕、事务提交或回滚后才归还给连接池。

#### 3.2.2 容量错配引发的“连接耗尽风暴”
- 我们的 Tomcat 配置为 `server.tomcat.threads.max=200`，意味着同时可以有 200 个 HTTP 请求在并发执行。
- 我们的 HikariCP 配置为 `spring.datasource.hikari.maximum-pool-size=20`，池中最多只有 20 个物理数据库连接。
- **故障场景**：如果 `MessageService.send()` 整体加了 `@Transactional`，当 20 个并发请求进来时，它们会立刻向 HikariCP 借走全部 20 个连接。如果此时这 20 个线程在执行 Redis 限流检查、Redis NX 幂等标记或网络 IO（耗时哪怕仅 50ms），这期间剩余的 180 个 Tomcat 线程如果也需要数据库连接，就会全部卡死在 `HikariPool.getConnection()` 上！
- 一旦排队等待时间超过 `connection-timeout=3000`（3秒），HikariCP 就会抛出 `SQLTransientConnectionException: Connection is not available, request timed out`，导致大量无辜的请求直接报 500 错误。

#### 3.2.3 编程式事务 `TransactionTemplate` 的减负原理
改造后，我们将限流、参数校验、会话归属查询、Redis 操作全部放在事务之外。只有在真正需要执行 3 条写 SQL（插入用户消息、插入助手回复、更新会话时间）时，才通过 `transactionTemplate.execute(...)` 临时借用数据库连接。
- 数据库连接持有时间从原来的 **30~100ms** 骤降至 **1~2ms**。
- 20 个物理连接得以以极高的周转率服务 200 个 Tomcat 工作线程，彻底消除了连接池饥饿问题。

---

### 3.3 Redis 单线程模型、Lua 脚本原子性与滑动窗口算法

#### 3.3.1 为什么分布式限流必须使用 Lua 脚本？
在分布式或多实例部署环境下，多个应用实例同时访问同一个 Redis 节点。假设我们要实现“1分钟内最多允许 10 次请求”的计数器：

**错误写法（客户端多次往返交互）：**
```java
// 线程 1 与 线程 2 并发执行
String current = redis.get("rate:user:1");
if (current == null) {
    redis.set("rate:user:1", "1");
    redis.expire("rate:user:1", 60); // 漏洞点 1：若在此处应用崩溃，该 key 将永不过期！
} else if (Integer.parseInt(current) < 10) {
    redis.incr("rate:user:1");       // 漏洞点 2：并发竞态条件（Race Condition）
}
```
在并发情况下，线程 1 和 2 可能同时读到 `current = 9`，各自判断都小于 10，然后各自执行 `INCR`，最终计数值变成 11，限流被突破（超卖）。

#### 3.3.2 Lua 脚本在 Redis 中的底层执行机制
Redis 服务器内部使用基于 Reactor 模式的单线程事件循环（Event Loop）来执行客户端命令。**Redis 保证：在一个 Lua 脚本执行期间，整个 Redis 服务器不会插队执行任何其他客户端发来的命令！** 整个脚本的执行是绝对原子的。

我们在 [RedisRateLimiter.java](../../src/main/java/yangsirly/rag_agent/common/ratelimit/RedisRateLimiter.java:18) 中编写的 Lua 脚本如下：
```lua
local c = redis.call('INCR', KEYS[1]);
if c == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1]);
end;
return c;
```
- `redis.call('INCR', KEYS[1])`：先对限流 key 自增。如果 key 不存在，Redis 会先初始化为 0 再自增为 1。
- `if c == 1 then redis.call('EXPIRE', ...)`：**只在计数器首次创建（值为 1）时设置过期时间**！
  - 为什么不能每次都 `EXPIRE`？如果每次请求都调用 `EXPIRE`，只要用户持续发请求，该 key 的过期时间就会被不断向后刷新，导致窗口永远无法重置，用户会被永久封锁。
  - 为什么不能分成两次网络请求？将 `INCR` 和 `EXPIRE` 封在同一个 Lua 脚本中，保证了即使网络中断或客户端宕机，也不会在 Redis 中留下一个没有 TTL 的“僵尸永久 key”。

#### 3.3.3 内存降级版滑动窗口（`InMemoryRateLimiter`）与防内存泄漏清扫
当 Redis 不可用或在单机测试环境下，系统会自动降级为 [InMemoryRateLimiter.java](../../src/main/java/yangsirly/rag_agent/common/ratelimit/InMemoryRateLimiter.java)。
- **数据结构**：`ConcurrentHashMap<String, Deque<Long>>`。每个 key 对应一个双端队列，存放每一次请求的毫秒级时间戳。
- **滑动窗口算法**：
  1. 获取当前时间 `now`。
  2. `synchronized(deque)` 锁定当前桶。
  3. 循环检查队头元素：若 `now - peekFirst() > windowMs`，说明队头时间戳已滑出时间窗口，调用 `pollFirst()` 弹出。
  4. 检查当前队列长度 `deque.size()`：若 `>= limit` 则拒绝；否则将 `now` 插入队尾 `addLast(now)` 并放行。
- **防内存溢出（OOM）的主动清扫机制**：
  如果黑客使用成千上万个随机伪造的 IP 发起攻击，`windows` Map 中会产生海量 key。虽然队列里的时间戳会过期，但 Map 的 Key 本身不会自动消失！因此代码中设置了 `SWEEP_THRESHOLD = 10_000`：当 Map 大小超过 10,000 时，触发全量迭代清扫，将队列为空的键从 Map 中彻底 `remove`，防止 JVM 内存泄漏。

---

### 3.4 Spring 容器启动阶段与 `@ConditionalOnBean` 死代码陷阱

在重构初期，开发团队曾踩过一个非常隐蔽的 Spring 容器生命周期大坑：

#### 3.4.1 错误写法及其现象
最初限流器和黑名单类是这样写的：
```java
// 错误写法！永远不会生效！
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisRateLimiter implements RateLimiter { ... }
```
**现象**：无论本地是否启动了 Redis、配置是否正确，系统在运行期间**永远只使用 `InMemoryRateLimiter`**，Redis 限流器和黑名单直接变成了“死代码”，生产多实例限流完全失效！

#### 3.4.2 深入 Spring Bean 加载生命周期
要理解为什么会失效，必须了解 Spring ApplicationContext 的启动顺序：

```text
Spring 容器启动阶段
   │
   ▼ 【阶段 1：组件扫描与 BeanDefinition 注册】
   Spring 扫描用户标注了 @Component、@Service、@Configuration 的类。
   此时遇到 RedisRateLimiter，开始计算其上的条件注解 @ConditionalOnBean(StringRedisTemplate.class)。
   
   【此时的致命矛盾】：
   Spring Boot 自身的各种 AutoConfiguration（包括 DataRedisAutoConfiguration）
   排在用户自定义组件扫描之后才会加载！
   在阶段 1 计算条件时，容器中根本还没有 StringRedisTemplate 的 BeanDefinition！
   -> 条件计算结果恒为 FALSE！RedisRateLimiter 直接被 Spring 丢弃，根本不会被注册！
   
   ▼ 【阶段 2：AutoConfiguration 加载】
   Spring Boot 开始加载 RedisAutoConfiguration，注册 StringRedisTemplate。
   但为时已晚，阶段 1 已经结束，Spring 不会再回过头去重新扫描一遍 RedisRateLimiter！
   
   ▼ 【阶段 3：Bean 实例化与依赖注入】
   开始创建单例 Bean。此时由于没有 RedisRateLimiter，只能注入 InMemoryRateLimiter。
```

#### 3.4.3 正确解法：`@Configuration` + `ObjectProvider`
我们在 [RateLimitConfiguration.java](../../src/main/java/yangsirly/rag_agent/common/ratelimit/RateLimitConfiguration.java:36) 中采用了标准写法：
```java
@Configuration
public class RateLimitConfiguration {
    @Bean
    public RateLimiter rateLimiter(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            return new RedisRateLimiter(redisTemplate);
        }
        return new InMemoryRateLimiter();
    }
}
```
- 将决策逻辑从“类扫描阶段（阶段 1）”推迟到了“Bean 实例化阶段（阶段 3）”。
- `ObjectProvider<StringRedisTemplate>` 是一个延迟解析句柄。当 Spring 执行 `rateLimiter(...)` 这个 `@Bean` 方法时，所有的 AutoConfiguration 都已经就绪。调用 `getIfAvailable()` 能准确感知到容器中是否存在 `StringRedisTemplate`，从而做出正确的装配选择。

---

### 3.5 JWT 状态管理：`jti` 黑名单机制与 TTL 精确对齐

#### 3.5.1 为什么无状态 Token 需要引入黑名单？
传统的 Session 认证，用户状态保存在服务端内存或 Redis 中，注销时服务端只需把 Session 删除即可。  
而 JWT 将用户 ID、角色等信息全部编码在客户端保存的字符串中，服务端只用密钥做密码学签名验证（无状态）。这种设计的最大缺点就是：**一旦签发，在有效期结束前，Token 自身在数学上始终合法，服务端无法在不重启、不换密钥的前提下单方面宣布某一个 Token 失效。**

#### 3.5.2 为什么黑名单只需要保存 `jti` 而不是整个 Token？
JWT 标准载荷（Payload）中包含一个 `jti`（JWT ID，即该 Token 的唯一唯一 UUID）。
- 整个 JWT 字符串通常长达数百字节，如果把整个 Token 存入 Redis，会浪费大量内存。
- 仅把 `jti`（36 字节 UUID）存入 Redis，Key 为 `blacklist:jti:{jti}`，Value 为 `"1"`，内存占用极小。

#### 3.5.3 为什么黑名单 TTL 必须与 Token 剩余有效期对齐？
在 [AuthController.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthController.java:68) 中：
```java
String jti = jwtTokenService.extractJti(token);
Date exp = jwtTokenService.extractExpiration(token);
long ttlSec = (exp.getTime() - System.currentTimeMillis()) / 1000;
if (ttlSec > 0) {
    tokenBlacklist.blacklist(jti, Duration.ofSeconds(ttlSec));
}
```
- **核心逻辑**：假设一个 Token 总有效期为 30 分钟。用户在登录 10 分钟后点击了退出，此时该 Token 还有 20 分钟才自然过期。
- 我们只需在 Redis 中把该 `jti` 关 20 分钟禁闭（`EX 1200`）。
- 20 分钟之后，Redis 会自动通过过期机制删除该 Key；而此时，哪怕有人拿着旧 Token 来访问，JWT 过滤器在解析阶段就会因为 `exp < now` 抛出 `ExpiredJwtException` 直接拒绝！
- **收益**：Redis 中的黑名单永远只会保留“提前注销但尚未自然过期”的少量 Token，占用的内存会自动动态收敛，绝不会无限膨胀。

---

### 3.6 Web 容器与全链路 TraceId：ThreadLocal 与 MDC 的生命周期

在分布式和高并发排查日志时，如果没有全局唯一的请求标识，海量并发日志混合在一起将无法分析。

我们在 [TraceIdFilter.java](../../src/main/java/yangsirly/rag_agent/common/web/TraceIdFilter.java:33) 中实现了请求追踪：
```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
    String traceId = sanitize(request.getHeader(TRACE_ID_HEADER));
    if (traceId == null) {
        traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    MDC.put(MDC_TRACE_ID, traceId);
    response.setHeader(TRACE_ID_HEADER, traceId);
    try {
        chain.doFilter(request, response);
    } finally {
        MDC.remove(MDC_TRACE_ID); // 极其关键的清理动作！
    }
}
```

#### 3.6.1 MDC 的底层机制与 Tomcat 线程池污染陷阱
- SLF4J 的 `MDC`（Mapped Diagnostic Context）底层是通过 `ThreadLocal<Map<String, String>>` 实现的。当你在当前线程 `MDC.put("traceId", ...)` 后，后续所有日志框架（Logback）打印日志时都会自动从当前线程的 `ThreadLocal` 获取该值并打印在每行日志开头。
- **致命陷阱**：Tomcat 是**线程复用模型**（200 个 Worker 线程长久存活并反复处理不同用户的 HTTP 请求）。如果不在 `finally` 块中显式调用 `MDC.remove(MDC_TRACE_ID)`：
  1. 请求 A 由 `Thread-1` 处理，TraceId 为 `aaaa1111`，处理完毕后连接断开。
  2. 随后请求 B 也分配到了 `Thread-1` 处理，如果请求 B 是一个静态资源或没有生成新的 TraceId，那么 `Thread-1` 的 `ThreadLocal` 里依然残留着请求 A 的 `aaaa1111`！
  3. 请求 B 的所有业务日志都会打上请求 A 的 TraceId，导致日志串号、全链路追踪彻底失真！

#### 3.6.2 为什么自带 TraceId 必须经过 `sanitize` 白名单校验？
`TraceIdFilter` 支持客户端通过请求头 `X-Trace-Id` 自行传入 TraceId（方便网关统一链路）。但是代码中使用 `sanitize()` 限制了必须是 8~64 位的十六进制字符：
- 如果直接信任并透传外部传入的任意字符串，恶意攻击者可以在请求头中注入 `\n\r`（换行符）和伪造的日志内容（**CRLF 日志注入攻击 / Log Forging**），从而在服务端的日志文件中伪造虚假的“管理员登录成功”等审计日志。

---

## 4. 知识点与应用对照

下表将本文讲解的所有底层机制与项目中的具体代码位置、可观察现象及破坏性后果进行精确绑定：

| 核心知识点 | 对应项目代码位置 | 触发与观察方式 | 正常表现（可观察结果） | 错误写法或省略后的灾难后果 |
| --- | --- | --- | --- | --- |
| **MySQL MVCC 隔离机制** | `MessageService.send` | 并发两个相同 `clientMessageId` 请求 | 胜者返回 201，败者重查返回 200，库中恰好 1 对消息 | 若在同一个 `@Transactional` 内重查：RR 隔离导致读旧快照，找不到胜者消息抛 500 |
| **细粒度事务与连接池保护** | `MessageService`、`ConversationService.delete` | 压测高并发发消息与批量删会话 | 20 个 Hikari 连接支撑 200 个并发线程，无连接超时 | 加大 `@Transactional`：Redis 等外部 IO 霸占物理连接，HikariCP 报 `ConnectionTimeoutException` 500 |
| **InnoDB 外键约束与软删除** | `V3`、`V5` 迁移脚本、`ConversationMapper` | 删除含有 1000 条消息的会话 | 分批执行 `UPDATE LIMIT 1000`，外键保持完整，再次查询返回 404 | 若只软删消息硬删会话：RESTRICT 外键直接报错抛 500；若用 CASCADE：大事务锁全表锁从表 |
| **Spring 容器生命周期** | `RateLimitConfiguration`、`AuthConfiguration` | 启动项目检查 Bean 装配 | 生产环境装配 `RedisRateLimiter`，测试环境降级为 `InMemoryRateLimiter` | 在 `@Component` 上加 `@ConditionalOnBean`：求值过早导致条件恒为 false，Redis 实现成死代码 |
| **Redis Lua 原子脚本** | `RedisRateLimiter.tryAcquire` | 100 线程并发打同一限流 Key | 计数值精准递增，严格拦截在 limit 阈值，响应 429 | 用普通 `GET+INCR`：并发竞态导致计数值超卖；漏设 `EXPIRE` 导致内存永久泄漏 |
| **JWT `jti` 黑名单与 TTL** | `AuthController.logout`、`JwtAuthenticationFilter` | 登出后用旧 Cookie 请求 `GET /me` | 返回 401 Unauthorized；Redis 自动在 TTL 后释放 Key | 仅前端清除 Cookie：旧 Token 泄露后依然能在有效期内任意调用接口 |
| **事务异常回滚机制** | `AuthService.login` | 连续输错 5 次密码 | 第 6 次返回 429；`users` 表成功写入 `lock_until` | 在 `login` 上加 `@Transactional`：密码错误抛出的异常会导致事务回滚，失败计数与锁定永远写不进 DB |
| **Servlet Filter 链与 MDC** | `TraceIdFilter`、`RateLimitFilter` | 查看控制台日志与响应头 | 响应头包含 `X-Trace-Id`，日志带有 `[traceId]`，线程归还后清理 | `finally` 漏掉 `MDC.remove()`：Tomcat 线程复用导致日志 TraceId 交叉串号污染 |

---

## 5. 换种写法会怎样：典型错误与反模式剖析

为了加深理解，本节列举几种在实际开发中最容易犯的典型错误写法，分析其背后的崩溃调用链：

### 5.1 反模式 1：只软删子表（messages），父表（conversations）仍硬删除
- **错误写法**：Flyway V3 给 `messages` 增加了 `deleted_at` 并将外键改为 `ON DELETE RESTRICT`，但 `conversations` 表没有加 `deleted_at`，删除会话时依然执行 `DELETE FROM conversations WHERE id = ?`。
- **崩溃调用链**：
  ```text
  1. 用户删除包含消息的会话。
  2. 代码先将该会话下的所有 messages 标记为 deleted_at = NOW()（软删除，行依然物理存在于数据库中）。
  3. 代码随后执行 DELETE FROM conversations WHERE id = 1。
  4. MySQL InnoDB 检查外键约束：发现 messages 表中依然存在 conversation_id = 1 的物理行！
  5. 由于外键是 RESTRICT（严格限制），MySQL 直接抛出错误：
     Cannot delete or update a parent row: a foreign key constraint fails (`fk_messages_conversation`)
  6. Spring 抛出 DataIntegrityViolationException，接口直接报 500 崩溃！
  ```
- **正解**：Flyway V5 将 `conversations` 也改为软删除。删除会话只做 `UPDATE conversations SET deleted_at = NOW()`，物理行始终保留，完美满足外键约束。

### 5.2 反模式 2：把限流和防刷检查写在 Controller 或 Spring MVC 拦截器中
- **错误写法**：不用 Servlet Filter，而是在 Controller 方法入口或 Spring MVC `HandlerInterceptor` 里调用限流逻辑。
- **风险分析**：
  1. 一个 HTTP 请求到达 Tomcat 后，需要经过 `Connector -> StandardContext -> FilterChain -> DispatcherServlet -> HandlerMapping -> HandlerInterceptor -> Controller`。
  2. 恶意攻击者发起的海量刷频流量如果直到 Controller 甚至业务层才被拦截，整个 Spring MVC 上下文和参数解析器（`HttpMessageConverter` 反序列化 JSON）已经消耗了大量的 CPU 和内存堆栈。
  3. 使用最前置的 `RateLimitFilter`（`@Order(Ordered.HIGHEST_PRECEDENCE + 1)`），在请求刚进入 Servlet 容器、尚未做任何反序列化和认证鉴权之前就判定并返回 429，最大程度保护了后端计算资源。

### 5.3 反模式 3：在 Filter 中尝试读取 Request Body 做账号维度的限流
- **错误写法**：希望在 `RateLimitFilter` 中根据用户提交的 JSON 请求体中的 `email` 进行防刷。
- **陷阱分析**：
  - Servlet 规范中，`HttpServletRequest.getInputStream()` 默认是一个**只能读取一次的流**。
  - 如果在 Filter 里把流读出来解析 JSON，后续 `DispatcherServlet` 在将请求绑定到 `@RequestBody LoginRequest` 时就会抛出 `IOException: Stream closed` 或读取到空内容。
  - 要解决必须使用 `ContentCachingRequestWrapper` 包装请求并在内存中缓存整个请求体，这在高并发下会消耗可观的 JVM 堆内存。
  - **正解**：Filter 只做无状态、开销极小的 IP 维度限流；账号维度的锁定（`login:fail:{email}`）放到已经反序列化好 DTO 的 `AuthService` 中处理。

---

## 6. 验证实验

### 6.1 自动化测试执行事实

本机全量执行 Maven 测试套件验证（2026-08-31 实测）：

```bash
mvn test
```

**实测结果：`Tests run: 129, Failures: 0, Errors: 0, Skipped: 1`（100% 通过）**

关键验证用例：
1. **并发 12 线程同 key 幂等发送**（[ChatFlowIntegrationTests.java](../../src/test/java/yangsirly/rag_agent/chat/ChatFlowIntegrationTests.java:139)）：
   - 使用 `CountDownLatch` 让 12 个并发线程在同一时刻向 `/conversations/{id}/messages` 发送具有相同 `clientMessageId` 的请求。
   - 断言：所有请求均返回 200/201 成功响应，数据库中通过 `SELECT COUNT(*)` 查得**最终恰好只有 1 条 USER 消息和 1 条 ASSISTANT 模板回复**，无任何 500 异常。
2. **登录防爆破锁定测试**（[AuthLockoutTests.java](../../src/test/java/yangsirly/rag_agent/authentication/AuthLockoutTests.java:38)）：
   - 连续输入 2 次错误密码返回 401 `INVALID_CREDENTIALS`。
   - 第 3 次即使输入正确密码，也稳定返回 `429 Too Many Requests`，响应头携带 `Retry-After: 60`，数据库 `lock_until` 字段成功写入。
3. **JWT 即时吊销测试**（[AuthControllerTests.java](../../src/test/java/yangsirly/rag_agent/authentication/AuthControllerTests.java)）：
   - 登录成功获取 Cookie -> 调用 `/logout` 注销 -> 继续携带旧 Cookie 访问受保护的 `/me` 接口 -> 返回 `401 Unauthorized`。
4. **Servlet 级限流单元测试**（[RateLimitFilterTests.java](../../src/test/java/yangsirly/rag_agent/common/web/RateLimitFilterTests.java:26)）：
   - 独立实例化 `RateLimitFilter`，验证第 4 次请求被精准拦截为 429，且响应头包含真实的 `X-RateLimit-Limit: 3` 与 `Retry-After: 60`。

---

### 6.2 开发与排错中踩过的两个真实生产“暗坑”

#### 坑 1：Spring Boot 4 中 Redis 自动配置类的重构与改名
- **现象**：在写单元测试时，我们希望通过 `spring.autoconfigure.exclude` 排除 Redis 自动配置以测试内存降级实现。但在配置了 `org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration` 后，测试依然尝试连接 Redis 并抛错。
- **根因**：Spring Boot 4 将 Redis 自动配置模块进行了重命名和拆分。旧类名已被废弃，新全限定类名为：
  `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`。
- **启示**：框架大版本升级后，不能凭记忆书写自动配置类名，必须查看实际依赖包的 `META-INF/spring/` 描述文件。

#### 坑 2：Java `Clock.systemUTC()` 与数据库 `NOW()` 的时区错位
- **现象**：在写锁定断言时，执行 SQL `SELECT COUNT(*) FROM users WHERE lock_until > NOW()` 总是返回 0，但 Java 代码中的 `user.getLockUntil().isAfter(LocalDateTime.now(clock))` 却返回 true。
- **根因**：应用配置的 `Clock` 使用了 UTC 时区（+0:00），写入数据库的时间戳是 UTC 时间（例如 09:00）；而本地 MySQL/H2 会话使用的是操作系统本地时区（UTC+8，当前时间为 17:00）。数据库在执行 `NOW()` 时返回 17:00，导致 `09:15 > 17:00` 恒为假！
- **启示**：在分布式跨系统设计中，时间基准必须全链路统一（要么全部带时区的 ISO-8601，要么数据库会话与应用时区显式对齐）。

---

## 7. 常见误区与面试问题

### 7.1 常见认知误区
1. **误区：“只要方法加了 `@Transactional`，数据就绝对安全可靠。”**  
   - *纠偏*：`@Transactional` 默认是单机数据库事务。高并发下它会长时间占用数据库物理连接，并在默认的 `REPEATABLE READ` 隔离级别下因快照读导致读不到并发已提交的数据。必须合理划分编程式事务（`TransactionTemplate`），将非 DB 操作移出事务。
2. **误区：“Redis 性能极高，有并发请求先在 Java 里 GET 一下，没超限再 INCR 就行了。”**  
   - *纠偏*：这是典型的 Check-Then-Act 非原子操作漏洞。网络传输存在延迟，多线程并发下 GET 和 INCR 之间会被插队，必须使用 Redis Lua 脚本保证多步操作的原子性。
3. **误区：“软删除只需要在表上加一个 `deleted_at` 字段，把 DELETE 改成 UPDATE 就行。”**  
   - *纠偏*：软删除会彻底改变唯一索引和外键的行为！如果表上有唯一约束（如 `(user_id, email)`），软删除后再次注册相同邮箱会撞唯一索引；如果子表软删而父表硬删，RESTRICT 外键会直接报错。必须同步改造联合索引与父子表删除策略。

---

### 7.2 高频面试题精解（以本项目为背景）

#### Q1: 在高并发场景下，如何保证接口的幂等性？你们项目是如何设计的？
> **答题要点**：
> 1. **分层防御架构**：幂等不能仅靠一层防护。我们项目采用了 **“客户端唯一键 + Redis 快速拦截 + 数据库唯一约束兜底”** 的三层设计。
> 2. **前端与通信层**：客户端每次发起生成动作前，生成一个唯一的 UUID（`clientMessageId`）。
> 3. **Redis 快路径**：服务端收到请求后，先执行 Redis `SET idmp:conv:{id}:client:{uuid} "1" NX EX 30s`。如果 Key 已存在，说明短时间内有重复请求，直接查库返回，避免无谓的写锁竞争。
> 4. **数据库终审判决**：在 `messages` 表上建立 `UNIQUE (conversation_id, client_message_id)` 联合唯一索引。无论网络重试还是并发穿透，数据库底层物理保证只有一条能插入成功。
> 5. **冲突恢复与事务外重查**：并发落败的请求捕获 `DuplicateKeyException` 后，在事务外利用全新的 Read View 重新查询胜者插入的数据并返回原结果（HTTP 200），保证客户端无感知。

#### Q2: 为什么你们项目中消息发送的 `send()` 方法没有使用 `@Transactional` 注解？
> **答题要点**：
> 1. **避免连接池饥饿**：`send()` 方法中包含参数校验、Redis 限流检查、Redis NX 幂等标记以及会话所有权查询。如果加了 `@Transactional`，Spring 在方法入口就会从 HikariCP 借出一个物理连接并绑定到线程。当并发高时，非 DB 操作会占满有限的 20 个连接池，导致 Tomcat 其余 180 个线程超时报错。
> 2. **解决 MySQL RR 隔离级别下的快照读陷阱**：如果在事务内执行了 SELECT 查重，InnoDB 会生成 Read View 快照。当并发冲突发生并捕获 `DuplicateKeyException` 后，若在同一个事务内重查，快照读看不到并发事务刚提交的记录，会误判为 null 抛出 500。
> 3. **最小事务范围**：我们仅对核心的 3 处写操作使用 `TransactionTemplate` 开启极小的本地事务，大幅缩短持锁时间。

#### Q3: 讲讲你们是如何实现 JWT 即时注销（Logout）的？如何防止 Redis 内存被撑爆？
> **答题要点**：
> 1. **痛点**：JWT 是无状态的，传统 `/logout` 仅清除客户端 Cookie，若 Token 被窃取依然可用。
> 2. **方案**：我们在签发 JWT 时植入了唯一标识 `jti`（UUID）。在用户调用 `/logout` 时，服务端解析出该 Token 的 `jti` 及其过期时间戳 `exp`。
> 3. **TTL 精确对齐**：计算剩余有效期 `ttl = exp - now`，在 Redis 中写入 `SET blacklist:jti:{jti} "1" EX {ttl}`。
> 4. **内存自收敛**：`JwtAuthenticationFilter` 在每次请求时先查 Redis 是否存在该 `jti`。由于 Redis Key 的 TTL 刚好等于 Token 的剩余寿命，当 Token 自身自然过期（会被 JWT 校验器直接拦截）时，Redis 中的 Key 也刚好被自动清除，从而保证了 Redis 内存占用始终处于可控的极小范围。

---

## 8. 可操作的小实验与动手练习

为了让初学者更直观地感受上述原理，建议亲自完成以下 3 个小实验：

### 实验 1：复现 MySQL REPEATABLE READ 快照读陷阱
- **操作**：将 [MessageService.java](../../src/main/java/yangsirly/rag_agent/chat/MessageService.java) 的 `send` 方法整体加上 `@Transactional` 注解，并将内部的 `transactionTemplate.execute(...)` 改为直接调用 Mapper。
- **观察**：运行 `ChatFlowIntegrationTests.java` 中的并发测试 `concurrentSendWithSameKeyProducesExactlyOnePair`。
- **预期现象**：并发测试出现失败，控制台抛出 `IllegalStateException: Duplicate clientMessageId conflict but USER message was not found`，亲身体会事务内快照读读不到并发胜者提交数据的现象。

### 实验 2：观察 Tomcat 线程复用导致的 MDC 日志污染
- **操作**：打开 [TraceIdFilter.java](../../src/main/java/yangsirly/rag_agent/common/web/TraceIdFilter.java)，将 `finally { MDC.remove(MDC_TRACE_ID); }` 注释掉。
- **观察**：使用浏览器或 Postman 连续发送几次请求，观察后端控制台日志中的 `[traceId]`。
- **预期现象**：会发现某些不需要 TraceId 的请求（或新请求）打印出了上一批请求残留的 TraceId，理解线程池复用与 `ThreadLocal` 内存泄露的成因。

### 实验 3：测试 Redis 限流器 fail-open 容灾
- **操作**：在本地启动 Redis 的情况下运行项目，随后直接通过命令行 `redis-cli shutdown` 或停止 Redis 容器。
- **观察**：继续调用注册、登录和发消息接口。
- **预期现象**：系统不会崩溃或报 500，而是平滑降级（限流器捕获异常后返回 `true` 放行），业务依然可以正常写入数据库，直观理解工业级系统中的“优雅降级”设计。

---

## 状态与维护记录

| 日期 | 状态说明 |
| --- | --- |
| 2026-08-25 | 完成工业级重构初始版本：Flyway V3/V4/V5、限流/幂等/黑名单/登录防刷、HikariCP 容量调优与测试补齐。 |
| 2026-08-31 | 深度重构底层原理学习文档：以 Java 初学者为受众，彻底讲透 MVCC、InnoDB 锁、HikariCP 连接池饥饿、Redis Lua 脚本原子性、Spring 生命周期陷阱与 MDC 机制。`mvn test` 129 个用例全绿通过。 |

### 2026-09-07：本地 EDITOR 初始化与空库迁移验证

`DevEditorSeeder.run` 是本地启动入口，`@Profile("local")` 与 `@ConditionalOnProperty` 控制 Bean 是否注册；运行时再检查只有 local 一个 active profile。凭据来自环境变量，不写日志。输入是本地配置的邮箱和密码，输出是 users 中一个 ACTIVE EDITOR；已经存在的 CUSTOMER 不能被提升权限。已有 EDITOR 密码匹配时不写入，不匹配时失败退出，开发者修正配置后重启恢复。

`@Transactional` 包裹 ApplicationRunner 调用，MyBatis insert 当场执行；邮箱唯一约束是并发竞争的最终边界。先查询只用于幂等判断，不能代替唯一约束。`DevEditorSeederTest` 覆盖新建 BCrypt 哈希、幂等、不提权、不重置密码、混合 profile 拒绝和 Bean 启用条件。隔离 MySQL 8.0 空库启动后 `/login`、`/me` 均返回 EDITOR。

本轮发现 V2 和 V5 重复创建 `idx_conversations_user_updated`，空库 MySQL 报 1061。用户确认 V5 未发布后，改为单条 `ALTER TABLE` 替换索引，保留 user_id 外键需要的索引前缀；新的隔离空库成功执行 V1～V9。已发布环境不应照搬修改历史 migration，需单独制定升级和校验方案。

理解练习：解释为什么 profile 检查不能证明数据库是测试库，以及为什么先查询邮箱仍需要数据库唯一约束。
