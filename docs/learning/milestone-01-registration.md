# 里程碑 01：用户注册

本文对应当前已经实现的邮箱注册垂直切片。它从一次 `POST /register` 请求出发，解释请求如何经过 Spring MVC、业务校验、BCrypt、MyBatis-Plus 和数据库约束，最终变成一条用户记录或一个稳定的错误响应。

## 1. 阅读范围与版本

### 1.1 对应代码

- HTTP 入口：[RegisterController.java](../../src/main/java/yangsirly/rag_agent/registration/RegisterController.java)
- HTTP 请求模型：[RegisterRequest.java](../../src/main/java/yangsirly/rag_agent/registration/RegisterRequest.java)
- 业务命令：[RegisterCommand.java](../../src/main/java/yangsirly/rag_agent/registration/RegisterCommand.java)
- 注册服务：[RegisterService.java](../../src/main/java/yangsirly/rag_agent/registration/RegisterService.java)
- 业务用户数据：[User.java](../../src/main/java/yangsirly/rag_agent/registration/User.java)
- 表映射对象：[UserEntity.java](../../src/main/java/yangsirly/rag_agent/registration/UserEntity.java)
- 持久化接口：[UserMapper.java](../../src/main/java/yangsirly/rag_agent/registration/UserMapper.java)
- MyBatis-Plus 配置：[MybatisPlusConfiguration.java](../../src/main/java/yangsirly/rag_agent/registration/MybatisPlusConfiguration.java)
- 密码组件配置：[RegistrationConfiguration.java](../../src/main/java/yangsirly/rag_agent/registration/RegistrationConfiguration.java)
- 异常映射：[RegistrationExceptionHandler.java](../../src/main/java/yangsirly/rag_agent/registration/RegistrationExceptionHandler.java)
- 数据库迁移：[V1__create_users_table.sql](../../src/main/resources/db/migration/V1__create_users_table.sql)
- Service 测试：[RegisterServiceTests.java](../../src/test/java/yangsirly/rag_agent/registration/RegisterServiceTests.java)
- HTTP 集成测试：[RegisterControllerTests.java](../../src/test/java/yangsirly/rag_agent/registration/RegisterControllerTests.java)
- 真实 MySQL 集成测试：[MySqlRegistrationIntegrationTests.java](../../src/test/java/yangsirly/rag_agent/registration/MySqlRegistrationIntegrationTests.java)
- MySQL 测试配置：[application-mysql.properties](../../src/test/resources/application-mysql.properties)

### 1.2 适用版本

本文按以下项目版本整理：

- Spring Boot 4.1.0
- MyBatis Spring Boot Starter 4.0.1
- MyBatis-Plus 3.5.17
- Flyway 12.4.0
- MySQL Connector/J 9.7.0
- 实际验证数据库 MySQL 8.0.40
- Java 25

框架升级后，特别要重新验证自动配置、事务异常翻译和 Mapper 注册方式。

## 2. 当前实现范围

### 2.1 已实现的外部行为

| 场景 | HTTP 结果 | 状态变化 |
| --- | --- | --- |
| 合法邮箱和密码首次注册 | `201 Created` | `users` 表新增一条 `CUSTOMER + ACTIVE` 用户 |
| 请求缺少邮箱或密码 | `400 Bad Request`，`INVALID_REGISTER_REQUEST` | 不进入注册 Service，不写数据库 |
| 同一规范化邮箱重复注册 | `409 Conflict`，`EMAIL_ALREADY_REGISTERED` | 第二次写入失败并回滚，已有用户不受影响 |

注册过程中还会执行以下规则：

- 邮箱去除首尾空白并使用 `Locale.ROOT` 转成小写。
- 邮箱最长 254 个字符，并通过当前项目的基础正则校验。
- 密码按 Unicode 码点计算，要求 8～64 个字符。
- 明文密码经过 BCrypt 后才进入待持久化用户对象。
- 外部请求不能指定 `EDITOR` 或 `DISABLED`；新用户固定为 `CUSTOMER + ACTIVE`。

### 2.2 当前非目标

以下能力尚未实现，不能从当前注册代码推断它们已经存在：

- 手机号注册和验证码
- 登录、会话、Token 和退出登录
- 找回或修改密码
- 管理员授予 `EDITOR`
- 邮箱验证
- 返回新用户 ID 或用户详情
- 注册审计日志、业务指标和告警

