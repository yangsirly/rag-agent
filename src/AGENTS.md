# Backend 局部规则

本文件适用于 `src/` 及其子目录，并在与根 `AGENTS.md` 冲突时覆盖后端局部事项。

## 技术栈与结构

- Java 25。
- Spring Boot 4.1.x。
- Maven Wrapper：优先使用仓库内 `mvnw` / `mvnw.cmd`，不要假设系统已安装 Maven。
- 持久化：MyBatis / MyBatis-Plus。
- 数据库迁移：Flyway，脚本位于 `src/main/resources/db/migration/`。
- 运行依赖：MySQL、Redis。
- 测试：Spring Boot Test、Spring MVC Test、Spring Security Test、H2，以及少量 MySQL 集成测试。

主要包：

- `authentication/`：认证、JWT、刷新会话与黑名单。
- `registration/`：注册与用户持久化。
- `chat/`：会话与消息。
- `knowledge/`：知识库与文档。
- `common/`：限流、Web filter 和跨模块基础能力。
- `src/test/.../agent/`：Agent 协议、fixture 与评估相关测试。

## 常用命令

Windows：

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
```

类 Unix：

```bash
./mvnw test
./mvnw -DskipTests package
```

本地 MySQL / Redis：

```bash
docker compose up -d mysql redis
```

若只改一个模块，优先先运行相关测试，再视风险扩大到完整后端测试。

## Spring 与分层

- Controller 负责 HTTP 边界：请求绑定、身份主体获取、响应映射；不要承载核心业务规则。
- Service 负责用例编排、业务规则、事务边界和跨 Repository/Mapper 协作。
- Mapper 只负责持久化访问，不在 SQL/Mapper 层隐藏授权或复杂业务决策。
- Request/Response/Command 等类型保持职责明确；不要直接把持久化 Entity 当作公开 API 契约，除非现有模块已明确这样设计且本次无必要改变。
- 新增可替换外部边界时优先依赖注入，例如时间、密码编码、token、Redis、模型客户端和外部存储。

## 数据库与 Flyway

- 已发布 migration 视为不可变历史；不要修改旧 migration 来“修正”已执行结构，应新增后续 migration。
- 新 migration 命名保持 `V<n>__description.sql` 风格，并确认版本号未冲突。
- 表结构变更必须同时检查：约束、唯一性、索引、软删除语义、默认值、已有数据兼容性和回滚/恢复方案。
- 为查询加索引时必须从真实 SQL 的过滤、排序、基数与回表成本出发，不只因某列出现在 `WHERE` 或 `ORDER BY` 就单独建索引。
- 涉及 MySQL 特有行为、锁、事务隔离、索引或并发时，H2 不能作为充分验证；应补 MySQL 集成验证或明确标记未验证。

## 认证、授权与租户边界

这些内容属于根规则定义的核心工程模块。

- 授权必须建立在服务端可信身份上，不接受客户端传入的 userId/ownerId 作为最终权限依据。
- 读取、更新、删除资源时同时验证“资源存在”和“当前主体有权访问”；避免 IDOR。
- JWT 的签发、验签、过期、撤销、refresh rotation 和并发使用必须明确不变量。
- 密码、refresh token、密钥等敏感值不得写入日志。
- 涉及安全策略的错误提示要在可诊断性与信息泄漏之间取舍，不随意暴露账户是否存在、token 内部状态或权限细节。

## 事务、并发与幂等

- 事务边界放在真正需要原子性的应用服务层，不因“方法多”就扩大事务范围。
- 涉及 read-modify-write、refresh token 轮换、幂等键、限流计数、状态迁移时，明确并发下的不变量。
- 如果正确性依赖数据库锁、唯一约束或隔离级别，要用真实数据库验证关键路径。
- 不用内存实现冒充分布式语义；若某实现仅适合单实例测试/开发，代码和文档必须说清楚。

## 测试

- Controller 测试验证 HTTP 契约、状态码、鉴权边界和序列化行为。
- Service 测试验证业务规则、状态变化与失败路径。
- 集成测试用于验证数据库约束、事务、并发、Flyway 与框架装配等无法由纯单测证明的行为。
- 测试优先围绕可观察行为，不绑定无关内部调用次数。
- 对 Bug 优先保留回归测试；对并发或安全问题，至少覆盖一个失败场景。

## 代码修改习惯

- 优先沿用现有命名、异常映射和包结构，不因个人偏好重构整个模块。
- 新增框架注解、事务配置、Security 配置或 MyBatis 行为时，若其语义不直观，在首次出现处添加简洁中文注释并同步更新学习笔记。
- 若代码事实与 `docs/api/`、`docs/learning/` 或测试计划冲突，必须指出并决定哪个是当前 source of truth；不得静默让文档继续失真。
