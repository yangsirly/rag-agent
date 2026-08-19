# 里程碑 02：登录与认证

本文对应登录与认证：**密码校验、禁用检查、HMAC JWT 签发/验签已实现**；请求入口、安全过滤器链、Cookie 契约与统一错误响应在此前骨干阶段已接通。

> 项目事实：截至 2026-07-18，`AuthService.login` 可完成查库 → `PasswordEncoder.matches` → 禁用检查 → 组装 `AuthenticatedUser` → `JwtTokenService.issueAccessToken`。`JwtTokenServiceImpl` 使用 JJWT 0.12.6 做 HMAC 签发与解析。登录成功后的端到端 HTTP 集成测试、Refresh Token、服务端黑名单仍未做。

## 1. 阅读范围与对应代码

| 职责 | 文件 |
| --- | --- |
| HTTP 入口 | [AuthController.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthController.java) |
| 登录业务边界 | [AuthService.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthService.java) |
| JWT 端口与实现 | [JwtTokenService.java](../../src/main/java/yangsirly/rag_agent/authentication/JwtTokenService.java)、[JwtTokenServiceImpl.java](../../src/main/java/yangsirly/rag_agent/authentication/JwtTokenServiceImpl.java) |
| Token 无效异常 | [InvalidAccessTokenException.java](../../src/main/java/yangsirly/rag_agent/authentication/InvalidAccessTokenException.java) |
| Cookie JWT 过滤器 | [JwtAuthenticationFilter.java](../../src/main/java/yangsirly/rag_agent/authentication/JwtAuthenticationFilter.java) |
| 安全过滤器链 | [SecurityConfiguration.java](../../src/main/java/yangsirly/rag_agent/authentication/SecurityConfiguration.java) |
| 配置绑定 | [AuthProperties.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthProperties.java) |
| 401 / 403 JSON | [JsonUnauthorizedAuthenticationEntryPoint.java](../../src/main/java/yangsirly/rag_agent/authentication/JsonUnauthorizedAuthenticationEntryPoint.java)、[JsonAccessDeniedHandler.java](../../src/main/java/yangsirly/rag_agent/authentication/JsonAccessDeniedHandler.java) |
| 业务异常映射 | [AuthenticationExceptionHandler.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthenticationExceptionHandler.java) |
| 骨干 HTTP 测试 | [AuthControllerTests.java](../../src/test/java/yangsirly/rag_agent/authentication/AuthControllerTests.java) |
| JWT 单元测试 | [JwtTokenServiceImplTests.java](../../src/test/java/yangsirly/rag_agent/authentication/JwtTokenServiceImplTests.java) |

注册模块的异常处理器已改为仅作用于 `RegisterController`，见 [RegistrationExceptionHandler.java](../../src/main/java/yangsirly/rag_agent/registration/RegistrationExceptionHandler.java)。

## 2. 当前实现范围

### 2.1 已接通的外部行为

| 场景 | HTTP 结果 | 说明 |
| --- | --- | --- |
| `POST /login` 缺少 email/password | `400`，`INVALID_LOGIN_REQUEST` | Web 校验，不进入 Service |
| 匿名访问未放行路径 | `401`，`UNAUTHORIZED` | 过滤器链 + JSON EntryPoint |
| `POST /logout` | `200`，并 `Set-Cookie` Max-Age=0 | 清除浏览器 Cookie |
| `POST /register` | 仍为注册行为 | `permitAll`，回归测试需保持通过 |
| 登录成功（Service 层） | 返回 `LoginResult` + JWT 字符串 | Controller 写入 HttpOnly Cookie；端到端 HTTP 集成测试待补 |

### 2.2 已实现的核心路径

- 按邮箱查用户并做 `PasswordEncoder.matches`
- 密码正确后拒绝 `DISABLED`（`UserDisabledException` → `401 USER_DISABLED`）
- JWT 签发：HMAC-SHA，claims 含 `sub`/`iss`/`iat`/`exp`/`email`/`role`/`status`
- JWT 校验：签名、过期、issuer、claims 完整性；失败抛 `InvalidAccessTokenException`
- 过滤器解析成功后把 `AuthenticatedUser` 写入 `SecurityContext`

### 2.3 明确未实现

- 登录成功 / 错误密码 / 禁用用户 的 HTTP 集成测试与 MySQL 集成测试
- Refresh Token / 服务端 token 黑名单
- 登录后状态变更（ACTIVE→DISABLED）时主动吊销已签发 JWT

### 2.4 方案选择（已固定）

需求文档允许 JWT Cookie 或 Spring Session JDBC。本里程碑选择：

```text
Spring Security + JWT Access Token + HttpOnly Cookie
暂不实现 Refresh Token，Access Token 较短有效期（默认 1800 秒）
JWT 库：JJWT 0.12.6（jjwt-api / jjwt-impl / jjwt-jackson）
```

理由：

