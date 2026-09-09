# RAG Agent 前端问题整改实施方案

## 1. 实施目标

本轮整改不是重新设计前端，而是把当前前端从“Mock 下基本能跑”收敛到：

1. 真实后端环境可以稳定注册、登录、退出和恢复登录态。
2. 本地存在标准化的 EDITOR 测试账号初始化方式。
3. CUSTOMER / EDITOR 权限表现与真实后端一致。
4. 网络、401、403、404、409、429、500、契约错误都有明确用户反馈。
5. Mock 测试和真实后端测试不再互相替代。
6. Chat、知识库关键操作不存在未处理 Promise 和静默失败。
7. `npm run e2e:real` 真正覆盖 EDITOR 知识库流程。
8. 不为了整改前端而扩大到 UI 重设计或成员授权后端开发。

---

## 2. 本轮固定决策

这些不在实施过程中重新讨论。

| 项目 | 决策 |
| --- | --- |
| 公开注册 EDITOR | **不允许** |
| `/register` 新用户角色 | 固定 `CUSTOMER` |
| EDITOR 来源 | 本地开发专用初始化机制 |
| EDITOR 初始化是否允许生产使用 | **绝对不允许** |
| KB Members 功能 | 后端完成前继续关闭 |
| 前端状态库 | 保持 TanStack Query + Zustand |
| API Client | 保持 Axios |
| 表单 | 保持 RHF/Zod 或现有 Ant Form，不引入新框架 |
| UI 框架 | 保持 Ant Design |
| 本轮 UI | 修交互和状态，不重新设计视觉 |
| 前端学习笔记 | 本轮不增加 |
| 真实权限 | 始终以后端为最终裁决 |
| Mock | 只做开发/自动化测试，不代表真实后端完成 |

明确不做：

- 注册页面增加“EDITOR”角色选择。
- 为 Members 页面顺手实现后端成员系统。
- 重写整个认证架构。
- 将 token 放进 localStorage。
- 为小问题引入 Redux 等新状态库。
- 大面积格式化现有代码。

---

## 3. 工作包 A：施工基线与变更隔离

### 目标

先解决当前仓库 100+ 文件 dirty 的工程风险。

这一步不改变业务行为。

### 实施要求

当前 `git status` 显示前端、后端、文档均存在大量修改，因此实际施工前必须建立恢复点。

推荐：

```bash
git status
git diff --stat
git diff
```

然后由当前代码形成一个本地 checkpoint。

不允许通过：

```bash
git reset --hard
git clean -fd
```

来获得“干净环境”。

也不自动 stash 用户已有改动。

### 后续改动原则

整改中的每一个工作包尽量形成独立 Git commit：

```text
A baseline
B dev editor provisioning
C auth state machine
D api error/retry
E real e2e
F knowledge base
G chat
H a11y/env
I final verification
```

这样某个方案出现问题，可以只回退该阶段。

### 验收

开始整改前：

- 已有改动均保留。
- 有明确恢复点。
- 没有删除现有 untracked RAG 代码。

---

## 4. 工作包 B：建立真实 EDITOR 本地初始化机制

这是解决“为什么我没法注册 EDITOR”的真正实现。

### 4.1 保持公开注册协议不变

继续保持：

```text
POST /register
{
  email,
  password
}

        ↓

CUSTOMER + ACTIVE
```

`RegisterPage.tsx` 不增加角色字段。

注册页面增加说明：

```text
新注册账号默认为普通用户。
编辑者权限由系统管理员分配。
```

这样用户不会误认为“注册页缺少角色按钮”。

### 4.2 新增本地 EDITOR Seeder

建议后端增加：

```text
src/main/java/yangsirly/rag_agent/dev/
    DevEditorSeeder.java
    DevEditorSeedProperties.java
```

使用双重保护：

```java
@Profile("local")

@ConditionalOnProperty(
    prefix = "app.dev.editor-seed",
    name = "enabled",
    havingValue = "true"
)
```

也就是说必须同时满足：

```text
Spring profile = local
+
app.dev.editor-seed.enabled=true
```

才允许执行。

#### 配置

只从环境变量读取：

