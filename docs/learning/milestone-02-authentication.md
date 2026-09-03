# 里程碑 02：登录与认证

本文对应登录与认证：**密码校验、禁用检查、Access JWT 签发/验签、Refresh 会话轮换、jti 黑名单和前端自动续期已实现**；请求入口、安全过滤器链、Cookie 契约与统一错误响应在此前骨干阶段已接通。

> 项目事实：截至 2026-09-03，`AuthService.login` 完成查库 → `PasswordEncoder.matches` → 禁用检查 → 组装 `AuthenticatedUser` → `RefreshSessionService.createSession`；登录同时写入 Access/Refresh 两个 HttpOnly Cookie。`JwtTokenServiceImpl` 使用 JJWT 0.12.6 签发带 `typ=access`、`sid`、`jti` 的 Access JWT；Refresh 明文只在 Cookie 中流转，数据库保存 SHA-256 哈希。认证 HTTP/JWT 测试 26 个已通过，另有 3 个 Refresh 凭证单元测试；MySQL 并发刷新测试已加入但需 MySQL 环境执行。

## 1. 阅读范围与对应代码

| 职责 | 文件 |
| --- | --- |
| HTTP 入口 | [AuthController.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthController.java) |
| 登录业务边界 | [AuthService.java](../../src/main/java/yangsirly/rag_agent/authentication/AuthService.java) |
| JWT 端口与实现 | [JwtTokenService.java](../../src/main/java/yangsirly/rag_agent/authentication/JwtTokenService.java)、[JwtTokenServiceImpl.java](../../src/main/java/yangsirly/rag_agent/authentication/JwtTokenServiceImpl.java) |
| Refresh 凭证与会话 | [RefreshTokenUtil.java](../../src/main/java/yangsirly/rag_agent/authentication/RefreshTokenUtil.java)、[RefreshSessionEntity.java](../../src/main/java/yangsirly/rag_agent/authentication/RefreshSessionEntity.java)、[RefreshSessionMapper.java](../../src/main/java/yangsirly/rag_agent/authentication/RefreshSessionMapper.java) |
| Refresh 业务边界 | [RefreshSessionService.java](../../src/main/java/yangsirly/rag_agent/authentication/RefreshSessionService.java)、[V6__create_refresh_sessions.sql](../../src/main/resources/db/migration/V6__create_refresh_sessions.sql) |
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
| `POST /logout` | `200`，并清除两个 Cookie | 当前 Access `jti` 黑名单 + 当前 Refresh 会话撤销 |
| `POST /refresh` | 成功 `200` 并轮换两个 Cookie；失败统一 `401` | 旧 Refresh 重放撤销当前设备会话 |
| `POST /register` | 仍为注册行为 | `permitAll`，回归测试需保持通过 |
| 登录成功（Service 层） | 返回 `LoginResult` + `TokenPair` | Controller 写入 Access/Refresh HttpOnly Cookie |

### 2.2 已实现的核心路径

- 按邮箱查用户并做 `PasswordEncoder.matches`
- 密码正确后拒绝 `DISABLED`（`UserDisabledException` → `401 USER_DISABLED`）
- Access JWT 签发：HMAC-SHA，header 强制 `typ=access`，claims 含 `sub`/`iss`/`iat`/`exp`/`jti`/`sid`/`email`/`role`/`status`
- JWT 校验：签名、过期、issuer、类型、`sid` 和 claims 完整性；失败抛 `InvalidAccessTokenException`
- 过滤器解析成功后把 `AuthenticatedUser` 写入 `SecurityContext`
- Refresh 会话按固定绝对过期时间保存当前哈希与 Access `jti`；轮换前锁行，成功后拉黑上一枚 Access `jti`

### 2.3 当前未覆盖

- MySQL 真实并发刷新集成测试（H2 单测不能证明 InnoDB 行锁行为）
- 登录后状态变更（ACTIVE→DISABLED）时已签发 Access 的主动吊销；刷新时会阻止禁用用户继续换取新 Token

### 2.4 方案选择（已固定）

需求文档允许 JWT Cookie 或 Spring Session JDBC。本里程碑选择：

