# 里程碑 06：工业级高并发加固

> 一次性工业级重构：保持"注册→登录→会话→消息幂等"语义不变，把单机
> `Spring Boot + MySQL + JWT Cookie` 改造为可多实例部署的形态。引入 Redis
>（限流 / 幂等快路径 / JWT 黑名单 / 登录失败计数），数据库以软删替代级联大事务，
> API 向后兼容。RabbitMQ 通道本次不引入（见 §2.5）。
> 对标结构：本文 8 段骨架与 `milestone-03-chat-loop.md` 一致。

相关契约：`phase-1-api.md` §2.7 工业增补、`一阶段需求文档.md` 第二章 非功能需求；
迁移：`V3__add_soft_delete_to_messages.sql`、`V4__add_industrial_columns.sql`、
`V5__add_soft_delete_to_conversations.sql`。

---

## 1. 项目现象与问题：为什么要做这一轮改造

**改造前现象：**
- `ON DELETE CASCADE` 删除会话时 MySQL 需在同一事务里级联删 N 条消息，持锁久、易死锁与超时；监控仅 health，无 trace。
- JWT 无状态但 `logout` 仅清 Cookie，复制的 token 可继续使用至过期，无法多实例即时吊销。
- `clientMessageId` 仅靠 `SELECT→INSERT` + 唯一约束兜底，单机并发尚可，分布式下重试风暴易重复打 DB。
- 注册/登录无限流，易被刷；消息列表 `page*size` 无上限，深分页拖垮 DB。
- 本地压测未定义 SLO，Hikari/Tomcat 取默认，连接与线程池未按容量规划。

**改造后要回答：** 级联删除为何必须消灭？分布式幂等只靠 DB 为何不够？Lua 限流与 Redis INCR 有何区别？JWT 黑名单 TTL 如何与过期对齐？为什么 `@ConditionalOnBean` 写在被扫描的组件上会变成死代码？

---

## 2. 具体写法与调用链

### 2.1 数据层

| 文件 | 改动 |
| --- | --- |
| `V3__add_soft_delete_to_messages.sql` | `messages.deleted_at DATETIME(6) NULL` + 索引 `idx_messages_conversation_deleted`；`fk_messages_conversation ON DELETE RESTRICT` |
| `V4__add_industrial_columns.sql` | `messages.status VARCHAR(20) DEFAULT 'DONE'` + `ck_messages_status`；`users.failed_login_count / lock_until` |
| `V5__add_soft_delete_to_conversations.sql` | `conversations.deleted_at DATETIME(6) NULL` + 索引 `idx_conversations_user_updated`。**修掉 V3 留下的致命矛盾**：V3 把外键改成 RESTRICT 又对 messages 软删，但会话仍物理删除——软删的消息行仍引用会话，RESTRICT 直接阻止删除，导致"删任何带消息的会话都抛外键错误 → 500"。会话也软删后，行保留，外键始终完整。 |
| `schema.sql` | H2 同步 `deleted_at/status/lock_until`（conversations + messages） |
| `application.properties` | `hikari.maximum-pool-size=20, minimum-idle=5, connection-timeout=3000`；`server.tomcat.threads.max=200`；`spring.data.redis.*`；`app.rate-limit.*`、`app.auth.*`；`management.endpoints.web.exposure.include=health,metrics,prometheus` |

**调用链（删除）：** `ConversationService.delete()` 不加 `@Transactional` →
`findByIdAndUserId` 校验归属 → 循环 `messageMapper.softDeleteBatch`（每批
`UPDATE ... LIMIT 1000`，**每批一个独立事务**，由 `TransactionTemplate` 包裹）→
最后 `conversationMapper.softDeleteById`（单行 UPDATE 的小事务）。会话软删后，
`findByIdAndUserId / listByUserId / countByUserId` 一律过滤 `deleted_at IS NULL`，
对外表现为"不存在"——再次删除自然返回 404。

