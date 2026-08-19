# RAG Agent 学习笔记

这里记录项目已经实现并验证过的工程能力，以及这些实现背后的 Java、Spring、数据库和安全原理。

学习笔记和源码注释的分工不同：

- 源码注释解释当前类、方法或关键分支为什么这样写。
- 学习笔记串联完整调用链，解释底层机制、失败路径、替代方案和验证方法。
- 需求文档说明系统准备实现什么；学习笔记只把已经落地的部分当作“项目事实”。

## 当前笔记

| 里程碑 | 状态 | 主要内容 |
| --- | --- | --- |
| [里程碑 01：用户注册](./milestone-01-registration.md) | 已实现，持续完善 | Spring MVC 请求链路、分层模型、BCrypt、MyBatis-Plus、事务、并发唯一约束、异常映射和测试 |
| [里程碑 02：登录与认证](./milestone-02-authentication.md) | 核心路径已实现，集成测试待补 | 密码 matches、禁用检查、HMAC JWT 签发/验签、过滤器写 SecurityContext；端到端登录 HTTP 集成测试待补 |
| [里程碑 03：聊天闭环](./milestone-03-chat-loop.md) | 骨架已落地，业务 TODO | 包结构、V2 表迁移、DTO/异常/控制器路由；单事务发送与 `clientMessageId` 幂等设计；实现体仍为 TODO（501） |

## 推荐阅读方式

1. 先阅读笔记中的“当前实现范围”和“完整调用链”。
2. 沿着文中的相对链接打开真实代码，确认笔记描述与实现一致。
3. 阅读底层原理和失败场景，不要只记注解名称。
4. 运行验证命令，再完成文末的小实验或理解检查。
5. 修改实现后同步更新对应笔记，避免代码和解释发生漂移。

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
- 测试数据库 H2

版本升级后，涉及自动配置、异常类型或默认行为的结论应重新验证。

## 后续计划

后续里程碑完成时，优先继续维护本目录，而不是把长篇原理说明堆进源码：

- 登录 HTTP 集成测试：注册→登录→带 Cookie 访问；错误密码/禁用用户断言
- 聊天闭环业务实现：在 [milestone-03-chat-loop.md](./milestone-03-chat-loop.md) 骨架笔记之上，填实 `MessageService.send` / 历史分页并补集成测试后更新该文状态
- 权限与角色边界
- 知识库和文档事务
- RAG 解析、分块、召回与引用
- Agent 工具调用、审批、恢复与审计

- [一阶段生产级前端](./milestone-frontend-phase1.md)