```text
Spring Security + JWT Access Token（15 分钟）+ 随机 Refresh Token（固定 7 天）
两个 Token 均放在 HttpOnly Cookie；Refresh 只存 SHA-256 哈希并严格轮换
JWT 库：JJWT 0.12.6（jjwt-api / jjwt-impl / jjwt-jackson）
```

理由：

- 与前后端分离一致：后端只返回 401，前端负责跳转登录页。
- Cookie + HttpOnly 降低 XSS 直接读 token 的风险；SameSite 辅助降低 CSRF 面。
- Access JWT 负责每个普通请求的无状态鉴权；Refresh 数据库会话负责续期与设备级撤销；Redis/内存黑名单负责 Access 的即时吊销。
- 使用成熟 JWT 库避免手写 Base64/HMAC，减少签名与 claim 解析的实现风险。

主要代价：

- Refresh 会话需要数据库读写和行锁；严格并发策略下同一 Refresh 的晚到请求会撤销整个设备会话，安全性优先于“两个请求都成功”。
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
       └─ RefreshSessionService.createSession
            ├─ RefreshTokenUtil.generate: sessionId.randomSecret
            ├─ JwtTokenService.issueAccessToken(..., sid)
            └─ INSERT refresh_sessions(token_hash, current_access_jti, expires_at)
  → Set-Cookie: access_token=...; HttpOnly; SameSite=Lax; Max-Age=900
  → Set-Cookie: refresh_token=...; HttpOnly; SameSite=Lax; Max-Age=604800
  → 200 { statusCode, role }

后续请求:
  Cookie: access_token=...
  → JwtAuthenticationFilter
  → JwtTokenService.parseAccessToken
       └─ 校验 typ=access + 签名 + issuer + exp + sid，还原 AuthenticatedUser
  → SecurityContextHolder 放入 AuthenticatedUser（ROLE_*）
  → Controller / 授权规则

Access 过期时:
  普通接口 401 → 前端 single-flight POST /refresh
  → RefreshSessionService.refresh（按 sid SELECT ... FOR UPDATE）
       ├─ 常量时间比较 SHA-256(token)
       ├─ 校验未撤销、未过期、用户 ACTIVE
       ├─ 黑名单上一枚 Access jti
       ├─ 原子替换 Refresh 哈希 + current_access_jti
       └─ 签发新的 Access/Refresh Cookie
  → 原请求最多重试一次
```

## 4. 信任边界与不变量

**信任边界**

- 客户端提交的 email/password 不可信。
- 客户端不得自行声明 `userId` 或 `role`；普通请求身份只来自服务端校验后的 Access JWT。
- Refresh Token 不进入 Spring Security Filter；它只在 `/refresh` 事务内用于定位并轮换数据库会话。
- Cookie 中的 token 仍可能被盗用（XSS/中间人），因此必须签名/哈希、设过期、生产环境启用 Secure。

**不变量（核心实现时必须满足）**

1. 登录失败对外统一为“账号或密码错误”，不暴露账号是否存在。
2. `DISABLED` 用户不能获得有效登录态。
3. 未认证访问受保护资源返回 JSON `401`，不是 HTML 登录页。
4. 明文密码与 token 明文不得进入日志。
5. Refresh 会话的 `expires_at` 从登录时固定计算，轮换不能延长绝对过期时间。
6. 同一 Refresh Token 只能成功轮换一次；检测到旧 Token 重放时撤销整个当前设备会话。

**失败路径**

- 结构非法请求：400
- 凭证错误 / 用户不存在：401 `INVALID_CREDENTIALS`
- 用户禁用：401 `USER_DISABLED`（骨干已预留异常类型）
- 伪造或过期 token：过滤器清除上下文 → 后续 401
- 缺失、畸形、过期、撤销、重放 Refresh 或禁用用户：统一 `401 UNAUTHORIZED` + 清除两个 Cookie

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
security.auth.jwt.refresh-token-ttl-seconds
security.auth.cookie.name
security.auth.cookie.refresh-name
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
  │       └── Base64URL({ sub, iss, iat, exp, jti, sid, email, role, status })
  └── Base64URL({ alg: HS256, typ: access })
```

服务端**不查库**就能验证“是否被篡改”和“是否过期”，因为签名依赖只有服务端知道的 `secret`。