> 为什么不能用一个 `@Transactional` 包住整个循环？那样批处理只是"把大事务切成多条语句"，
> 锁仍持有到整体提交，违背了消除 CASCADE 大事务的初衷。批次独立提交带来的中间状态
> （部分消息已删、会话仍在）可接受：消息只通过会话可见，且操作可重试幂等。

### 2.2 限流

| 文件 | 职责 |
| --- | --- |
| `common/ratelimit/RateLimiter.java` | `tryAcquire(key,limit,window)` 抽象 |
| `common/ratelimit/RedisRateLimiter.java` | Lua `INCR+EXPIRE` 原子计数，异常 fail-open；**普通类**，由配置类装配 |
| `common/ratelimit/InMemoryRateLimiter.java` | 单机滑动窗口降级；带 key 清扫防膨胀；**普通类** |
| `common/ratelimit/RateLimitConfiguration.java` | `@Bean rateLimiter(ObjectProvider<StringRedisTemplate>)` 选型：Redis 可用 → Redis 实现，否则内存 |
| `common/web/RateLimitFilter.java` | `@Component @Order(HIGHEST_PRECEDENCE+1)`，作为 Servlet 过滤器在安全链之前执行；只做 **IP 维度**（注册/登录）|
| `common/web/TraceIdFilter.java` | `@Order(HIGHEST_PRECEDENCE)` 注入 traceId 至 MDC，客户端自带值需通过十六进制白名单 |
| `common/ratelimit/RateLimitProperties.java`、`AuthLockProperties.java` | `@ConfigurationProperties(prefix="app.rate-limit"/"app.auth")` |

**调用链：** Servlet 过滤器顺序 `TraceIdFilter → RateLimitFilter → Spring Security → Dispatcher`；
限流命中由 `RateLimitFilter` 直接写 `429 + Retry-After + X-RateLimit-Limit`（真实阈值）响应体。
发送消息的 **userId 维度**限流在 `MessageService.send` 内执行（需要认证主体）。

> 教训：`@Component + @ConditionalOnBean(StringRedisTemplate.class)` 写在被扫描的组件上
> **不会生效**——组件扫描早于自动配置，条件求值时 `StringRedisTemplate` 的 Bean 定义还没注册，
> 条件恒为 false，Redis 限流器在生产里根本不会被装配，全部静默退化为内存实现
> （多实例下各自计数，限流被放大 N 倍）。正确做法是在 `@Configuration` 里用
> `ObjectProvider.getIfAvailable()` 在实例化期解析，那时所有 Bean 定义都已就位。
> 黑名单同样踩了这个坑，本次一并修正。

### 2.3 分布式幂等

`chat/MessageService.java:send()` 本身 **不加 `@Transactional`**：
1. 校验 + `RateLimiter.tryAcquire("send:user:"+userId)`（默认 20/min）
2. 校验会话归属
3. **Redis fast-path**：`SET idmp:conv:{id}:client:{uuid} NX EX 30s`；命中转 DB 查证
4. DB 快路径 `findUserMessageByClientMessageId` + `resolveExistingPair`
5. 写路径用 `TransactionTemplate` 包成单事务：`insert USER(DONE)` → `insert ASSISTANT(DONE)` → `update conversations.updated_at`
6. 撞唯一约束时事务已回滚，**在事务外用新连接重查**走 `resolveExistingPair`，DB 仍为最终仲裁
7. `Idempotency-Key` 头与 body `clientMessageId` 兼容：同时存在以 Header 为准，不一致 → 400

> 为什么读操作要放在事务外？MySQL `REPEATABLE READ` 下，事务内第一条 SELECT 建立快照。
> 若并发胜者在我们的快照之后提交，我们撞 `DuplicateKeyException` 后在**同一事务内**重查，
> 读的是旧快照——看不到对方已提交的 USER 消息，`resolveExistingPair` 拿不到数据，
> 抛 `IllegalStateException` → 500。把读移到事务外（每条语句独立快照），重查就能看到
> 已提交的行。同时，限流与 Redis 调用不再占用 Hikari 连接（`@Transactional` 在第一条
> SQL 前就会向连接池借连接）。