- 与前后端分离一致：后端只返回 401，前端负责跳转登录页。
- Cookie + HttpOnly 降低 XSS 直接读 token 的风险；SameSite 辅助降低 CSRF 面。
- 第一阶段不做 Redis/黑名单时，短 TTL 是主要的退出/泄露缓解手段。
- 使用成熟 JWT 库避免手写 Base64/HMAC，减少签名与 claim 解析的实现风险。

主要代价：

- 退出后旧 JWT 在过期前仍可能被持有者使用（无服务端撤销）。
- CSRF：Cookie 会自动携带，需要后续明确跨站策略（见第 7 节）。
- 密钥长度必须满足 HS256（≥ 32 字节）；过短会在启动/签发时失败。

## 3. 当前调用链

```text
POST /login { email, password }
  → AuthController
  → AuthService.login
       ├─ 规范化邮箱（strip + lower）
       ├─ UserMapper.findByEmail
       ├─ PasswordEncoder.matches(明文, password_hash)
       ├─ status == DISABLED → UserDisabledException
       ├─ 组装 AuthenticatedUser(userId, email, role, status)
       └─ JwtTokenService.issueAccessToken
            └─ Jwts.builder: sub/iss/iat/exp/email/role/status + HMAC 签名
  → Set-Cookie: access_token=...; HttpOnly; SameSite=Lax
  → 200 { statusCode, role }

后续请求:
  Cookie: access_token=...
  → JwtAuthenticationFilter
  → JwtTokenService.parseAccessToken
       └─ 校验签名 + issuer + exp，还原 AuthenticatedUser
  → SecurityContextHolder 放入 AuthenticatedUser（ROLE_*）
  → Controller / 授权规则
```

## 4. 信任边界与不变量

**信任边界**

- 客户端提交的 email/password 不可信。
- 客户端不得自行声明 `userId` 或 `role`；身份只来自服务端校验后的 JWT。
- Cookie 中的 token 仍可能被盗用（XSS/中间人），因此必须签名、设过期、生产环境启用 Secure。

**不变量（核心实现时必须满足）**

1. 登录失败对外统一为“账号或密码错误”，不暴露账号是否存在。
2. `DISABLED` 用户不能获得有效登录态。
3. 未认证访问受保护资源返回 JSON `401`，不是 HTML 登录页。
4. 明文密码与 token 明文不得进入日志。

**失败路径**

- 结构非法请求：400
- 凭证错误 / 用户不存在：401 `INVALID_CREDENTIALS`
- 用户禁用：401 `USER_DISABLED`（骨干已预留异常类型）
- 伪造或过期 token：过滤器清除上下文 → 后续 401

## 5. Spring Security 过滤器链在本项目中的位置

`SecurityFilterChain` 把 HTTP 请求变成“匿名 / 已认证 / 拒绝”的决策流：

```text
请求
 → Security 过滤器链
     → JwtAuthenticationFilter（尝试从 Cookie 建 Authentication）
     → 授权规则（permitAll / authenticated / hasRole）
 → Controller
```

要点：

- `SessionCreationPolicy.STATELESS`：不创建 HttpSession，登录态不靠服务端 Session。
- `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`：在表单登录过滤器之前解析 JWT。
- 关闭默认 `formLogin` / `httpBasic`，避免浏览器原生弹窗干扰 API。
- `AuthenticationEntryPoint` 与 `AccessDeniedHandler` 输出统一 `ApiErrorResponse`。

## 6. 为什么凭证放在 HttpOnly Cookie

| 存放位置 | 优点 | 风险 |
| --- | --- | --- |
| JSON 响应体 + localStorage | 前端易读 | XSS 可直接读 token |
| `Authorization: Bearer` 头 | 语义清晰 | 前端需自己存 token |
| HttpOnly Cookie | JS 不可读 | 浏览器自动携带，需考虑 CSRF |

本项目选择 Cookie，并在响应体只返回 `role` 等非机密信息。

## 7. CSRF 的已知边界（骨干阶段）

当前骨干**关闭了** Spring Security 默认 CSRF 过滤器，以便 JSON `POST /login` 不被表单 CSRF 令牌阻断。

这意味着：

- 若未来存在“仅靠 Cookie 身份 + 浏览器自动提交”的状态变更接口，跨站页面可能触发带 Cookie 的请求。
- 缓解方向（后续实现时再选）：SameSite=Lax/Strict、自定义 CSRF 头、双重 Cookie、或对敏感写操作要求额外头。

这是方案取舍，不是“已经安全到生产可忽略 CSRF”。

## 8. 与注册模块的协作

- 密码哈希仍由注册写入 `password_hash`；登录侧应复用同一个 `PasswordEncoder` Bean。
- `User` / `UserEntity` / `UserMapper` 继续放在 `registration` 包；认证模块通过查询复用，不复制用户表映射。
- 两个 `@RestControllerAdvice` 都使用 `assignableTypes`，分别绑定注册/登录 Controller，避免 `INVALID_REGISTER_REQUEST` 与 `INVALID_LOGIN_REQUEST` 互相覆盖。