### 2.3 已知边界与风险

这些是当前项目事实，不是框架已经替我们解决的问题：

1. Web 层只用 `@NotBlank` 拒绝空字段。格式错误的邮箱或长度错误的密码会由 Service 抛出 `IllegalArgumentException`，目前还没有映射成稳定的 HTTP 400。
2. 当前邮箱正则是基础格式检查，不是完整的 RFC 邮箱解析器。
3. BCrypt 常见实现存在 72 字节输入边界。项目目前按 Unicode 码点限制 8～64 个字符，但尚未测试多字节密码超过 BCrypt 字节边界时的行为。
4. MySQL 8.0.40、Flyway v1、首次注册和重复邮箱路径已经实际验证，但默认测试仍使用 H2；真实 MySQL 测试需要显式开启，尚未接入独立 CI 数据库。
5. 重复邮箱识别依赖异常链中出现约束名 `uk_users_email`。H2 与当前 MySQL Connector/J 路径均已验证，升级数据库或驱动后需要重新核验。
6. 本机数据库密码存放在 Git 忽略的 `.local/application.properties`，不能复制到源码、测试或可提交的示例配置中。

## 3. 从一次 HTTP 请求看完整调用链

客户端发送：

```http
POST /register
Content-Type: application/json

{
  "email": "  User@Example.COM  ",
  "password": "password"
}
```

正常路径如下：

```text
HTTP 请求
  │
  ▼
DispatcherServlet
  │  根据 @PostMapping 找到 Controller 方法
  ▼
RegisterRequest
  │  JSON 反序列化 + @Valid/@NotBlank
  ▼
RegisterController
  │  Request → RegisterCommand
  ▼
RegisterService.register()  ← @Transactional 事务边界
  │
  ├─ 规范化并校验邮箱
  ├─ 校验密码长度
  ├─ PasswordEncoder.encode() 生成 BCrypt 哈希
  ├─ 创建 CUSTOMER + ACTIVE 的 User
  └─ User → UserEntity
        │
        ▼
UserMapper.insert()
        │  MyBatis-Plus 生成并执行 INSERT
        ▼
MySQL/H2 users 表
        │
        ├─ 成功：事务提交 → HTTP 201
        └─ uk_users_email 冲突
             ▼
          DuplicateKeyException
             ▼
          EmailAlreadyRegisteredException
             ▼
          RegistrationExceptionHandler → HTTP 409
```

这里有两个重要边界：

- **信任边界**：HTTP 请求来自外部，必须经过结构校验和业务校验。
- **事务边界**：`RegisterService.register()` 内的数据库操作作为一个整体提交或回滚。

## 4. 为什么要有 Request、Command、User 和 UserEntity

这四个类型字段有重复，但职责不同。

| 类型 | 所属层 | 表达的含义 | 是否信任 | 是否依赖框架 |
| --- | --- | --- | --- | --- |
| `RegisterRequest` | Web 层 | 客户端提交的 JSON | 不可信 | 依赖 Bean Validation |
| `RegisterCommand` | 应用/业务入口 | Controller 交给注册流程的命令 | 只完成基础结构校验 | 无 HTTP 注解 |
| `User` | 业务层 | 已完成注册规则处理、准备持久化的用户数据 | 已满足当前业务不变量 | 无持久化注解 |
| `UserEntity` | 持久化层 | Java 字段如何映射到 `users` 表 | 由 Service 构造 | 依赖 MyBatis-Plus 注解 |

### 4.1 为什么 Request 和 Command 不直接共用

现在它们字段相同，但变化原因不同：

- `RegisterRequest` 可能因为 HTTP 协议、字段命名或前端兼容而变化。
- `RegisterCommand` 应围绕注册用例变化，不应被 JSON 结构绑住。

如果 Service 直接接收 `RegisterRequest`，业务层就会开始依赖 Web 层模型。未来即使从消息队列、批处理或命令行触发注册，也会被迫伪造一个 HTTP Request 对象。

### 4.2 为什么 User 和 UserEntity 分开

`User` 是不可变 record，适合表达“已经准备好的业务数据”；`UserEntity` 负责 `@TableName`、`@TableId` 和 `@TableField` 等表映射。

分离的收益：