### 2.4 认证增强

| 文件 | 改动 |
| --- | --- |
| `authentication/JwtTokenServiceImpl.java` | 新增 `jti` 声明（`id`+`claim jti`），`issueAccessToken` 随机 UUID；`extractJti`/`extractExpiration` 上提到 `JwtTokenService` 接口 |
| `authentication/AuthConfiguration.java` | `@Bean tokenBlacklist(ObjectProvider<StringRedisTemplate>)` 选型：Redis → `RedisTokenBlacklist`，否则 `InMemoryTokenBlacklist` |
| `authentication/JwtAuthenticationFilter.java` | `extractJti → tokenBlacklist.isBlacklisted → 跳过认证`；不再 `instanceof` 具体实现 |
| `authentication/AuthService.java` | `lockUntil` 校验 + **Redis INCR `login:fail:{email}`** 计数（带锁定时长 TTL），达阈值落库 `users.lock_until`；Redis 不可用退化为 DB 计数 |
| `authentication/AuthenticationExceptionHandler.java` | 新增 `RateLimitExceededException → 429 + Retry-After`（**修复**：此前锁定账号登录会 500，因为 `@RestControllerAdvice` 没映射这个异常）|
| `authentication/AuthController.java` | `logout` 提取 jti/expiration 计算 TTL `SET blacklist:jti EX ttl` |

### 2.5 关于 RabbitMQ 通道（本次移除）

前一轮重构引入了 `chat/messaging/`（`MessageProducer` 声明队列、`MessageConsumer` 空 `@RabbitListener`、`AsyncMessageService` 空方法）和 `spring-boot-starter-amqp`。审查发现这批代码有三个问题：

1. `MessageProducer`/`MessageConsumer` 用 `@ConditionalOnBean(RabbitTemplate.class)`，同样是"被扫描组件上的 `@ConditionalOnBean`"，求值早于 `RabbitAutoConfiguration`，**条件恒为 false**，队列和消费者永远不会被装配——即使 RabbitMQ 在运行。
2. `MessageConsumer.onMessage` 是空方法，一旦真的激活会 ACK 并丢弃消息。
3. 没有任何代码调用 `AsyncMessageService.publishPending`，`chatTaskExecutor` 也没有任何消费者。

结论：这是"看起来在搭通道、实际永不运行"的脚手架，违反 `AGENTS.md` 的"不为假想场景创建多层包装"。本次移除 `chat/messaging/`、`spring-boot-starter-amqp`、`docker-compose` 中的 rabbitmq 服务、`chatTaskExecutor` 与相关 properties。RabbitMQ 留作接入真实模型时的非目标：那时应实现真正的 `PENDING→MQ→DONE` 状态机与幂等确认，而不是空壳。

---

## 3. 底层执行原理

### 3.1 InnoDB 锁、唯一约束与软删除

- `uk_messages_conversation_client_message` 为 `UNIQUE (conversation_id, client_message_id)`，并发双发必有一条 `Duplicate entry`；MyBatis 翻译为 `DuplicateKeyException`。该约束为幂等最终真相，Redis 仅快路径。
- `ON DELETE RESTRICT` + 双侧软删：`conversations` 与 `messages` 行都保留，外键始终完整；查询一律 `deleted_at IS NULL`。原 `CASCADE` 会在同一事务里锁 conversations + messages N 行，binlog 放大、回滚代价高。
- `REPEATABLE READ` 下 `SELECT→INSERT` 间隙锁易死锁；本实现保留 `SELECT` 快路径但以唯一约束兜底，避免 `SELECT FOR UPDATE`。读在事务外执行，规避了"事务内重查读旧快照"的陷阱（见 §2.3）。

### 3.2 HikariCP 与 Tomcat 线程池

