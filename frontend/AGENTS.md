# Frontend 局部规则

本文件适用于 `frontend/` 及其子目录，并在与根 `AGENTS.md` 冲突时覆盖前端局部事项。

## 技术栈

- Node.js >= 24。
- React 19 + TypeScript。
- Vite。
- React Router。
- TanStack Query：服务端状态与请求缓存。
- Zustand：本地/跨页面客户端状态。
- React Hook Form + Zod：表单与校验。
- Ant Design。
- Vitest + Testing Library：单元/组件测试。
- Playwright：端到端测试。
- MSW：开发/测试阶段的 HTTP mock。

## 安装与常用命令

首次安装或 lockfile 变化后：

```bash
npm ci
```

常用验证：

```bash
npm test
npm run typecheck
npm run lint
npm run build
```

端到端：

```bash
npm run e2e
npm run e2e:real
```

格式检查：

```bash
npm run format:check
```

不要在没有明确需要时运行 `npm run format` 对整个前端做批量格式化，以免制造无关 diff。

## 状态与数据流

- TanStack Query 用于后端资源、缓存、重新获取、mutation 与服务端状态；不要把同一份服务端数据再复制进 Zustand 形成双重 source of truth。
- Zustand 用于真正的客户端共享状态，例如 UI 状态、短生命周期流程状态或无法自然归属于 query cache 的信息。
- 组件局部状态优先 `useState` / `useReducer`，不要因为“以后可能复用”提前提升成全局状态。
- URL 可表达的导航/筛选状态优先放到 router/search params，而不是藏在全局 store。

## API 与类型

- 以后端公开 API 契约和 `docs/api/` 为依据，不凭页面需求猜字段。
- API 类型与领域/UI 类型职责分开时，转换集中在明确边界，不在多个组件里重复拼装。
- 错误处理区分：认证失效、权限拒绝、校验错误、资源不存在、网络/服务异常；不要把所有失败统一吞成“请求失败”。
- 不在浏览器日志、错误 toast 或 telemetry 中输出 token、密码、refresh token 或其他敏感值。

## React 组件

- 组件优先保持单一职责；页面负责组合，复杂业务流程提取为 hook 或清晰的 feature 层逻辑。
- 不为简单 JSX 过度抽象；只有真实复用、复杂状态或测试边界出现时再拆组件。
- 避免在 render 期间产生副作用。
- `useEffect` 只用于与 React 外部系统同步，不把可由事件、派生值或 Query 生命周期表达的逻辑塞进 effect。
- 表单校验规则应尽量有单一来源，避免 Zod、组件和请求层各自维护不同规则。

## 测试

- Testing Library 测试从用户可观察行为出发，优先角色、标签、文本和交互，不依赖内部实现细节。
- 网络边界优先使用 MSW，而不是 Mock 掉整个业务 hook 后宣称页面流程已验证。
- 对鉴权、权限、错误恢复、空状态、loading、重复提交等关键流程补失败路径。
- Playwright 用于验证跨页面/真实浏览器流程；`e2e:real` 依赖真实后端时，先确认所需服务和测试数据边界。
- 修复前端 Bug 时，条件允许先增加能稳定复现的组件测试或 E2E 回归用例。

## 可访问性与交互

- 优先使用语义化 HTML 和 Ant Design 已有无障碍能力，不用仅靠视觉样式表达状态。
- 表单控件保持可访问 label；交互元素必须可通过键盘操作。
- loading、disabled、error 和 empty 状态应可被用户理解，并避免重复提交。
- 现有 Playwright/axe 检查覆盖到的页面，不得通过关闭规则来制造通过。

## 修改边界

- 沿用现有路由、query key、API client、组件和目录模式；不因单个需求重构整个前端架构。
- 若修改公开交互、路由、API 字段或错误语义，同步检查 E2E、组件测试和 `docs/api/` 是否需要更新。
- 新增依赖前先确认现有 React/Ant Design/TanStack/Zustand/Zod 能否解决；不要为很小的能力增加重依赖。
- 保留用户已有视觉和交互决策；未要求 UI 重设计时，不擅自扩大成全面样式改造。