```text
APP_DEV_EDITOR_SEED_ENABLED=true
APP_DEV_EDITOR_SEED_EMAIL=...
APP_DEV_EDITOR_SEED_PASSWORD=...
```

密码和邮箱不给默认值。

仓库中不提交真实密码。

### 4.3 Seeder 行为

启动过程：

```text
读取配置
   ↓
校验 email/password
   ↓
按规范化邮箱查询 users
   ↓
 ┌──────────────┬────────────────────┬───────────────────┐
 │ 不存在        │ 已是 EDITOR         │ 已是 CUSTOMER      │
 └──────┬───────┴─────────┬──────────┴────────┬──────────┘
        │                 │                    │
        ▼                 ▼                    ▼
创建 EDITOR        校验 password       启动失败并提示
ACTIVE 用户        是否匹配             不自动提权
        │                 │
        ▼                 ▼
 BCrypt hash       匹配 → 幂等成功
                  不匹配 → 明确失败
```

#### 重要不变量

Seeder **不能**：

```text
CUSTOMER → 自动 EDITOR
```

否则只要配置错误就会发生静默权限提升。

也不默认重置已有 EDITOR 密码。

如果：

```text
email 已存在
role=CUSTOMER
```

应直接失败：

```text
Dev editor seed email already belongs to CUSTOMER
```

让开发者换测试邮箱。

### 4.4 测试

新增类似：

```text
DevEditorSeederTest
```

覆盖：

```text
disabled → 不执行
profile 非 local → 不执行

不存在用户
→ 创建 EDITOR
→ ACTIVE
→ BCrypt hash
→ 明文密码不入 DB

已有相同 EDITOR + 密码正确
→ 幂等通过
→ 不重复创建

已有 CUSTOMER
→ 拒绝
→ 不修改 role

已有 EDITOR + 密码不一致
→ 拒绝
→ 不静默改密码
```

#### 完成标准

启动本地后端后：

```text
EDITOR_EMAIL/PASSWORD
        ↓
POST /login
        ↓
200
role=EDITOR
        ↓
GET /me
        ↓
role=EDITOR
```

至此才算真正解决 EDITOR 本地测试入口。

---

## 5. 工作包 C：认证状态机整改

这是前端最高优先级工作包。

当前最大的设计问题是：

```text
/me 网络失败
≈
用户未登录
```

这两个状态必须拆开。

### 5.1 明确认证状态

修改：

```text
frontend/src/features/auth/auth-store.ts
```

建议把：

```text
user
bootstrapped
```

收敛为：

```ts
type AuthStatus =
  | "bootstrapping"
  | "authenticated"
  | "anonymous"
  | "error";
```

Store：

```text
status
user
```

保持不保存 Token。

状态约束：

```text
authenticated → user != null
anonymous     → user == null
bootstrapping → user 通常为空
error         → 不对登录状态作错误推断
```

### 5.2 启动流程

修改：

```text
useAuthBootstrap.ts
router.tsx
```

目标状态机：

```text
应用启动
   ↓
BOOTSTRAPPING
   ↓
GET /me
   │
   ├─ 200
   │    ↓
   │ AUTHENTICATED
   │
   ├─ Access 过期
   │    ↓
   │ POST /refresh
   │    ├─ 200 → 重放 /me → AUTHENTICATED
   │    └─ 401 → ANONYMOUS
   │
   ├─ /me 401 + refresh 401
   │    ↓
   │ ANONYMOUS
   │
   └─ network / 5xx / contract error
        ↓
      ERROR
```

`ERROR` 必须显示：

```text
无法恢复登录状态
[重新尝试]
```

而不是跳到 `/login`。

### 5.3 BootstrapGate

当前：

```text
loading → spinner
else → routes
```

调整为：

```text
bootstrapping
→ Loading

error
→ BootstrapErrorPage
   └─ Retry

anonymous/authenticated
→ Router
```

可以新增：

```text
frontend/src/features/auth/components/AuthBootstrapError.tsx
```

不需要复杂页面。

### 5.4 登录流程

保持：

```text
POST /login
↓
GET /me
↓
setUser
↓
navigate
```

但拆分两个错误阶段。

#### `/login` 本身失败

继续表单展示：