- 业务对象不依赖 MyBatis-Plus。
- 数据库列名或持久化框架变化时，业务规则不必一起变化。
- 明确限制明文密码不能进入持久化对象。

主要代价：

- 需要维护 `UserEntity.from(user)` 转换。
- 字段变化时要同步检查两个类型和迁移脚本。

对于当前学习项目，这个边界有真实价值，因为持久化方案已经从 JPA 改成 MyBatis-Plus，而 `User` 的业务含义没有随之改变。

## 5. Spring MVC、JSON 绑定与参数校验

### 5.1 Controller 是怎样被找到的

`RagAgentApplication` 上的 `@SpringBootApplication` 启动组件扫描。`RegisterController` 带有 `@RestController`，因此会被创建成 Spring Bean。

请求进入 `DispatcherServlet` 后，Spring 根据：

```java
@PostMapping("/register")
```

找到注册方法。

### 5.2 JSON 如何变成 record

`@RequestBody` 告诉 Spring 使用 HTTP 消息转换器读取请求体。JSON 字段名与 record 组件名一致时：

```json
{"email":"user@example.com","password":"password"}
```

会被构造成：

```text
RegisterRequest[email=user@example.com, password=password]
```

### 5.3 `@Valid` 在什么时候执行

反序列化成功后，`@Valid` 触发 Bean Validation。当前两个字段都使用 `@NotBlank`，它会拒绝：

- `null`
- 空字符串 `""`
- 只包含空白的字符串

校验失败时，Spring 在调用 Controller 方法之前抛出 `MethodArgumentNotValidException`。因此无效请求不会创建 `RegisterCommand`，也不会进入事务。

### 5.4 为什么还需要 Service 校验

Web 校验只保护 HTTP 入口，Service 可能被测试、任务或其他入口直接调用。因此 Service 仍然检查：

- command、email、password 是否为 `null`
- 邮箱规范化后的格式和长度
- 密码字符数

通用原则是：

```text
Web 层校验输入结构和协议约束
Service 校验业务规则和业务不变量
数据库校验最终数据完整性
```

三层校验不是简单重复，而是分别保护不同边界。

## 6. 邮箱规范化与密码规则

### 6.1 为什么邮箱要先规范化

当前规则：

```java
email.strip().toLowerCase(Locale.ROOT)
```

例如：

```text
"  User@Example.COM  " → "user@example.com"
```

如果不规范化，数据库可能把视觉上相同的邮箱当成不同输入，唯一约束也可能因为数据库排序规则差异表现不一致。

`Locale.ROOT` 表示使用与具体国家和语言无关的大小写规则。标识符规范化不应依赖服务器默认地区，否则同一输入可能在不同部署环境得到不同结果。

### 6.2 为什么密码不能 `strip()`

邮箱首尾空格通常是输入噪声，密码中的空格却可能是用户有意设置的字符。擅自修改密码会导致：

- 用户输入和实际哈希内容不一致。
- 登录时出现难以解释的失败。
- 密码空间被无意缩小。

因此当前代码只计算密码字符数，不修改密码内容。

### 6.3 `codePointCount` 解决什么问题

Java `String.length()` 返回 UTF-16 代码单元数量，不一定等于用户看到的 Unicode 字符数量。`codePointCount()` 对 emoji 等补充平面字符更接近“字符数”。

但它仍不等于：

- 用户感知的字素簇数量，例如带组合符号的字符。
- UTF-8 字节数。

这也是为什么“8～64 个字符”和“BCrypt 最多处理多少字节”是两个不同问题。

## 7. BCrypt：为什么哈希而不是加密

### 7.1 哈希与加密的区别

| 方式 | 能否还原 | 密码存储是否适合 |
| --- | --- | --- |
| 对称加密 | 有密钥即可解密 | 不适合；密钥泄露会暴露全部密码 |
| 普通快速哈希 | 通常不可逆，但计算太快 | 不适合；攻击者可高速猜测 |
| BCrypt 等密码哈希 | 不可逆且故意计算较慢 | 适合密码验证 |

登录时不需要解密密码，而是执行：

```text
PasswordEncoder.matches(用户本次输入, 数据库中的 BCrypt 哈希)
```

### 7.2 盐值在哪里

