# RAG Agent 学习笔记

这里记录项目已经实现并验证过的工程能力，以及这些实现背后的 Java、Spring、数据库和安全原理。

学习笔记和源码注释的分工不同：

- 源码注释解释当前类、方法或关键分支为什么这样写。
- 学习笔记串联完整调用链，解释底层机制、失败路径、替代方案和验证方法。
- 需求文档说明系统准备实现什么；学习笔记只把已经落地的部分当作“项目事实”。
- 未在真实数据库、并发或故障场景中验证的结论，必须明确写成“待验证”，不能从设计意图直接升级成性能或正确性事实。

## 第二阶段执行方式

第二阶段默认采用 **项目驱动 + Just-in-time 学习**：只补当前工程切片必需的知识，然后立刻做决策、实现和验证，不按课程式路径提前铺完 RAG 理论。

- [第二阶段 RAG 学习与实现流程](./phase2-rag-learning-workflow.md)：规定前置知识控制、P2.1 Retrieval-first 垂直切片、decision gate 和后续按失败现象补理论的推进方式。

## 当前笔记

| 里程碑 | 状态 | 主要内容 |
| --- | --- | --- |
| [里程碑 01：用户注册](./milestone-01-registration.md) | 已实现，持续完善 | Spring MVC 请求链路、分层模型、BCrypt、MyBatis-Plus、事务、并发唯一约束、异常映射和测试 |
| [里程碑 02：登录与认证](./milestone-02-authentication.md) | 双 Token 核心路径已实现；MySQL 并发测试代码已存在，真实执行待验证 | Access JWT（15 分钟）、随机 Refresh（固定 7 天）、会话行锁/严格轮换、jti 黑名单、Cookie 恢复与前端 single-flight；`MySqlRefreshSessionConcurrencyIntegrationTests` 默认在未设置 `RUN_MYSQL_TESTS=true` 时跳过 |
| [里程碑 03：聊天闭环](./milestone-03-chat-loop.md) | 业务已实现，集成测试已补 | `send` 单事务消息对、`clientMessageId` 三态幂等与并发兜底、所有权 404 伪装、最新页优先分页、游标与深分页保护 |
| [里程碑 04：知识库与文档管理](./milestone-04-knowledge-base.md) | CRUD 已实现；现有知识模块 37 个测试通过；安全/事务/SQL 投影仍有待修项 | `content_length` 写时预统计、HTTP Payload 瘦身、联合索引原理；同时记录当前列表 SQL 仍读取正文、删除资源绑定缺口、级联软删缺少事务，以及真实 MySQL `EXPLAIN ANALYZE` 待验证 |
| [里程碑 05：RAG Retrieval Baseline](./milestone-05-rag-retrieval-baseline.md) | P2.1 Slice 1 进行中；MySQL/测试执行待验证 | 方案 B（MySQL JSON vector + Java Exact Search）、`document_chunks` 资源绑定、code point offset、ParagraphChunker baseline、评测 seed |
| [里程碑 06：工业级高并发加固](./milestone-06-industrial-hardening.md) | 已实现并审查修复 | 软删替代 CASCADE（会话+消息双侧）、Hikari/Tomcat 池化、Redis Lua 限流 + SET NX 幂等快路径、JWT jti 黑名单、登录失败锁定、游标/深分页保护、429/黑名单、Prometheus；移除永不激活的 RabbitMQ 脚手架 |

## 推荐阅读方式

1. 先阅读笔记中的“当前实现范围”和“完整调用链”。
2. 沿着文中的相对链接打开真实代码，确认笔记描述与实现一致。
3. 阅读底层原理和失败场景，不要只记注解名称。
4. 区分四种表述：**项目事实 / 通用原理 / 推断建议 / 待验证项**。
5. 运行验证命令，再完成文末的小实验或理解检查。
6. 修改实现后同步更新对应笔记，避免代码和解释发生漂移。

## 当前技术基线

以下版本来自项目当前的 `pom.xml`：

- Java 25
- Spring Boot 4.1.0
- MyBatis Spring Boot Starter 4.0.1
- MyBatis-Plus 3.5.17
- Spring Security Crypto
- JJWT 0.12.6（JWT HMAC 签发/验签）
- Spring Boot Flyway Starter + MySQL Connector/J
- 本机验证数据库 MySQL 8.0.40
- 测试数据库 H2（MODE=MySQL）
- Redis 7（限流/幂等快路径/黑名单/登录失败计数；测试排除 Redis 自动配置，降级为内存实现）

版本升级后，涉及自动配置、异常类型或默认行为的结论应重新验证。

## 后续计划

后续里程碑完成时，优先继续维护本目录，而不是把长篇原理说明堆进源码：

- 第二阶段当前主线：按 [第二阶段 RAG 学习与实现流程](./phase2-rag-learning-workflow.md) 继续 P2.1 Slice 1；当前已完成 Chunking baseline，下一 decision gate 是 Embedding 模型与调用边界，然后实现 `Embedding -> persistence -> cosine Exact Search -> Top-K`。
- 认证专项：显式设置 `RUN_MYSQL_TESTS=true`，在真实 MySQL/InnoDB 上运行已存在的 `MySqlRefreshSessionConcurrencyIntegrationTests`，确认同一 Refresh 并发使用只能一个成功，并验证严格重放撤销。
- 聊天专项：在 [milestone-03-chat-loop.md](./milestone-03-chat-loop.md) 第 10.2 节清单基础上继续补 Service 单元测试、HTTP 集成测试与并发双发验证，并实测 MySQL 上的 V2 迁移。
- 知识库专项：优先修复知识库/文档删除的资源绑定与 IDOR 风险，为知识库级联软删增加事务与失败回滚验证；随后把文档列表改成显式 SQL Projection，并在真实 MySQL 上用 `EXPLAIN ANALYZE` 验证索引、回表和大文本读取情况。
- 权限与角色边界。
- 知识库成员协同授权（`knowledge_base_members`）与细粒度权限。
- RAG 解析、分块、召回与引用。
- Agent 工具调用、审批、恢复与审计。

- [一阶段生产级前端](./milestone-frontend-phase1.md)
