# RAG Agent Frontend

一阶段生产级前端：React 19 + TypeScript + Vite + Ant Design。

## 快速开始

```bash
cd frontend
npm ci
npm run dev:mock    # Mock 模式，无需后端
# 或
npm run dev         # 真实模式，代理到 http://localhost:18080
```

打开 http://localhost:5173

### Mock 测试账号

| 邮箱                 | 密码      | 角色     |
| -------------------- | --------- | -------- |
| customer@example.com | password1 | CUSTOMER |
| editor@example.com   | password1 | EDITOR   |
| editor.b@example.com | password1 | EDITOR   |
| disabled@example.com | password1 | 禁用账号 |

## 环境变量

见 `.env.example`：

- `VITE_API_MODE=mock|real`
- `VITE_ENABLE_KB_MEMBERSHIP=false`（默认关闭成员授权页）
- `VITE_ENABLE_DIAGNOSTICS=true|false`
- `VITE_API_PROXY_TARGET`（开发代理目标）

前端请求统一走 `/api/*`，Vite/Nginx 会去掉 `/api` 前缀再转发后端，避免 SPA `/login` 与后端 `POST /login` 冲突。

## 脚本

| 脚本                    | 说明                                 |
| ----------------------- | ------------------------------------ |
| `npm run dev`           | 真实后端模式                         |
| `npm run dev:mock`      | MSW Mock 模式                        |
| `npm run build`         | 生产构建                             |
| `npm run lint`          | ESLint                               |
| `npm run typecheck`     | TypeScript 检查                      |
| `npm run test`          | Vitest                               |
| `npm run test:coverage` | 覆盖率                               |
| `npm run e2e`           | Playwright（默认 Mock）              |
| `npm run e2e:real`      | 对真实后端跑 CUSTOMER 和 EDITOR CRUD |

## Docker

```bash
docker build -t rag-agent-frontend .
# 需将 nginx 中 backend 主机名指向实际后端服务
docker run --rm -p 8080:8080 rag-agent-frontend
```

## 真实 EDITOR 账号

公开注册固定为 CUSTOMER。开发专用 Seeder 仅在唯一 active profile 为 `local` 且显式启用时执行：

```powershell
# 仓库根目录；邮箱和密码由本地环境注入，不要提交真实值。
$env:SPRING_PROFILES_ACTIVE = "local"
$env:APP_DEV_EDITOR_SEED_ENABLED = "true"
# 设置 APP_DEV_EDITOR_SEED_EMAIL / APP_DEV_EDITOR_SEED_PASSWORD 后启动。
# 若仓库存在 .local/application.properties，使用 SPRING_* 显式指定隔离服务，覆盖其中的连接地址。
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:13316/rag_remediation_verified"
$env:SPRING_DATASOURCE_USERNAME = "root"
$env:SPRING_DATASOURCE_PASSWORD = "..."
$env:SPRING_DATA_REDIS_HOST = "127.0.0.1"
$env:SPRING_DATA_REDIS_PORT = "16386"
$env:SERVER_PORT = "18085"
.\mvnw.cmd spring-boot:run
```

Seeder 只创建新 ACTIVE EDITOR；已有同邮箱用户必须为 ACTIVE EDITOR 且密码匹配。CUSTOMER、禁用账号、密码不一致均启动失败，不自动提权或重置密码。严禁连接生产数据库；profile 是配置防误用机制，不能识别数据库实际用途。

真实 E2E 必须设置 `E2E_EDITOR_EMAIL` 和 `E2E_EDITOR_PASSWORD`（与 Seeder 一致），缺少时直接失败。测试会动态注册 CUSTOMER 并创建、编辑、删除测试知识库、文档和会话，请使用隔离数据库。

```powershell
# frontend 目录；已有真实后端
$env:VITE_API_PROXY_TARGET = "http://127.0.0.1:18080"
$env:E2E_EDITOR_EMAIL = "..."
$env:E2E_EDITOR_PASSWORD = "..."
npm run e2e:real
```

Mock E2E 独占 5174，Real E2E 独占 5175，均拒绝复用已有服务，避免混用 API 模式。普通开发仍使用 5173。成员管理后端未完成，保持 `VITE_ENABLE_KB_MEMBERSHIP=false`。`VITE_API_MODE` 必须明确为 `mock` 或 `real`，拼写错误不会回退为 Mock。

当前真实后端上限：知识库名称 16 字、描述 100 字，文档标题 100 字、摘要 500 字、正文 50000 字。这些值与旧规划不同，前端按实际后端校验；400/409 等仍由后端最终裁决。

## 目录结构

```
src/
  app/           # 路由、布局、诊断、主题同步
  features/      # auth / chat / knowledge-base
  shared/        # api client、zod 契约、i18n、UI
  mocks/         # MSW 状态仓库与 handlers
  test/          # 测试工具
e2e/             # Playwright
```

## 故障诊断

1. 打开右上角「诊断」抽屉（`VITE_ENABLE_DIAGNOSTICS=true`）。
2. 查看请求方法、路径、状态、耗时、`X-Client-Request-Id`。
3. Mock 模式下可重置数据、切换身份、注入 400/401/403/404/409/500。
4. 契约校验失败会在诊断中记录字段差异。
5. Cookie 登录跨端口联调时，确认后端 CORS 允许 `credentials` 与明确 Origin。

## 学习笔记

见仓库 `docs/learning/milestone-frontend-phase1.md`。