BCrypt 每次编码会生成随机盐，并把算法标识、成本、盐和结果编码在最终字符串中。因此同一个明文密码多次编码，结果通常不同，但 `matches()` 都能验证成功。

这能防止攻击者仅通过比较哈希判断哪些用户使用了相同密码，也削弱预计算彩虹表的效果。

### 7.3 strength 的意义

项目通过：

```properties
security.password.bcrypt-strength=${BCRYPT_STRENGTH:12}
```

配置 BCrypt 成本。成本每增加 1，计算工作量大致翻倍。提高成本可以增加离线破解代价，但也会增加注册和登录的 CPU 时间。

项目选择：

- 生产默认值：12
- 测试值：4

测试使用较低成本是为了缩短测试时间，不代表生产也应该使用 4。

### 7.4 为什么注入 `PasswordEncoder` 接口

`RegisterService` 依赖 `PasswordEncoder`，而不是直接 `new BCryptPasswordEncoder()`：

- 算法和成本集中在配置类中。
- 测试可以使用较低成本实现。
- 未来迁移到其他编码器时，业务代码不必负责创建对象。

这里的依赖注入不是为了“多一层抽象”，而是因为密码算法和成本确实是会变化、需要测试替换的边界。

### 7.5 明文密码的生命周期

当前数据流是：

```text
RegisterRequest.password
  → RegisterCommand.password
  → PasswordEncoder.encode()
  → User.passwordHash
  → UserEntity.passwordHash
  → users.password_hash
```

`User` 和 `UserEntity` 都没有明文密码字段。仍需遵守：

- 不在日志中输出 Request、Command 或明文密码。
- 不把密码写入异常消息。
- 不在测试失败消息里打印真实用户密码。

## 8. MyBatis-Plus 持久化是怎样工作的

### 8.1 Mapper 接口为什么没有实现类

`UserMapper`：

```java
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
```

启动时，MyBatis 扫描 `@Mapper` 接口并创建代理对象。Service 调用代理的 `insert()` 后，MyBatis-Plus 根据 `UserEntity` 元数据生成映射语句，再通过 MyBatis 和 JDBC 执行 SQL。

可以把调用理解成：

```text
Java 接口调用
→ Mapper 代理
→ MyBatis MappedStatement
→ PreparedStatement 参数绑定
→ JDBC Driver
→ 数据库
```

### 8.2 映射注解分别做什么

`UserEntity` 中：

- `@TableName("users")`：指定表名。
- `@TableId(type = IdType.AUTO)`：主键由数据库自增生成。
- `@TableField("password_hash")`：明确 Java 字段与数据库列的对应关系。
- `mapUnderscoreToCamelCase=true`：允许 `password_hash` 与 `passwordHash` 这类命名自动映射。

即使开启了驼峰映射，关键或不直观字段保留显式 `@TableField` 也能减少阅读歧义。

### 8.3 为什么项目有自定义 SessionFactory

当前依赖组合是：

- 官方 MyBatis Spring Boot Starter 4.0.1：提供 Spring Boot 4 的数据源、事务和 MyBatis 集成。
- MyBatis-Plus 3.5.17：提供 `BaseMapper` 和增强能力。

截至 2026-07-16 核对 Maven Central 时，尚未找到 MyBatis-Plus 的 Boot 4 专用 Starter；项目也没有使用面向 Spring Boot 3 的 Starter。当前通过 `MybatisSqlSessionFactoryBean` 显式创建 MyBatis-Plus 的 `SqlSessionFactory`，确保 `BaseMapper` 的语句注入机制生效。

这是一项版本兼容决策。未来如果 MyBatis-Plus 提供并验证了 Boot 4 专用 Starter，应重新评估是否可以删除手工配置。

### 8.4 MyBatis 与 JPA 在这里的关键区别

当前 `insert()` 调用时 SQL 会直接执行。它没有 JPA 持久化上下文、脏检查或延迟 flush，因此数据库错误会在 `userMapper.insert()` 调用点出现。

但“立即执行 SQL”不等于“立即提交事务”：

```text
insert：向数据库执行 SQL
commit：确认整个事务成功
rollback：撤销当前事务内的修改
```

### 8.5 为什么检查 `insertedRows == 1`

注册成功的不变量是：一次注册必须新增且只新增一条用户记录。

