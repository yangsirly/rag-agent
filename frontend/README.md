# RAG Agent Frontend

一阶段生产级前端：React 19 + TypeScript + Vite + Ant Design。

## 快速开始

```bash
cd frontend
npm ci
npm run dev:mock    # Mock 模式，无需后端
# 或
npm run dev         # 真实模式，代理到 http://localhost:8080
```

打开 http://localhost:5173

### Mock 测试账号

| 邮箱 | 密码 | 角色 |
| --- | --- | --- |
| customer@example.com | password1 | CUSTOMER |
| editor@example.com | password1 | EDITOR |
| editor.b@example.com | password1 | EDITOR |
| disabled@example.com | password1 | 禁用账号 |

## 环境变量

见 `.env.example`：

- `VITE_API_MODE=mock|real`
- `VITE_ENABLE_KB_MEMBERSHIP=false`（默认关闭成员授权页）
- `VITE_ENABLE_DIAGNOSTICS=true|false`
- `VITE_API_PROXY_TARGET`（开发代理目标）

前端请求统一走 `/api/*`，Vite/Nginx 会去掉 `/api` 前缀再转发后端，避免 SPA `/login` 与后端 `POST /login` 冲突。

## 脚本

| 脚本 | 说明 |
| --- | --- |
| `npm run dev` | 真实后端模式 |
| `npm run dev:mock` | MSW Mock 模式 |
| `npm run build` | 生产构建 |
| `npm run lint` | ESLint |
| `npm run typecheck` | TypeScript 检查 |
| `npm run test` | Vitest |
| `npm run test:coverage` | 覆盖率 |
| `npm run e2e` | Playwright（默认 Mock） |
| `npm run e2e:real` | 对真实后端跑可用接口 |

## Docker

```bash
docker build -t rag-agent-frontend .
# 需将 nginx 中 backend 主机名指向实际后端服务
docker run --rm -p 8080:8080 rag-agent-frontend
```

## 真实 EDITOR 账号

真实模式不内置 EDITOR 注册。请用后端初始化脚本/SQL 创建 EDITOR，凭证仅通过本地环境变量或 CI Secrets 注入，例如：

```bash
# 本地（勿提交）
export E2E_EDITOR_EMAIL=...
export E2E_EDITOR_PASSWORD=...
npm run e2e:real
```

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