```text
INVALID_CREDENTIALS
USER_DISABLED
INVALID_LOGIN_REQUEST
RATE_LIMITED
NETWORK_ERROR
```

#### `/login` 成功，而 `/me` 失败

不能显示：

```text
邮箱或密码错误
```

因为密码实际上已经验证成功。

应该显示类似：

```text
登录成功，但无法加载用户信息。
请重试。
```

重试只执行：

```text
GET /me
```

而不是重新 POST `/login`。

### 5.5 登出流程

当前：

```ts
onSettled:
  clear auth
  clear query
  navigate login
```

必须取消。

新的逻辑：

```text
POST /logout
   │
   ├─ 200
   │   ↓
   │ clear auth
   │ clear Query
   │ /login
   │
   ├─ 401
   │   ↓
   │ 服务端已认为没有有效登录态
   │ clear local state
   │ /login
   │
   └─ network / 500
       ↓
     不清 auth
     提示：
     "退出登录未完成，请重试"
```

原因是 HttpOnly Cookie 仍可能有效。

不能出现：

```text
前端显示已登出
刷新页面
又登录回来了
```

### 5.6 Auth 测试

增加页面/Hook 测试：

```text
/me 200
→ authenticated

/me 401 + refresh 401
→ anonymous

/me network
→ error screen
→ 不跳 login

error screen retry 后 200
→ authenticated

login success + /me success
→ chat

login success + /me network failure
→ 显示恢复错误
→ 不显示密码错误

logout 200
→ login

logout network error
→ 仍保持当前用户
→ 显示错误
```

---

## 6. 工作包 D：统一 API Error 与 Retry 策略

### 6.1 消除双层业务重试

修改：

```text
frontend/src/shared/api/client.ts
frontend/src/shared/api/query-client.ts
```

当前：

```text
Axios GET retry
+
TanStack Query retry
```

改成：

```text
Axios
├── HTTP
├── 401 refresh
├── contract parse
└── error normalize

TanStack Query
└── 普通业务请求 retry
```

即删除 Axios：

```text
GET + no response → retry 2 次
```

普通业务请求重试只由 Query 控制。

### 6.2 Query 重试规则

建议：

```text
400 → 0
401 → 0（Axios 已处理 refresh）
403 → 0
404 → 0
409 → 0
429 → 0
network → 最多 2 次
500/502/503/504 → 最多 1 次
contract error → 0
mutation → 默认 0
```

发送消息本身已有业务幂等机制，不做自动 mutation retry。

由用户点击“重试”，并复用原 `clientMessageId`。

### 6.3 AppApiError 扩展

修改：

```text
errors.ts
```

建议加入：

```ts
retryAfterSeconds?: number;
```

从响应头：

```text
Retry-After
```

解析。

同时增加：

```text
isRateLimited
isNotFound
isConflict
isServerError
```

避免页面到处：

```ts
if (error.statusCode === ...)
```

### 6.4 建立统一用户错误映射

例如：

```text
getUserFacingError(error, context)
```

不要所有页面继续：

```ts
message.error(e.message)
```

建议统一规则：

```text
NETWORK_ERROR
→ 网络连接失败，请检查连接后重试

403
→ 你没有执行此操作的权限

404
→ 资源不存在或你无权访问

409
→ 当前资源状态已变化，请刷新后重试

429
→ 请求过于频繁，请 N 秒后重试

5xx
→ 服务暂时不可用，请稍后重试

CONTRACT_ERROR
→ 服务响应格式异常
```

详细 contract issue 继续只进入 diagnostics。

### 6.5 429 防重复提交

API 文档已经存在：

```text
RATE_LIMITED
Retry-After
```

所以至少覆盖：

```text
/register
/login
发送消息
```

出现 429 后：

```text
Retry-After: 10
```

页面按钮变成：

```text
10 秒后可重试
9 秒后可重试
...
```

倒计时期间 disabled。

建议新增轻量 Hook：

```text
src/shared/hooks/useRetryAfter.ts
```

不引入依赖。

### 6.6 Diagnostics

删除 Axios GET 自动 retry 后，大部分 pending 诊断问题自然消失。

同时确保：

```text
每一个实际 HTTP request
→ 一个 diagnostics entry
```