`maximumPoolSize=20` 限制并发 DB 连接，避免 MySQL `max_connections` 打爆；`minimumIdle=5` 常驻连接；`connectionTimeout=3000` 快失败。Tomcat `max=200` 与 Hikari 20 配合：200 HTTP 线程复用 20 DB 连接。本次把限流与 Redis 调用移出事务后，DB 连接只在真正写库时持有，连接池压力进一步下降。

### 3.3 Lua 限流原子性

`RedisRateLimiter` 脚本：`INCR key; IF c==1 THEN EXPIRE key window` 两步原子执行；多实例共享同一 Redis 计数。对比"先 GET 再 INCR"会有竞态。`InMemoryRateLimiter` 用 `ConcurrentHashMap<Deque>` 滑动窗口，仅单机有效；带 key 清扫防止攻击者轮换 IP 导致 map 无限增长。

### 3.4 JWT jti 黑名单与 TTL

`jti` 为每次签发的随机 UUID，存于 `id` 与 `claim jti`。`logout` 时 `extractExpiration` 算剩余 TTL，`SET blacklist:jti EX ttl`，Filter 每次解析先查 Redis，黑名单命中即视为未登录。TTL 与 token 过期对齐，避免永久膨胀。装配用 `ObjectProvider` 选型：生产（Redis 自动配置生效）→ `RedisTokenBlacklist` 多实例共享；测试（排除 Redis 自动配置）→ `InMemoryTokenBlacklist`。

### 3.5 登录失败计数：Redis 主、DB 兜底

需求 2.3 要求"连续失败 5 次锁定 15 分钟（Redis 计数 + users.lock_until）"。`AuthService.handleFailedAttempt` 优先 `INCR login:fail:{email}`（首次设置 TTL=锁定时长），达阈值才落库 `lock_until` 并清零计数——密码爆破风暴下每个失败请求不必都打一行 UPDATE。Redis 故障时退化为 DB 计数。`lock_until` 始终是最终仲裁：锁定期内即使密码正确也返回 429。

---

## 4. 知识点与应用对照

| 知识点 | 项目位置 | 触发方式 | 可观察结果 | 换种写法后果 |
| --- | --- | --- | --- | --- |
| 双侧软删 + RESTRICT | `V3`,`V5`,`ConversationService.delete` | 删除 1001 条消息的会话 | 循环 2 次 `UPDATE LIMIT 1000` + 1 次 `UPDATE conversations`；行保留 | 只软删 messages：RESTRICT 阻止删会话 → 500 |
| `@ConditionalOnBean` 时机 | `RateLimitConfiguration`/`AuthConfiguration` | 生产启 Redis vs 测试排除 | 生产用 Redis 实现，测试用内存 | 注解写在 `@Component` 上：永不生效，静默退化为单机 |
| 事务外读 + 事务内写 | `MessageService.send` | 12 线程同 key 并发 | 库中仅 2 行，无 500 | 事务内重查：RR 快照读不到胜者 → `IllegalStateException` |
| Lua 原子限流 | `RedisRateLimiter` | 并发 50 线程同 key `INCR` | 多实例计数一致 | 非原子 GET+INCR：并发超卖 |
| jti 黑名单 | `JwtTokenServiceImpl`,`AuthConfiguration`,`AuthController.logout` | logout 后再带旧 Cookie 调 `/me` | 401，Filter 拦截 | 仅清 Cookie：复制 token 仍可用至过期 |
| 登录失败锁定 | `AuthService`,`AuthenticationExceptionHandler` | 连续 5 次错误密码 | 第 6 次 429 + `Retry-After` | 异常未映射：返回 500 |
| 游标/深分页保护 | `MessageService.listMessages` `page*size>=1000` | `page=20 size=50` | 400 | 无保护：`OFFSET 100000` 全表扫描 |
| 专用异常 → 429/400 | `ChatExceptionHandler`,`AuthenticationExceptionHandler` | 超限抛 `RateLimitExceededException` | `Retry-After` 头 | 字符串分流：映射易错 |