### 10.2 本项目固定 claims

| claim | 来源 | 用途 |
| --- | --- | --- |
| `sub` | `userId` | 主体主键 |
| `iss` | `security.auth.jwt.issuer` | 拒绝其他系统签发的 token |
| `iat` / `exp` | 当前时间 + TTL | 过期后过滤器视为未登录 |
| `jti` | 每次签发随机 UUID | 黑名单定位单枚 Access |
| `sid` | `refresh_sessions.id` | 把 Access 绑定到设备会话，便于登出/重放撤销 |
| `email` | 用户邮箱 | 还原主体 / 日志关联 |
| `role` | `User.Role` 名 | 构造 `ROLE_*` 权限 |
| `status` | `User.Status` 名 | 后续业务可再拒绝禁用态 |

### 10.3 签发代码入口

见 [JwtTokenServiceImpl.issueAccessToken](../../src/main/java/yangsirly/rag_agent/authentication/JwtTokenServiceImpl.java)：

```text
Jwts.builder()
  .header().type("access").and()
  .subject(userId)
  .issuer(issuer)
  .issuedAt / .expiration
  .id(jti)
  .claim("sid", sessionId)
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
| 退出 = 只删 Cookie | 当前 Access `jti` 会进入黑名单，Refresh 会话也会撤销；黑名单不可用时仍有最长 15 分钟 fail-open 风险 |
| Refresh Token 也应该做 BCrypt | Refresh 是高熵随机值，快速 SHA-256 + 常量时间比较即可；BCrypt 的故意慢速会增加刷新端点成本 |
| 旋转只生成新 Token 就够了 | 必须先锁 `refresh_sessions` 行再比较/更新，否则并发请求可能双成功 |
| Access JWT 能代表永久登录态 | Access 只负责短期鉴权；续期信任来自数据库 Refresh 会话，且有固定绝对过期 |
| 密钥可以随便短 | HS256 要求密钥至少 256 bit（32 字节） |
| 把密码校验写在 Mapper | 哈希比对属于业务/安全边界，应在 Service + `PasswordEncoder` |

## 11. 验证

2026-09-03 实际执行：

```powershell
./mvnw "-Dtest=JwtTokenServiceImplTests,AuthControllerTests,AuthLockoutTests" test
```

结果：26 个认证 HTTP/JWT 测试全部通过。

已覆盖：

- JWT 往返：issue → parse 字段一致
- 篡改 token / 错误 issuer / 过期 token / 空白 token → `InvalidAccessTokenException`
- 登录空请求 400、匿名受保护路径 401、登录双 Cookie、Refresh 轮换/重放/禁用、多设备隔离、退出清双 Cookie
- 注册回归

前端实际执行：

- `npm test -- --run`：33 个测试通过
- `npm run typecheck`：通过
- `npx playwright test e2e/auth.spec.ts --project=chromium`：注册 → 登录 → 手动过期 Access 自动刷新 → 页面重载恢复 → 退出，1 个 E2E 通过

尚未覆盖：

- MySQL/InnoDB 上同一 Refresh Token 的真实并发刷新（当前 H2/Mock 验证不能替代行锁集成测试）

补充实现细节：

- Spring Boot 4 使用 Jackson 3，包名为 `tools.jackson.databind.ObjectMapper`。
- 配置了占位 `UserDetailsService`，避免 Boot 生成默认用户并打印随机密码。
- 测试 secret 见 `src/test/resources/application.properties`，长度已满足 HS256。

## 12. 下一步

1. 在 MySQL 8 上用两个事务并发提交同一 Refresh，验证一个成功、另一个重放并撤销会话。
2. 观察 `auth.refresh{outcome}` 指标与重放安全告警，确认不包含 Token、哈希或邮箱。
3. （可选）登录后若 status 变为 DISABLED，确认现有 Access 的业务策略与刷新策略一致。

## 13. 面试要点

1. 为什么登录失败不能分别提示“用户不存在”和“密码错误”？
2. HttpOnly Cookie 与 Bearer Header 的威胁模型差异是什么？
3. 双 Token 下“退出登录”分别撤销了什么？为什么还保留 Access 黑名单？
4. `SecurityContext` 存在哪里？STATELESS 下请求之间是否共享？
5. 过滤器返回 401 与 Controller 抛业务异常返回 401，分别适合什么场景？
6. JWT 的 `exp` 与 Cookie 的 `Max-Age` 分别约束谁？两者不一致会怎样？

## 14. 双 Token 会话轮换：从本项目代码看底层机制

### 14.1 项目现象与问题

单一长寿命 JWT 很难同时满足“请求无需查库”和“泄露后可撤销”。本次实现把职责拆开：短期 Access JWT 只用于普通请求；固定 7 天的随机 Refresh 只用于换取下一对凭证，并由数据库会话记录控制设备级生命周期。关键外部行为可在 [AuthControllerTests.java](../../src/test/java/yangsirly/rag_agent/authentication/AuthControllerTests.java) 的 Refresh 与多设备测试中观察：登录响应体没有 Token，成功刷新会得到不同的两枚 Cookie，旧 Refresh 重放后当前 Access 也不能再访问 `/me`。

### 14.2 具体写法与调用链

1. `POST /login`：`AuthController` 调 `AuthService.login`；`AuthService` 校验密码和状态后调用 `RefreshSessionService.createSession`。该方法用 `RefreshTokenUtil.generateForSession` 生成 `sessionId.randomSecret`，用 `JwtTokenService.issueAccessToken(..., sessionId)` 生成带 `typ=access`、`sid`、`jti` 的 Access，并向 `refresh_sessions` 写入哈希、当前 jti 和固定 `expires_at`。
2. 普通请求：`JwtAuthenticationFilter` 只读取配置的 Access Cookie；`parseAccessToken` 强制校验 header 类型为 `access`，成功后写入 `SecurityContext`，并先通过 `TokenBlacklist` 检查 `jti`。
3. `POST /refresh`：`RefreshSessionService.refresh` 先从 Refresh 文本解析会话 ID，然后调用 Mapper 的 `SELECT ... FOR UPDATE`。锁住行后才计算 SHA-256 并做常量时间比较；通过后检查撤销时间、固定过期时间和用户 `ACTIVE` 状态，拉黑旧 jti、生成同一 sid 的新随机密钥和新 Access，再 `updateById` 原子替换当前哈希/jti。
4. `POST /logout`：服务端拉黑当前 Access，并锁定/撤销对应 Refresh 会话；无论撤销操作是否成功，Controller 都将两个 Cookie 的 `Max-Age` 设为 0。
5. 前端 `apiClient`：普通接口第一次 `401 UNAUTHORIZED` 共用模块级 `refreshInFlight` Promise；刷新成功只重放原请求一次，刷新接口本身走独立 Axios 实例，不会递归刷新。

### 14.3 底层执行原理

**为什么高熵 Refresh 用 SHA-256 而不是 BCrypt**

`RefreshTokenUtil` 每次生成 32 字节安全随机数，Base64URL 后的随机部分不可猜。数据库只保存 `SHA-256(token)` 的 32 字节结果，泄露数据库时不能直接拿到可用凭证；比较使用 `MessageDigest.isEqual`，避免按前缀提前返回。BCrypt 的工作因子是为低熵密码抗离线穷举而设计的故意慢速算法，放在高频刷新端点会增加 CPU 和延迟；它不能替代随机值本身的熵。若随机源变成可猜的短字符串，换成 BCrypt 也不能修复根因。

**为什么必须行锁 + 事务**

两个请求可能同时带来同一枚 Refresh。`SELECT ... FOR UPDATE` 让同一 `session_id` 的事务串行：第一个事务验证旧哈希并提交新哈希；第二个事务等待后读取到新哈希，比较失败，撤销会话并拉黑数据库记录中的当前 Access。`@Transactional(noRollbackFor = InvalidRefreshTokenException.class)` 保证“检测重放后撤销”不会因统一 401 异常被回滚。数据库唯一约束和 InnoDB 行锁是并发正确性的边界，不能只靠 Java 内存锁或 H2 的偶然行为。

**三种凭证各自的信任/撤销边界**

| 组件 | 本项目职责 | 是否每次普通请求查库 |
| --- | --- | --- |
| Access JWT | 短期身份、角色和 sid；Filter 验签后构造 SecurityContext | 否（只查黑名单，且 Redis 不可用时沿用 fail-open） |
| `refresh_sessions` | 设备会话、当前 Refresh 哈希、固定绝对过期、当前 Access jti | 只在 `/refresh`/`/logout` 查库并锁行 |
| Access `jti` 黑名单 | 登出、Refresh 轮换和重放后的即时吊销 | 是；命中即拒绝 |

### 14.4 知识点与应用对照

| 知识点 | 项目位置 | 可观察结果 |
| --- | --- | --- |
| Token 类型隔离 | `JwtTokenServiceImpl.parseAccessToken` | 把 Refresh Cookie 当 Access 提交时 `/me` 仍是 401 |
| 固定绝对过期 | `RefreshSessionService.createSession`/`refresh` | 多次轮换只更新 `last_used_at`，不更新 `expires_at` |
| 常量时间比较 | `RefreshTokenUtil.matches` | 错误 Token 不因前缀相同而走不同分支耗时；日志不输出秘密 |
| 一次性轮换 | `RefreshSessionMapper.findByIdForUpdate` + `updateById` | 旧 Refresh 第二次提交统一 401，并撤销该 sid |
| 设备隔离 | 每次登录生成独立 session ID | 设备 A 登出后，设备 B 的 Access 仍可访问 `/me` |

### 14.5 换种写法会怎样

- 只在 Cookie 中删除 Token、不写黑名单：被复制的 Access 在 15 分钟内仍可用。
- 不保存当前 Refresh 哈希、只验证格式：任何知道 sid 的伪造值都有机会续期，且无法识别重放。
- 先比较再加行锁：两个并发请求都可能通过旧哈希，出现双轮换和不可预测的会话状态。
- 轮换时把 `expires_at` 重新加 7 天：活跃刷新会把绝对会话寿命无限延长，违背固定 7 天约束。
- 在前端拦截器中直接再次调用同一个 `apiClient` 刷新：`/refresh` 自己 401 时会递归，或多个 401 产生刷新风暴。

### 14.6 验证实验

- 后端：运行 `./mvnw "-Dtest=JwtTokenServiceImplTests,AuthControllerTests,AuthLockoutTests,RefreshTokenUtilTests" test`；当前实际结果为 29 个测试通过。重点检查 `AuthControllerTests.refreshRotatesBothCookiesAndRejectsOldRefreshToken`、`separateDeviceSessionsDoNotRevokeEachOther`、`refreshRejectsMalformedTokenAndClearsBothCookies` 和 `refreshRejectsExpiredSessionAndClearsBothCookies`。
- 前端：运行 `npm test -- --run`、`npm run typecheck`，再运行 `npx playwright test e2e/auth.spec.ts --project=chromium`；实际结果分别为 33 个测试通过、类型检查通过、1 个 E2E 通过。
- 待补 MySQL 实验：两个独立事务用同一 Refresh 并发调用 `/refresh`，预期恰好一个 200、一个 401，且最终会话被重放策略撤销；这一步必须在 MySQL/InnoDB 上执行，不能用 H2 结果替代。

### 14.7 常见误区与面试问题

1. “JWT 无状态，所以不能撤销”——Access 本身无状态，但本项目用 jti 黑名单补上单枚撤销边界。
2. “Refresh 也做成 JWT 就更统一”——本项目选择随机不透明凭证，是为了把续期信任放在可轮换、可撤销的数据库会话上。
3. “两个并发刷新都返回 200 更友好”——本项目明确安全优先：晚到旧 Token 视为重放并撤销整个设备会话。
4. “`Max-Age=0` 就等于服务端登出”——它只清浏览器 Cookie；服务端撤销和黑名单才决定复制凭证是否立即失效。

### 14.8 可操作的小实验

在 `RefreshSessionService.refresh` 中临时注入一个测试用 `Clock`，把 `expires_at` 推进到过期后，验证接口仍统一返回 401 且不会生成新 Cookie；然后恢复 Clock，增加一个 MySQL 并发测试，观察第二个事务如何从“哈希不匹配”进入“撤销会话”分支。