如果 Mapper 意外返回 0，而 Service 仍返回 201，客户端会认为注册成功，数据库却没有用户。当前代码因此检查：

```text
insertedRows != 1 → IllegalStateException → 事务回滚 → HTTP 500
```

这个错误不应转换成用户输入错误，因为它更可能代表 Mapper、SQL 或数据库状态异常。

## 9. `@Transactional` 的底层过程

### 9.1 Spring 如何开启事务

`RegisterService` 是 Spring Bean，`register()` 是由 Controller 通过 Bean 代理调用的公共方法。Spring 事务拦截器在方法前后执行：

```text
调用代理
→ 获取数据库连接
→ 关闭自动提交并开启事务
→ 执行 register()
→ 正常返回：commit
→ 抛出运行时异常：rollback
→ 释放连接
```

MyBatis 使用同一个 Spring 管理的数据源，因此 Mapper 执行的 SQL 会加入这个事务。

### 9.2 为什么 `insert()` 已执行仍能回滚

数据库执行 SQL 后，修改先属于当前事务。只有 commit 后才成为最终结果。若 Service 随后抛出运行时异常，Spring 会 rollback，已经执行的 INSERT 也会被撤销。

### 9.3 当前事务边界保护什么

目前注册只有一次数据库写入，事务看起来作用不大，但它已经确定了正确的业务边界。未来如果注册增加用户资料、审计记录或初始化数据，这些写入应明确决定是否与用户创建保持原子性。

不应把发送邮件、调用第三方服务等慢外部调用长期放在数据库事务里，否则会占用连接并延长锁持有时间。

### 9.4 常见代理陷阱

`@Transactional` 依赖 Spring 代理。常见错误包括：

- 自己 `new RegisterService(...)` 后期待事务生效。
- 同一个类内部使用 `this.register()` 自调用。
- 把事务方法改成无法被代理拦截的形式，却没有验证。

单元测试中手工创建 Service 是为了测试业务逻辑，不会验证事务代理；事务行为需要 Spring 集成测试或真实数据库测试覆盖。

## 10. 并发注册与数据库唯一约束

### 10.1 为什么“先查再写”不够

错误方案：

```text
if (!exists(email)) {
    insert(email)
}
```

两个请求并发时可能发生：

```text
时间  请求 A                         请求 B
T1    查询：邮箱不存在
T2                                   查询：邮箱不存在
T3    尝试 INSERT
T4                                   尝试 INSERT
```

查询和插入之间存在竞态窗口。应用层预查询最多改善提示或减少部分失败写入，不能成为唯一性保证。

### 10.2 真正的不变量放在哪里

迁移脚本定义：

```sql
CONSTRAINT uk_users_email UNIQUE (email)
```

数据库负责对所有写入入口执行同一规则。无论请求来自哪个应用实例，只允许一个事务最终拥有该邮箱。

关键不变量：

```text
同一个规范化邮箱最多对应一条 users 记录。
```

### 10.3 第二个并发请求如何失败

大致异常链为：

```text
数据库拒绝重复唯一键
→ JDBC SQLException
→ MyBatis-Spring 异常翻译
→ DuplicateKeyException
→ RegisterService 检查 uk_users_email
→ EmailAlreadyRegisteredException
→ RegistrationExceptionHandler
→ HTTP 409 EMAIL_ALREADY_REGISTERED
```

`EmailAlreadyRegisteredException` 是运行时异常，因此当前事务回滚。

### 10.4 为什么不能把所有数据库异常都报成邮箱重复

`DataIntegrityViolationException` 还可能来自：

- `password_hash` 写入 `null`
- `role` 不满足 `ck_users_role`
- `status` 不满足 `ck_users_status`
- 字段长度超过数据库限制
- 未来新增的外键约束失败

如果把这些错误都转换成“邮箱已注册”，就会隐藏迁移、映射或程序 Bug。当前 Service 只捕获 `DuplicateKeyException`，并进一步检查异常链中的 `uk_users_email`。

## 11. 数据库迁移与约束

### 11.1 Flyway 是表结构的事实来源

生产配置启用了 Flyway。`V1__create_users_table.sql` 描述了：

- 自增主键
- 邮箱和手机号唯一约束
- BCrypt 哈希列
- 角色和状态检查约束
- 创建、更新时间
- `email` 和 `phone` 至少有一个存在