而不是逻辑请求被反复修改成一个 pending entry。

---

## 7. 工作包 E：重建真实 E2E 身份体系

这是防止“Mock 全绿、真实环境坏掉”的核心。

### 7.1 CUSTOMER 不使用固定账号

真实和 Mock 测试都可以动态创建：

```text
e2e_<uuid>@example.com
password1
```

新增：

```text
frontend/e2e/support/auth.ts
```

提供类似能力：

```text
registerCustomer()
login()
registerAndLoginCustomer()
loginEditor()
```

Chat、RBAC 等测试不再硬编码：

```text
customer@example.com
```

这样 Mock 和 Real 共用大部分用例。

### 7.2 EDITOR 使用环境变量

`editor-kb.spec.ts` 改成：

```text
E2E_EDITOR_EMAIL
E2E_EDITOR_PASSWORD
```

Mock 模式可以使用默认：

```text
editor@example.com
password1
```

真实模式必须要求环境变量存在。

### 7.3 Real Playwright 配置 fail-fast

修改：

```text
playwright.real.config.ts
```

启动时检查：

```text
E2E_EDITOR_EMAIL
E2E_EDITOR_PASSWORD
```

缺少则直接：

```text
Real E2E requires EDITOR credentials
```

而不是悄悄跳过 editor 测试后报告“全部通过”。

### 7.4 Real E2E 必须包含 editor-kb

当前：

```text
testMatch: auth | chat | customer
```

整改后必须加入：

```text
editor-kb
```

最终真实 E2E：

```text
auth.spec
chat.spec
customer-rbac.spec
editor-kb.spec
```

A11y 是否加入 real 可以后置，因为主要是浏览器 DOM 行为，Mock 足够。

### 7.5 EDITOR E2E 最低流程

真实后端必须验证：

```text
EDITOR login
↓
/me = EDITOR
↓
知识库菜单可见
↓
创建 KB
↓
列表可见
↓
进入 KB
↓
创建 Document
↓
查看 Document
↓
编辑 Document
↓
删除 Document
↓
删除 KB
```

不要只测当前的：

```text
create KB
+
create document
```

CRUD 必须真正闭环。

---

## 8. 工作包 F：知识库前端整改

### 8.1 Members 当前冻结

保持：

```text
VITE_ENABLE_KB_MEMBERSHIP=false
```

真实后端没有 `/members` 时：

```text
MembersPage
Members APIs
```

可以暂时保留源码，但不能：

- 默认打开。
- 纳入真实功能完成声明。
- 让 README 表述成真实可用。

### 8.2 KnowledgeBaseListPage

修改：

```text
KnowledgeBaseListPage.tsx
```

#### Create/Edit

表单统一通过：

```text
kbNameField
kbDescriptionField
```

Zod 错误映射回具体 Form.Item。

不要只：

```text
message.error(...)
```

用户需要知道哪个字段不合法。

#### Save API error

处理：

```text
409 duplicate name
403
404
429
network
500
```

Modal API 失败时保持打开。

#### Delete

增加：

```text
onError
```

删除失败不能无反馈。

删除当前页最后一项：

```text
page > 0
AND
current page item count == 1
```

成功后：

```text
setPage(page - 1)
```

否则 invalidate 当前 Query。

### 8.3 KnowledgeBaseDetailPage

当前最重要问题：

```ts
const full = await getDocument(...)
```

直接写在 click handler。

改为受控加载状态。

建议：

```text
selectedDocumentId
document detail query
editor mode
viewer mode
```

状态：

```text
点击编辑
↓
set editing document id
↓
detail query loading
↓
成功
↓
form.setFieldsValue
↓
打开/完成 editor
```

失败：

```text
query error
↓
明确错误提示
↓
不打开空编辑器
```

重复点击期间 disabled/loading。

### 8.4 文档表单

必须完整使用：

```text
docTitleField
docSummaryField
docContentField
```

正文显示：

```text
当前字数 / 50000
```

超过限制直接表单错误，而不是请求后让服务器拒绝。

实施校正：当前后端 `DocumentService.MAX_CONTENT_LENGTH` 的真实上限为 50000 字，前端、Mock 与 API 文档均以该后端约束为准。