## 9. 配置项

见 `application.properties`：

```properties
security.auth.jwt.secret
security.auth.jwt.issuer
security.auth.jwt.access-token-ttl-seconds
security.auth.cookie.name
security.auth.cookie.secure
security.auth.cookie.same-site
security.auth.cookie.path
```

生产密钥必须通过环境变量注入；仓库中的默认值仅用于本地/测试占位。

## 10. JWT 签发与验签原理（本项目落地）

### 10.1 JWT 结构

```text
header.payload.signature
  │       │         └── HMAC-SHA256(secret, header.payload)
  │       └── Base64URL({ sub, iss, iat, exp, email, role, status })
  └── Base64URL({ alg: HS256, typ: JWT })
```

服务端**不查库**就能验证“是否被篡改”和“是否过期”，因为签名依赖只有服务端知道的 `secret`。

### 10.2 本项目固定 claims

| claim | 来源 | 用途 |
| --- | --- | --- |
| `sub` | `userId` | 主体主键 |
| `iss` | `security.auth.jwt.issuer` | 拒绝其他系统签发的 token |
| `iat` / `exp` | 当前时间 + TTL | 过期后过滤器视为未登录 |
| `email` | 用户邮箱 | 还原主体 / 日志关联 |
| `role` | `User.Role` 名 | 构造 `ROLE_*` 权限 |
| `status` | `User.Status` 名 | 后续业务可再拒绝禁用态 |

### 10.3 签发代码入口

见 [JwtTokenServiceImpl.issueAccessToken](../../src/main/java/yangsirly/rag_agent/authentication/JwtTokenServiceImpl.java)：

```text
Jwts.builder()
  .subject(userId)
  .issuer(issuer)
  .issuedAt / .expiration
  .claim(email/role/status)
  .signWith(Keys.hmacShaKeyFor(secretBytes))
  .compact()
```

### 10.4 验签失败如何处理

`parseAccessToken` 捕获 `JwtException` / `IllegalArgumentException`，统一包装为 `InvalidAccessTokenException`。  
`JwtAuthenticationFilter` 捕获任意 `RuntimeException` 后 `SecurityContextHolder.clearContext()`，**不写响应体**，让后续授权规则 + `AuthenticationEntryPoint` 返回 JSON `401 UNAUTHORIZED`。这样不会把“签名错误 / 过期 / issuer 错误”细节泄露给客户端。

### 10.5 常见误区

| 误区 | 正确理解 |
| --- | --- |
| JWT 加密了用户数据 | 默认只**签名**不加密；payload 可被 Base64 解码，勿放密码 |
| 退出 = token 立刻作废 | 本阶段只清浏览器 Cookie；持有旧 token 者在 `exp` 前仍可能使用 |
| 密钥可以随便短 | HS256 要求密钥至少 256 bit（32 字节） |
| 把密码校验写在 Mapper | 哈希比对属于业务/安全边界，应在 Service + `PasswordEncoder` |

## 11. 验证

2026-07-18 实际执行：

```powershell
./mvnw "-Dtest=JwtTokenServiceImplTests,AuthControllerTests,RegisterServiceTests,RegisterControllerTests" test
```

结果：`TESTS_PASSED`。

已覆盖：

- JWT 往返：issue → parse 字段一致
- 篡改 token / 错误 issuer / 过期 token / 空白 token → `InvalidAccessTokenException`
- 登录空请求 400、匿名受保护路径 401、退出清 Cookie
- 注册回归

尚未覆盖（后续练习）：

- `POST /register` → `POST /login` → 带 Cookie 访问受保护路径的 HTTP 集成测试
- 错误密码统一 `401 INVALID_CREDENTIALS`
- 禁用用户 `401 USER_DISABLED`

补充实现细节：

- Spring Boot 4 使用 Jackson 3，包名为 `tools.jackson.databind.ObjectMapper`。
- 配置了占位 `UserDetailsService`，避免 Boot 生成默认用户并打印随机密码。
- 测试 secret 见 `src/test/resources/application.properties`，长度已满足 HS256。

## 12. 下一步

1. HTTP 集成测试：注册 → 登录成功写 Cookie → 带 Cookie 访问受保护资源。
2. 错误密码 / 禁用用户的 HTTP 断言。
3. （可选）登录后若 status 变为 DISABLED，是否在过滤器二次拒绝。

## 13. 面试要点

1. 为什么登录失败不能分别提示“用户不存在”和“密码错误”？
2. HttpOnly Cookie 与 Bearer Header 的威胁模型差异是什么？
3. 无状态 JWT 下“退出登录”究竟清了什么、没清什么？
4. `SecurityContext` 存在哪里？STATELESS 下请求之间是否共享？
5. 过滤器返回 401 与 Controller 抛业务异常返回 401，分别适合什么场景？
6. JWT 的 `exp` 与 Cookie 的 `Max-Age` 分别约束谁？两者不一致会怎样？