---

## 5. 换种写法会怎样

- **只软删 messages、会话仍物理删**：RESTRICT 外键阻止删除带消息的会话 → 500（V3 的实际 bug，V5 修正）。
- **限流写在 Controller 层**：绕过滤器直接调 Service 仍可刷；Filter 在安全链之前可拦截未认证流量。
- **`@ConditionalOnBean` 写在被扫描组件上**：条件恒 false，Redis 实现永不装配，多实例限流被放大。
- **幂等只靠 Redis**：Redis 闪断或 `EX 30s` 过期后重放会写重复；DB 约束为最终防线。
- **JWT 用黑名单不设 TTL**：Redis 无限增长；与过期对齐才可控。
- **事务内捕获 DuplicateKeyException 后重查**：RR 快照读不到并发已提交的行 → 500。
- **测试复用生产限流阈值 10/min**：100+ MockMvc 请求在同一 JVM 累积必触发 429（已验证，调至 1000 后通过）。

---

## 6. 验证实验

### 6.1 已执行（2026-08-25）

```bash
mvn test
```

- `ChatFlowIntegrationTests`（新增）：发送消息对、`clientMessageId` 重试返回原 id 不新增行、不同 content 返回 409、**12 线程并发同 key 库中仅 2 行**、深分页 400、游标分页、会话软删后再次删除 404、越权 404。
- `AuthLockoutTests`（新增，独立上下文 `lock-threshold=2`）：连续失败达阈值后密码正确也 429 + `Retry-After`，DB 落 `lock_until`。
- `AuthControllerTests`（新增用例）：logout 后旧 Cookie 调 `/me` → 401（黑名单生效）。
- `RateLimitFilterTests`（新增，纯单元）：超限 429 + `X-RateLimit-Limit` 为真实阈值；register/login 独立桶；非限流路径放行；XFF 默认不信任。
- 原有 109 个测试全绿。编码修复：4 个 GBK 文件以 UTF-8 重写。

> **两个测试期踩到的坑（都是真实生产隐患）**：
> 1. **Spring Boot 4 把 Redis 自动配置拆到独立模块并改名**：旧名
>    `org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration`
>    已不存在，新名是 `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`（+ Reactive/Repositories 变体）。
>    用 `spring.autoconfigure.exclude` 排除旧名不会报错也不会生效——`StringRedisTemplate` 仍被创建，
>    `AuthConfiguration` 的 ObjectProvider 选了 `RedisTokenBlacklist`，但测试没起 Redis，
>    所有 Redis 操作被 `catch` 静默吞掉，黑名单形同未启用。排除名必须用 Boot 4 的新类名。
> 2. **`Clock.systemUTC()` 与 `WHERE col > NOW()` 时区错位**：Java 端时间戳存成 UTC，
>    H2/MySQL 的 `NOW()` 返回会话时区（本机 UTC+8），`lock_until > NOW()` 恒为假。
>    纯 Java 比较（`isAfter`）正常，所以"锁定 429"能通过、"DB 落 `lock_until`"断言失败。
>    生产部署在非 UTC 时区服务器时，任何 `col > NOW()` 查询都会踩同样的坑——
>    要么让 Clock 用 `systemDefaultZone()`，要么让 DB 会话时区与 UTC 对齐。本次先在测试断言里规避，列为后续修复项。

### 6.2 待补 / 建议实验

| 实验 | 步骤 | 预期 |
| --- | --- | --- |
| k6 压测 | `k6 run k6/industrial.js` 混合 100/200 QPS 30s | 注册/登录 p99<300ms，发送 p99<500ms，错误率<0.1% |
| MySQL 实测 | `docker-compose up -d mysql redis && mvn flyway:migrate` | V3/V4/V5 在 MySQL 8.0 执行无报错 |
| Redis 实测 | 启动 redis 后跑限流/黑名单路径 | 多实例计数一致；logout 即时吊销 |
| 真实并发压测 | 50 线程同 key 打 MySQL（非 H2） | 库中仅 2 行（H2 12 线程已验证同不变量） |