API 仍保持同样校验，因为前端校验不是安全边界。

### 8.5 权限 UI

当前真实后端只返回创建者自己的知识库，因此这一阶段：

```text
creatorId == current user
```

应始终成立。

但前端为未来 Members 能力准备：

```ts
const isCreator =
  kb.creatorId === currentUser.userId;
```

只有 creator 显示：

```text
删除 KB
管理成员
```

普通被授权 Editor 未来只显示：

```text
编辑 KB
文档 CRUD
```

后端仍进行最终鉴权。

### 8.6 KB 测试

组件测试覆盖：

```text
列表 loading/error/empty
创建成功
duplicate 409
创建 network failure
编辑失败 Modal 不关闭
删除失败
删除最后一页回退
文档 detail load failure
文档 create/update/delete
creator-only control
```

E2E：

```text
CUSTOMER → no menu
CUSTOMER direct KB → 403

EDITOR
→ CRUD KB
→ CRUD document
```

---

## 9. 工作包 G：Chat 可靠性整改

不改变现有 `clientMessageId` 设计。

这部分当前方向是正确的。

### 9.1 Conversation 操作统一失败反馈

修改：

```text
ConversationSidebar.tsx
useConversations.ts
```

这些：

```text
createMut.mutateAsync
renameMut.mutateAsync
deleteMut.mutateAsync
```

都必须有可观察 error path。

#### 新建失败

```text
不导航
+
toast
```

#### Rename 失败

```text
Modal 保持打开
+
原输入保留
+
错误提示
```

#### Delete 失败

```text
当前 conversation 不消失
+
错误提示
```

### 9.2 Rename 使用统一 Unicode 校验

删除：

```ts
title.length > 100
```

统一：

```text
conversationTitleField
```

避免 emoji 等 Unicode 长度规则不一致。

### 9.3 Composer 字数反馈

当前：

```text
>10000
→ return
```

属于静默失败。

改为：

```text
TextArea showCount
+
10000 max
+
超限错误提示
```

用户应在发送前知道原因。

### 9.4 Failed Message 状态

当前每个 conversation 只有：

```text
一个 pending bubble
```

失败以后再发新消息会覆盖之前失败记录。

改为：

```text
localBubblesByConversation:
Record<conversationId, LocalBubble[]>
```

每条至少：

```text
key
clientMessageId
content
status
error?
```

状态：

```text
SENDING
  ↓
SUCCESS → remove local bubble

SENDING
  ↓
FAILED → 保留
             │
             └─ Retry
                 ↓
               使用原 clientMessageId
```

允许旧失败消息继续保留。

为了第一阶段保持简单：

```text
一个会话仍只允许 1 个正在发送中的请求
```

但可以同时存在多个历史 failed bubble。

### 9.5 Chat 测试

必须覆盖：

```text
new conversation network failure
rename failure
delete failure

new send
→ clientMessageId A

failure
→ retry
→ clientMessageId 仍为 A

失败消息后发送新消息
→ 旧失败消息仍存在

>10000
→ 页面错误
→ 不发 API

429
→ 显示 Retry-After
→ cooldown 内发送按钮 disabled
```

---

## 10. 工作包 H：环境配置、路由和可访问性

### 10.1 API Mode fail-fast

当前存在：

```text
main.tsx：
只有 === mock 才启 MSW

env.ts：
不是 real 就报告成 mock
```

两处语义不同。

修改：

```text
shared/lib/env.ts
```

严格解析：

```text
mock
real
```

其他：

```text
undefined
mokc
REAL
xxx
```

启动直接报配置错误。

仅测试环境允许明确 fallback：

```text
MODE=test → mock
```

生产不允许 fallback。

### 10.2 SPA 导航

这些：

```tsx
<Button href="/chat">
```

改成 React Router navigation。

避免：

```text
整页 reload
→ auth bootstrap
→ Query cache 全丢
```

### 10.3 登录 redirect

当前仅保存：

```text
pathname
```

改成：

```text
pathname + search + hash
```

例如：

```text
/knowledge-bases/1?page=2#document
```

登录后不应丢失查询参数。

### 10.4 Conversation Sidebar 无障碍

不能让：