MyBatis-Plus 负责映射和执行 SQL，不负责替代数据库迁移。表结构变化应新增 Flyway 版本脚本，而不是依靠运行时自动改表。

真实接入时曾发现：只引入 `flyway-core` 和 `flyway-mysql`，Spring Boot 4.1 应用虽然能连接 MySQL，却不会自动执行迁移。原因是 Boot 4 将 Flyway 自动配置拆到了独立模块。项目改用 `spring-boot-starter-flyway` 后，启动日志才真实出现：

```text
Creating Schema History table rag_agent.flyway_schema_history
Migrating schema rag_agent to version "1 - create users table"
Successfully applied 1 migration
```

这说明“依赖在 classpath 中”不等于“Spring Boot 已经启用对应自动配置”。遇到表没有创建时，应检查启动日志和 Bean 条件，而不是先手工建表绕过迁移。

### 11.2 为什么数据库仍要有默认值和 CHECK

Service 已固定角色和状态，但数据库约束仍有价值：

- 防止脚本或其他服务绕过当前 Service 写入非法值。
- 在程序 Bug 出现时尽早拒绝坏数据。
- 把关键数据不变量放在最接近数据的位置。

### 11.3 H2 默认测试与真实 MySQL 测试

默认测试使用 H2 MySQL 模式，并通过 `src/test/resources/schema.sql` 建表；默认测试中关闭 Flyway。这样可以快速执行大部分测试，不要求每位开发者都启动 MySQL。

项目另外提供默认跳过的 `MySqlRegistrationIntegrationTests`。显式开启后，它会：

- 断言当前数据库产品确实为 MySQL。
- 断言 Flyway 当前版本为 1。
- 通过真实 HTTP 链路注册用户。
- 查询 MySQL 确认用户已经写入。
- 再次注册并断言返回 409。
- 在测试事务结束后回滚，不留下测试用户。

保留两种测试是因为 H2 仍不能完全替代 MySQL：

- SQL 方言存在差异。
- 约束异常消息和错误码可能不同。
- 字符集、排序规则和大小写行为不同。
- MySQL 的迁移脚本并没有在 H2 默认测试中执行。

## 12. 测试策略：每层验证什么

### 12.1 Service 单元测试

`RegisterServiceTests` 使用真实 BCrypt 编码器和 Mock `UserMapper`，验证：

- 邮箱规范化
- 密码确实是可验证的 BCrypt 哈希
- 默认角色和状态
- 传给 Mapper 的持久化对象内容
- 插入行数不是 1 时失败
- 邮箱唯一约束异常转换
- 其他完整性错误不会被误报为邮箱重复

Mock 的价值是稳定制造罕见失败路径，但它不能证明 Mapper、SQL 和数据库真的能协作。

### 12.2 HTTP 集成测试

`RegisterControllerTests` 使用：

```java
@SpringBootTest
@AutoConfigureMockMvc
```

加载真实 Spring 容器、Controller、Service、事务、MyBatis-Plus Mapper 和 H2 数据库。它验证可观察行为：

- 首次注册返回 201
- 第二次相同邮箱由真实数据库唯一约束拒绝并返回 409
- 空请求在进入核心流程前返回 400

### 12.3 真实 MySQL 集成测试

`MySqlRegistrationIntegrationTests` 使用 `@EnabledIfEnvironmentVariable` 控制是否连接外部数据库。默认不设置 `RUN_MYSQL_TESTS` 时，JUnit 会在创建 Spring 容器前跳过该测试。

测试带有 `@Transactional`，HTTP 请求和后续数据库查询在测试事务中执行，结束后统一回滚。这让真实数据库验证可以重复运行，同时不污染 `users` 表。

### 12.4 启动测试

`RagAgentApplicationTests.contextLoads()` 没有显式断言。Spring 上下文能成功启动本身就验证了 Bean、Mapper、数据源和配置至少能够完成装配。

### 12.5 当前验证命令

```powershell
mvn -q test
```

2026-07-16 实际执行该命令，默认测试结果为 9 个通过、1 个真实 MySQL 测试跳过：

- 应用启动测试：1
- Controller 集成测试：3
- Service 单元测试：5
- MySQL 集成测试：1，默认跳过

显式运行真实 MySQL 测试：