> k6 未在本机离线环境执行，脚本与 SLO 已在需求文档固定，待接入 CI 后归档真实 `p(99)` 数据。

---

## 7. 常见误区与面试问题

**误区：**
1. "级联删除省事" → 大事务持锁久、回滚代价高，生产禁用。
2. "`@ConditionalOnBean` 写哪都行" → 写在被扫描组件上会因求值时机过早而失效，是隐蔽的死代码陷阱。
3. "Redis 计数够分布式" → 非原子操作会超卖，需 Lua。
4. "JWT 无状态就不用管 logout" → 需 jti 黑名单或短 TTL + Refresh 轮转。
5. "幂等靠生成 UUID 即可" → 必须服务端以 `(conversation_id, clientMessageId)` 唯一约束仲裁。
6. "事务内捕获唯一约束异常后重查就行" → RR 隔离下重查读旧快照，必须把重查移到事务外。
7. "限流过滤器读 JSON body 做账号级限流" → 会消费 InputStream，JSON 请求体在 Filter 里无法安全读取；账号级防刷应放到解析 body 之后的 Service 层。

**面试题（附答案要点）：**
1. *软删 vs 硬删？* 软删可恢复、审计；需 `deleted_at IS NULL` 过滤与索引，定期归档。本项目会话与消息双侧软删以维持 RESTRICT 外键。
2. *Lua 为何原子？* Redis 单线程执行脚本，`INCR+EXPIRE` 不会被 interleaving。
3. *jti 黑名单如何防重放？* `SET jti EX ttl`，Filter 先查，TTL 与 exp 对齐。
4. *并发双发不同 content 处理？* `resolveExistingPair` 比对 content，不一致抛 409，避免静默覆盖。
5. *为什么限流与幂等读要放在事务外？* 避免占用 DB 连接做 Redis 调用；规避 RR 下事务内重查读不到并发已提交行的陷阱。
6. *Hikari 20/5 如何定？* 按 `QPS * avgDBTime` 估算，压测调优，本项目 100 QPS 下池利用率 < 60%。
7. *为什么本次移除 RabbitMQ？* 之前的通道是空壳且因 `@ConditionalOnBean` 时机问题永不激活；接入真实模型时再实现完整 `PENDING→MQ→DONE` 状态机。

---

## 8. 可操作的小实验或修改练习

1. **验证黑名单失效边界**：logout 后在 TTL 内带旧 Cookie 调 `GET /me` 应 401；等待 `exp` 后再调仍 401（token 已过期）。
2. **压测深分页**：`page=50 size=50`（2500）验证 400 分流；改成 `cursor` 后 `EXPLAIN` 对比 `OFFSET` 执行计划。
3. **故障注入**：让 `StringRedisTemplate` 抛异常，验证 `RedisRateLimiter` fail-open 与 `MessageService` 降级到 DB 兜底仍正确。
4. **把限流改成滑动窗口**：将 `RedisRateLimiter` 脚本改为 `ZSET` 滑动窗口，对比固定窗口边界突发。
5. **软删归档**：新增定时任务将 `deleted_at < NOW() - 30d` 的行物理归档，验证不影响在线查询。

> 源码短注释：相关类头部均保留 `学习笔记：docs/learning/milestone-06-industrial-hardening.md#x.x` 指向本篇对应章节。

---

## 状态

| 日期 | 状态 |
| --- | --- |
| 2026-08-25 | 工业级重构完成并审查修复：V3/V4/V5、限流/幂等/黑名单/登录失败计数、Hikari/Tomcat、可观测；移除永不激活的 RabbitMQ 脚手架；补全聊天/锁定/限流/黑名单测试。`mvn test` 全绿 |