```text
<List.Item onClick>
```

承担唯一导航职责。

主标题改成真正：

```text
<Link>
```

Edit/Delete 按钮增加 accessible name，例如：

```text
编辑「会话 A」
删除「会话 A」
```

而不是只有 icon。

### 10.5 Axe 扩充

现在主要检查 Login。

补：

```text
authenticated Chat
knowledge base list
knowledge base detail
mobile navigation
```

检查 serious / critical WCAG violation。

---

## 11. 工作包 I：测试体系和 CI 收口

### 11.1 单元 / 组件测试结构

最终测试重点应该成为：

```text
shared
├── validation
├── errors
├── api client
└── env

auth
├── bootstrap
├── login
├── register
└── logout

chat
├── sidebar
├── composer
├── send retry
└── message local state

knowledge-base
├── list
├── detail
└── RBAC capability
```

### 11.2 Mock Contract Tests

Mock handler 必须继续验证契约，但原则改成：

```text
Mock 根据真实 API contract 模拟
```

而不是：

```text
前端想做什么
→ Mock 就先实现什么
```

Members 属于明显例子。

后端没实现前，不增加新的成员流程完成声明。

### 11.3 CI

现有 CI 已经：

```text
lint
typecheck
coverage
build
mock playwright
```

增加：

```text
npm run format:check
```

建议顺序：

```text
npm ci
npm run format:check
npm run lint
npm run typecheck
npm run test:coverage
npm run build
npm run e2e
```

#### Real E2E

当前暂不强塞到普通 GitHub CI。

原因：

```text
真实后端
MySQL
Redis
EDITOR credentials
```

都需要可靠测试环境。

在基础设施没建立之前，`e2e:real` 先作为：

```text
本地集成验收 Gate
```

但必须是真的全量测试，而不是跳过 editor。

以后若增加 CI backend service，再独立建立：

```text
frontend-real-e2e
```

Job。

---

## 12. 推荐施工切片

虽然这些存在依赖关系，但每一个切片都应该独立达到可验证状态。

### Slice 1：EDITOR Provisioning

修改：

```text
backend dev seeder
README
RegisterPage 提示
```

验证：

```text
backend tests
EDITOR real login
```

完成后我们第一次拥有稳定真实 EDITOR。

### Slice 2：Auth State Machine

修改：

```text
auth-store
useAuthBootstrap
router
LoginPage
AppLayout logout
```

验证：

```text
auth component tests
Mock auth E2E
real auth E2E
```

### Slice 3：API Reliability

修改：

```text
client
query-client
errors
Retry-After
diagnostics
```

验证：

```text
client tests
429 tests
refresh single-flight regression
```

### Slice 4：Real E2E Infrastructure

修改：

```text
e2e/support
real config
auth/chat/customer/editor specs
README
```

验收：

```text
npm run e2e
npm run e2e:real
```

此时开始要求真实 EDITOR KB 测试成功。

### Slice 5：Knowledge Base

修改：

```text
KB list
KB detail
document loading
errors
permissions
tests
```

不碰真实 Members backend。

### Slice 6：Chat

修改：

```text
sidebar mutations
composer
failed message state
tests
```

保持现有幂等协议。

### Slice 7：UX / A11y / Env

修改：

```text
strict env
SPA routing
redirect
keyboard navigation
axe
```

### Slice 8：Final Gate

执行完整：

```bash
npm run format:check
npm run lint
npm run typecheck
npm run test:coverage
npm run build
npm run e2e
npm run e2e:real
```

同时后端至少执行与 DevEditorSeeder 和知识库接口相关测试。

---

## 13. 最终验收场景

### 场景 A：新 CUSTOMER

```text
打开注册页
↓
注册
↓
提示 CUSTOMER 语义
↓
登录
↓
Chat
↓
没有 KB 菜单
↓
访问 /knowledge-bases
↓
403
```

### 场景 B：真实 EDITOR

```text
local dev seeder
↓
EDITOR account
↓
登录
↓
KB 菜单
↓
KB CRUD
↓
Document CRUD
↓
退出登录
```

### 场景 C：Access Token 失效

```text
当前已登录
↓
access expired
↓
普通请求 401
↓
single-flight refresh
↓
原请求重放一次
↓
页面继续工作
```