```powershell
$env:RUN_MYSQL_TESTS='true'
$env:MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3306/rag_agent'
$env:MYSQL_TEST_USERNAME='<your-local-username>'
$env:MYSQL_TEST_PASSWORD='<your-local-password>'
mvn -q "-Dtest=MySqlRegistrationIntegrationTests" test
```

2026-07-16 在 MySQL 8.0.40 上实际执行结果：1 个测试通过，Flyway schema 版本为 1，测试结束后 `users` 表没有遗留测试数据。

本机直接启动时，主配置会可选导入 Git 忽略文件：

```text
.local/application.properties
```

环境变量 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 仍可用于部署环境；本机私有文件只用于开发便利，不能提交。

依赖核验命令：

```powershell
mvn dependency:tree "-Dincludes=org.mybatis.spring.boot:mybatis-spring-boot-starter,org.mybatis:mybatis,org.mybatis:mybatis-spring,com.baomidou:mybatis-plus,com.baomidou:mybatis-plus-core,com.baomidou:mybatis-plus-spring,org.hibernate.orm:hibernate-core"
```

最近一次核验显示：MyBatis 3.5.19、MyBatis-Spring 4.0.0、MyBatis-Plus 3.5.17，未包含 Hibernate Core。

## 13. 故障诊断路径

### 13.1 请求返回 400

优先检查：

1. JSON 是否能被反序列化。
2. `email`、`password` 是否缺失或只包含空白。
3. 是否由 `MethodArgumentNotValidException` 进入全局异常处理器。

### 13.2 请求返回 409

优先检查：

1. 规范化后的邮箱是否已存在。
2. 数据库是否实际触发 `uk_users_email`。
3. 异常链是否包含约束名。
4. 不要通过删除数据库唯一约束“修复”409。

### 13.3 请求返回 500

可能原因包括：

- 邮箱格式或密码长度错误触发尚未映射的 `IllegalArgumentException`
- Mapper 返回的插入行数不是 1
- 非邮箱唯一键或 CHECK 约束错误
- Mapper 没有注册
- 表、列或迁移与映射不一致
- 数据库连接失败

诊断时应保留原始异常链，但不能把明文密码写入日志。

### 13.4 Mapper 启动或调用失败

检查顺序：

1. `UserMapper` 是否带 `@Mapper`。
2. Spring 是否创建了 `SqlSessionFactory` 和 `SqlSessionTemplate`。
3. 是否使用 `MybatisSqlSessionFactoryBean` 让 MyBatis-Plus 注入 `BaseMapper` 语句。
4. `@TableName`、`@TableId`、`@TableField` 是否与迁移脚本一致。
5. 数据源和测试 schema 是否已经初始化。

## 14. 方案取舍

| 方案 | 优点 | 代价 | 当前项目选择 |
| --- | --- | --- | --- |
| 原生 JDBC | 行为最直接 | 连接、语句和异常处理样板多 | 不采用 |
| `JdbcTemplate` | SQL 清楚，Spring 异常翻译完善 | CRUD 需要手写 SQL | 曾采用，后改造 |
| 原生 MyBatis | SQL 控制强，适合复杂查询 | 简单 CRUD 仍需映射语句 | 可作为复杂 SQL 的补充 |
| MyBatis-Plus | 保留 MyBatis 控制力，减少基础 CRUD | 增加注解、自动语句和版本兼容成本 | 当前采用 |
| JPA/Hibernate | 实体关系和常规 CRUD 能力丰富 | 持久化上下文、flush、代理和隐式行为更复杂 | 曾采用，当前移除 |

MyBatis-Plus 不会自动让代码“更生产级”。生产质量仍然取决于：

- 事务边界是否正确
- SQL 和索引是否合理
- 数据库约束是否存在
- 异常是否精确映射
- 测试是否覆盖真实数据库边界
- 日志、指标和告警是否足够

## 15. 常见误区

### 误区一：使用了 `@Transactional` 就不需要数据库约束

事务保证一组操作的原子性，不自动保证两个并发事务不会写入相同业务键。唯一性仍应由数据库唯一约束保证。

### 误区二：注册前查询不存在，就一定可以插入

查询结果只代表查询时刻。查询和插入之间，其他事务可以完成写入。

### 误区三：BCrypt 哈希等于加密后的密码

哈希不应被解密。验证方式是 `matches()`，不是取回明文。