### 场景 D：Refresh 也失效

```text
access 401
↓
refresh 401
↓
auth → anonymous
↓
login
```

### 场景 E：后端挂掉

```text
刷新浏览器
↓
/me network error
↓
"无法恢复登录状态"
↓
Retry
```

绝不能：

```text
自动认为未登录
```

### 场景 F：Logout 网络失败

```text
点击退出
↓
network error
↓
提示退出失败
↓
仍保持 authenticated
```

刷新后不会出现“神秘重新登录”。

### 场景 G：限流

```text
429
Retry-After: 10
↓
页面显示 10 秒
↓
按钮暂时 disabled
↓
到期后允许重新操作
```

### 场景 H：Chat 发送失败

```text
发送
ID=A
↓
network failure
↓
failed bubble
↓
发送另一条
↓
旧 failed bubble 仍存在
↓
点击旧消息 Retry
↓
仍使用 ID=A
```

---

## 14. Definition of Done

整个整改只有同时满足以下条件才算完成：

| 项目 | 要求 |
| --- | --- |
| EDITOR | 有受保护的本地初始化机制 |
| Register | 无 EDITOR 自助注册 |
| Auth bootstrap | 能区分 anonymous 与 backend failure |
| Refresh | single-flight + 原请求最多重放一次 |
| Logout | 网络失败不伪装成功 |
| API retry | 不存在 Axios + Query 双层业务重试 |
| 429 | 支持 Retry-After |
| KB | CRUD 失败均有明确反馈 |
| Documents | detail 加载受控 |
| Members | 后端未完成时继续关闭 |
| Chat | mutation 无静默失败 |
| Message retry | clientMessageId 不变化 |
| Accessibility | 关键导航键盘可操作 |
| Mock E2E | CUSTOMER + EDITOR 关键路径通过 |
| Real E2E | CUSTOMER + EDITOR 均实际执行 |
| editor-kb | 不再被 real config 排除 |
| TypeScript | 通过 |
| ESLint | 通过 |
| Vitest | 通过 |
| Build | 通过 |
| Format check | 通过 |

---

## 15. 实施过程中禁止出现的“快速修法”

以下方案即使能暂时让页面工作，也不接受：

```text
/register 增加 role=EDITOR
```

```text
SQL 手工 UPDATE users SET role='EDITOR'
作为长期开发流程
```

```text
/me 网络失败直接 navigate('/login')
```

```text
logout 无论成功失败都清登录态
```

```text
删除失败 catch {}
```

```text
所有错误统一 message.error("请求失败")
```

```text
为了测试通过而关闭真实 EDITOR E2E
```

```text
Mock 有 API → 宣称后端功能完成
```

```text
HTTP GET Axios retry
+
TanStack Query retry
```

```text
后端 403 前端直接当登录过期
```

这些都会重新制造当前问题。

---

## 16. 预计最终结构变化

不会发生大规模目录重构，主要新增少量边界组件：

```text
frontend/
├── e2e/
│   └── support/
│       └── auth.ts
│
└── src/
    ├── features/
    │   ├── auth/
    │   │   └── components/
    │   │       └── AuthBootstrapError.tsx
    │   ├── chat/
    │   └── knowledge-base/
    │
    └── shared/
        ├── api/
        │   ├── client.ts
        │   ├── errors.ts
        │   └── query-client.ts
        └── hooks/
            └── useRetryAfter.ts

src/main/java/yangsirly/rag_agent/
└── dev/
    ├── DevEditorSeeder.java
    └── DevEditorSeedProperties.java
```

重点是**把现有架构的边界修正确，而不是换架构**。

---

## 17. 实施原则

后续实际动工时，把本方案作为整改基线：

- 每完成一个 Slice 就运行该 Slice 对应回归测试。
- 不等所有文件全部修改完成后再统一排错。
- 优先完成 `EDITOR Seeder + Auth 状态机`，因为真实 E2E、知识库验证都依赖这两项。
- 任一阶段如果发现真实后端契约与本文假设不一致，应先修正文档和方案，再继续实现。
- 所有验证结论必须区分 Mock、真实后端和未验证状态。