### 误区四：MyBatis-Plus 不再使用 JDBC

MyBatis-Plus 最终仍通过 MyBatis、MyBatis-Spring 和 JDBC Driver 访问数据库。它减少的是上层映射和 CRUD 样板，不是绕过 JDBC。

### 误区五：单元测试 Mock 了 Mapper，就证明数据库能写入

Mock 只能证明 Service 如何调用边界。真实映射、SQL、事务和约束需要集成测试验证。

### 误区六：捕获所有异常并返回友好消息就是健壮

错误分类不准确会隐藏真实故障。业务冲突可以稳定映射，未知数据库错误应保留原因并进入监控，而不是伪装成用户错误。

## 16. 面试问题与回答要点

### 16.1 为什么同时有 `User` 和 `UserEntity`

回答应包含：领域数据与持久化映射职责不同、业务层不依赖框架、转换成本，以及项目从 JPA 切换到 MyBatis-Plus 时业务对象没有变化这一实例。

### 16.2 为什么不能只用 `existsByEmail` 防止重复注册

回答应包含：检查与写入不是原子操作、并发竞态窗口、数据库唯一约束是最终权威、第二个事务如何失败。

### 16.3 MyBatis 的 `insert()` 已执行，为什么还能回滚

回答应区分 SQL 执行与事务提交，并说明 Spring 事务代理、同一连接以及运行时异常触发 rollback。

### 16.4 为什么只转换 `uk_users_email`

回答应指出其他完整性异常可能代表程序或迁移错误；错误地统一转换会隐藏根因。

### 16.5 为什么测试 BCrypt 使用较低 strength

回答应说明成本因子影响计算时间、测试需要速度、生产需要抗破解成本，以及测试值不能误用于生产。

### 16.6 MyBatis-Plus 相比 JPA 少了哪些概念

可以从持久化上下文、脏检查、延迟 flush、实体代理和关联加载回答，同时说明 MyBatis-Plus 仍然需要事务、数据源、JDBC 和数据库约束。

## 17. 可操作的小实验

### 实验一：验证事务回滚

目标：证明 SQL 已执行不等于事务已提交。

1. 编写 Spring 集成测试。
2. 在事务方法完成 `insert()` 后主动抛出运行时异常。
3. 使用 Mapper 或 JDBC 查询该邮箱。
4. 断言记录不存在。

不要只 Mock Mapper，因为 Mock 无法验证真实回滚。

### 实验二：验证规范化后的唯一性

依次注册：

```text
User@Example.COM
  user@example.com  
```

预期第二次返回 409。这个实验同时验证邮箱规范化和数据库唯一约束的组合效果。

### 实验三：增加并发注册测试

用两个线程同时注册同一邮箱，并使用同步屏障尽量让两个请求同时进入写入阶段。断言：

- 一个请求成功。
- 一个请求返回重复邮箱冲突。
- 数据库最终只有一条记录。

这比顺序发送两次请求更接近需求文档中的并发失败场景。

### 实验四：测量 BCrypt 成本

分别使用 strength 4、10、12 编码同一测试密码，记录多次执行耗时。不要只比较一次，因为 JVM 预热和机器负载会影响结果。

实验结论应包含测量环境和数据，不要只写“12 更安全但更慢”。

### 实验五：补齐非法格式的 HTTP 错误映射

先增加失败测试：非法邮箱和过短密码应返回稳定的 400 业务错误，而不是 500。然后设计专用业务校验异常及其全局映射。

这个练习会改变系统外部行为，应作为独立小任务完成。

## 18. 官方资料

以下资料用于继续核对框架机制；版本升级时应优先查看对应版本文档：

- Spring Framework 声明式事务：<https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html>
- MyBatis Spring Boot Starter：<https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/>
- MyBatis-Plus 入门：<https://baomidou.com/getting-started/>
- MyBatis-Plus 持久层接口：<https://baomidou.com/guides/data-interface/>
- MyBatis-Plus 注解配置：<https://baomidou.com/reference/annotation/>
- Spring Security 密码存储：<https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html>
- OWASP Password Storage Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>
- Spring Boot 数据库初始化与 Flyway：<https://docs.spring.io/spring-boot/how-to/data-initialization.html>
- Flyway 文档：<https://documentation.red-gate.com/flyway>
