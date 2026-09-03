# Agent 阶段协议契约

> 状态：草案 v0.2（2026-07-26）
> v0.2 变更：新增 3.6 节（取消的时序与幂等语义，含 `INV-M8`/`INV-M9`）；7.2 节补齐 COMPLETED 重放前的授权重校验（`INV-I7`）；新增待定项 T-8。两处均由批次 3 的缺失清单反推补齐，见 `agent-test-plan.md` 7.1 节。
> 依据：[phase-1-api.md](phase-1-api.md)、[AGENTS.md](../../AGENTS.md)、[agent-test-plan.md](../plans/agent-test-plan.md) 第 2 节
> 用途：Agent 实现尚未开始时先固定协议；后续 fixture、测试规格与协议层测试全部以本文为断言依据
> 定位：`phase-1-api.md` 的**续篇**，不改动原文；原文未涉及的约定在本文补齐，冲突时以本文为准并在此处显式标注

## 0. 文档定位

### 0.1 沿用第一阶段的既有约定

本文不重复 `phase-1-api.md` 已定的通用约定，只声明沿用：

| 约定 | 出处 |
| --- | --- |
| JSON 字段名 camelCase | 2.1 |
| 数据库 ID 在 JSON 中序列化为**十进制字符串** | 2.1、12.8 |
| 时间字段 ISO-8601（UTC 或带偏移） | 2.1 |
| 统一错误体 `{statusCode, code, message}`，`message` 不含密码/token/堆栈 | 2.5 |
| 认证方式：Access JWT（15 分钟）+ 随机 Refresh（固定 7 天），均为 HttpOnly Cookie | phase-1-api.md 2.2 |
| 无权访问他人资源统一 `404 NOT_FOUND`；角色不足 `403 FORBIDDEN` | 2.2 第 5、6 条 |
| 幂等键 `clientMessageId` 为标准 UUID，重试保持不变 | 2.7 |

### 0.2 本文与第一阶段的实质差异

第一阶段的消息发送是 **单事务同步**：校验 → 写 USER → 写模板 ASSISTANT → 更新会话 `updatedAt`，全程无外部 IO（见 `MessageService` 类注释与 `一阶段需求文档.md` 第六节方案 A）。

接入模型后有三点变化，本文其余章节都是这三点的展开：

1. 一次发送跨越秒级的外部调用，**不能**再包在一个事务里；
2. 回复是**流式增量**产生的，中途可能失败、可能被取消、可能连接断开；
3. 模型可以请求调用工具，而模型的输出**不可信**——工具名、参数、要访问的资源都可能是幻觉或注入的结果。

### 0.3 阅读顺序

第 1～2 节定义"线上传什么"，第 3 节定义"库里留什么"，第 4～5 节定义"模型能做什么"，第 6～7 节定义"错了怎么办"。第 8 节汇总所有**待定项**，实现前必须由用户拍板。

### 0.4 全文约定的书写格式

每条约定都必须写清"违反时系统应该怎样"，否则它不是可测的契约，只是愿望。本文统一用两种形式：

- 表格中带 **违反时** 列；
- 正文中以 `违反时：` 开头的独立行。

**不变量编号**用于被测试规格（`agent-test-cases.md`）直接引用：`INV-S*` 流协议、`INV-M*` 消息状态机、`INV-C*` 引用、`INV-T*` 工具、`INV-I*` 幂等。

---

## 1. 传输绑定

### 1.1 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/conversations/{conversationId}/messages/stream` | 发起一轮 Agent 生成，响应体为 SSE 流 |
| POST | `/conversations/{conversationId}/messages/{messageId}/cancel` | 取消正在进行的轮次 |
| GET | `/conversations/{conversationId}/messages` | 沿用第一阶段接口读取最终结果（断连后的权威恢复手段） |

第一阶段的 `POST /conversations/{conversationId}/messages`（非流式、模板回复）**保持不变**，不改为调用模型。Agent 能力走新端点，便于前端灰度与回退。

> 路径命名是本文提案，实现时可调整；调整路径不影响第 2 节起的事件协议。

### 1.2 请求

请求体与第一阶段 `POST .../messages` 完全一致，便于前端复用发送逻辑：

```json
{
  "clientMessageId": "018f6f5a-7d5b-7c3a-a08f-5cf5b26a7a21",
  "content": "报销单最多可以延迟多少天提交？"
}
```

请求头必须包含 `Accept: text/event-stream`。

违反时：`Accept` 不接受 `text/event-stream` → `406 NOT_ACCEPTABLE`，不退化成 JSON 响应（静默退化会让前端拿到自己没准备解析的结构）。

### 1.3 响应

```text
HTTP/1.1 200 OK
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache, no-store
Connection: keep-alive
X-Accel-Buffering: no
```

**关键约定（容易踩的坑）**：只有在**首个事件写出之前**发生的失败，才能用 HTTP 状态码表达（401 / 404 / 400 / 409）。一旦响应头已经发出，HTTP 状态码永远是 200，所有失败只能通过 `error` 事件的 `statusCode` 字段表达。

违反时：前端若按 HTTP 状态码判断成败，会把"模型超时"当成"请求成功"。因此前端必须以 `done` / `error` 事件为唯一成败判据。

### 1.4 为什么是 SSE 而不是 WebSocket

本文假设 SSE。理由：当前只需要服务端单向推送；SSE 走普通 HTTP，Cookie 认证、`404`/`401` 前置校验、反向代理配置都与现有接口一致；WebSocket 需要独立的握手期鉴权与心跳栈。

见 [第 8 节](#8-待定项) 待定项 T-2：若后续要做前端主动打断或多路复用，需改 WebSocket，届时第 2 节需重写，但事件类型与字段可整体搬迁。

---

## 2. SSE 事件协议

### 2.1 帧格式与公共字段

每个事件占一个 SSE 帧，包含 `event:`、`id:`、`data:` 三行，以空行结束：

```text
event: delta
id: 3
data: {"seq":3,"messageId":"101","text":"最多可以延迟 "}

```

| 规则 | 说明 | 违反时 |
| --- | --- | --- |
| `data` 必须是**单行** JSON 对象 | 换行符在 JSON 字符串中转义为 `\n`，不得产生多行 `data:` | 消费方按协议违例处理（`PROTOCOL_VIOLATION`） |
| `id` 等于 payload 中的 `seq` | 供客户端 `Last-Event-ID` 定位 | 同上 |
| `event` 必须是六个已定义类型之一 | `delta` / `tool_call` / `tool_result` / `citation` / `done` / `error` | 未知事件类型：消费方**忽略并记录**，不得中断（为将来加事件类型留出前向兼容） |

所有事件共有两个字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| seq | number | 流内序号，从 1 开始，严格递增且步长为 1 |
| messageId | string | 本轮生成的 ASSISTANT 消息 ID（十进制字符串） |

`messageId` 在**第一个事件之前**就已经确定：服务端先在一个短事务里写入 USER 消息与 PENDING 状态的 ASSISTANT 占位消息，再开始调模型（见第 7 节）。这样客户端从第一个事件起就能定位到要更新哪条消息。

### 2.2 `delta`

模型产出的正文增量。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| text | string | 是 | 非空的文本片段，可以是单个字符 |

```json
{"seq":3,"messageId":"101","text":"最多可以延迟 30 天"}
```

- 不做任何形式的"整句缓冲"，模型给多少就转发多少；
- `text` 中可以出现内联引用标记 `[^1]`（见第 4 节）；
- 标记可能被切在两个 `delta` 之间，客户端必须在拼接后的完整文本上解析标记，不得逐片解析。

违反时：`text` 为空字符串或 `null` → `PROTOCOL_VIOLATION`。空 delta 没有任何语义，只会让客户端以为"模型还在输出"，掩盖真正的空回复（见 3.4 节 `INV-M4`）。

### 2.3 `tool_call`

服务端决定执行一次工具调用时发出。**注意发出时机**：它表示"服务端收到并登记了这次调用请求"，不表示"参数校验已通过"。校验结果由配对的 `tool_result` 表达。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| callId | string | 是 | 流内唯一；来自模型响应，服务端若发现重复或缺失则自行生成 `srv_<seq>` 替代 |
| step | number | 是 | 所属步号，从 1 开始（定义见 5.7 节） |
| name | string | 是 | 模型请求的工具名，**原样回显**，即使该工具不存在 |
| arguments | object \| null | 是 | 解析后的参数对象；模型给的参数不是合法 JSON 对象时为 `null` |
| argumentsInvalid | boolean | 否 | 缺省 `false`；`true` 表示参数无法解析为 JSON 对象 |

```json
{"seq":4,"messageId":"101","callId":"call_1","step":1,"name":"kb_search","arguments":{"query":"报销单 延迟","knowledgeBaseId":"1","topK":5}}
```

为什么不存在的工具名也要发 `tool_call`：保持"每个 `tool_result` 都有先行 `tool_call`"这条不变量无例外，客户端的时间线渲染逻辑就只有一种形状；而且用户看到"模型试图调用一个不存在的工具"对排查很有价值。

违反时（服务端产出侧）：发出 `arguments` 为超长原文的 `tool_call` → 违反 5.4 节的日志约束。超长参数必须在此处就被截断为 `null` + `argumentsInvalid: true`。

### 2.4 `tool_result`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| callId | string | 是 | 必须等于某个先行 `tool_call` 的 `callId` |
| status | string | 是 | `OK` \| `ERROR` |
| durationMs | number | 是 | 工具执行墙钟耗时 |
| result | object | `status=OK` 时必填 | 工具返回值，结构由各工具自定义 |
| code | string | `status=ERROR` 时必填 | 第 6 节中的错误码 |
| message | string | `status=ERROR` 时必填 | **回喂给模型**的无害化文案，见 5.6 节 |

```json
{"seq":5,"messageId":"101","callId":"call_1","status":"OK","durationMs":42,"result":{"hits":[{"docId":"kb-1/doc-3","score":0.83}]}}
```

```json
{"seq":5,"messageId":"101","callId":"call_9","status":"ERROR","durationMs":0,"code":"TOOL_NOT_FOUND","message":"工具 send_email 不存在，请只使用工具清单中列出的工具。"}
```

违反时：
- `status=OK` 却缺 `result`，或 `status=ERROR` 却缺 `code` → `PROTOCOL_VIOLATION`；
- 同时出现 `result` 与 `code` → `PROTOCOL_VIOLATION`（两种语义互斥，同时出现说明实现里有分支漏了 return）。

### 2.5 `citation`

字段语义见第 4 节。

```json
{"seq":8,"messageId":"101","marker":1,"docId":"kb-1/doc-3","chunkId":null,"snippet":"报销单应在费用发生后 30 天内提交","score":0.83,"knowledgeBaseId":"1"}
```

### 2.6 `done`

流成功结束。**必须是最后一个事件。**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| status | string | 是 | 恒为 `COMPLETED`（保留字段以便客户端统一读 `status`） |
| finishReason | string | 是 | `STOP` \| `LENGTH` \| `REFUSAL` |
| steps | number | 是 | 本轮实际步数 |
| content | string | 是 | 最终落库的完整正文 |

```json
{"seq":12,"messageId":"101","status":"COMPLETED","finishReason":"STOP","steps":2,"content":"最多可以延迟 30 天[^1]。"}
```

`finishReason` 取值：

| 值 | 含义 | 落库状态 |
| --- | --- | --- |
| STOP | 模型正常结束 | COMPLETED |
| LENGTH | 触达输出长度上限被截断 | COMPLETED（正文保留已生成部分） |
| REFUSAL | 模型拒答（如问题超出知识库范围） | **COMPLETED，不是 FAILED** |

`REFUSAL` 必须是 COMPLETED：拒答是**正确行为**，尤其在"知识库里没有答案"时。把拒答记为 FAILED 会让"应拒答召回率"这个指标（见 `docs/eval/metrics.md`）永远刷不上去，还会诱导实现去讨好模型编答案。

**为什么 `done` 重复携带 `content`**：它是落库后的权威值，客户端可以用它对账拼接结果，丢包/漏帧能立刻发现。代价是长回答的响应体近似翻倍——接受这个代价，因为"客户端显示的和数据库存的不一致"是最难排查的一类缺陷。

违反时：拼接全部 `delta.text` 的结果与 `done.content` 不一致 → `PROTOCOL_VIOLATION`（`INV-S9`）。

### 2.7 `error`

流失败结束。**必须是最后一个事件，且与 `done` 互斥。**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| statusCode | number | 是 | 该失败在非流式请求下应对应的 HTTP 状态码 |
| code | string | 是 | 第 6 节错误码 |
| message | string | 是 | 人类可读；不得含堆栈、SQL、内部主机名、密钥 |
| retryable | boolean | 是 | 客户端用同一 `clientMessageId` 重试是否可能成功 |
| partialContentPersisted | boolean | 是 | 已产生的部分正文是否已落库 |

```json
{"seq":6,"messageId":"101","statusCode":504,"code":"MODEL_TIMEOUT","message":"模型响应超时，请重试。","retryable":true,"partialContentPersisted":true}
```

前三个字段与 `phase-1-api.md` 2.5 节的统一错误体同构，前端可以复用同一段错误渲染代码。

**取消是唯一的特例**：用户主动取消不是错误，但为了保住"流必以 `done`|`error` 结束、且 `done` 只表示成功"这条不变量，取消也走 `error` 通道，`code` 为 `AGENT_CANCELLED`，`statusCode` 为 `200`。

违反时：前端按 `statusCode >= 400` 判断失败 → 会把取消误判为成功。因此前端必须按 `code` 分支，`statusCode` 只用于日志与埋点。

### 2.8 事件顺序不变量

这一节是 `SseEventSequenceValidator` 的完整规格。**服务端产出违例是缺陷，消费端遇到违例必须拒绝整个流，不得猜测意图去修补顺序。**

| 编号 | 不变量 | 违反时 |
| --- | --- | --- |
| INV-S1 | `seq` 从 1 开始，严格递增，步长恒为 1 | `PROTOCOL_VIOLATION`；缺号意味着丢帧，继续渲染就是给用户看不完整的答案 |
| INV-S2 | 流以 `done` 或 `error` 结束，二者互斥，且必须是最后一个事件 | `PROTOCOL_VIOLATION`；`done` 后再出现任何事件一律丢弃并把消息标记 FAILED |
| INV-S3 | 每个 `tool_result` 必须有同 `callId` 的**先行** `tool_call` | `PROTOCOL_VIOLATION`；孤儿 `tool_result` 不得落库（这正是 `out-of-order` fixture 要拦的） |
| INV-S4 | 同一 `callId` 至多一个 `tool_call` 和至多一个 `tool_result` | `PROTOCOL_VIOLATION` |
| INV-S5 | 以 `done` 结束时，不允许存在未配对的 `tool_call` | `PROTOCOL_VIOLATION`；以 `error` 结束时**允许**未配对（轮次被中断，属正常） |
| INV-S6 | `citation.marker` 从 1 开始严格递增、不跳号、不重复 | `PROTOCOL_VIOLATION` |
| INV-S7 | `delta.text` 非空 | `PROTOCOL_VIOLATION` |
| INV-S8 | 所有事件的 `messageId` 相同 | `PROTOCOL_VIOLATION`；不同 `messageId` 意味着两轮的流被串在了一起 |
| INV-S9 | 拼接全部 `delta.text` == `done.content` | `PROTOCOL_VIOLATION` |
| INV-S10 | `step` 从 1 开始、单调不减（同一 step 可含多个并行 `tool_call`） | `PROTOCOL_VIOLATION` |
| INV-S11 | 流在没有 `done`/`error` 的情况下结束 | **不是违例**，是 `TRUNCATED`：可恢复的断连，按 2.9 节处理 |

`INV-S11` 与其余条目性质不同，必须区分对待：截断是网络的正常故障，协议违例是代码缺陷。把两者都当成"失败"会让真正的 bug 淹没在网络噪声里。

### 2.9 心跳、超时与断连

| 项 | 值 | 说明 |
| --- | --- | --- |
| 服务端心跳 | 无事件满 15 秒发一行 SSE 注释 `:hb` | 注释行不是事件，**不占用 `seq`** |
| 客户端断连判定 | 45 秒收不到任何字节 | 视为 `TRUNCATED` |
| 单次模型调用超时 | 60 秒 | → `error` `MODEL_TIMEOUT` |
| 单次工具执行超时 | 见各工具 `timeoutMs`（默认 3 秒） | → `tool_result` `status=ERROR` `code=TOOL_EXECUTION_FAILED`，**不终止轮次** |
| 整轮墙钟预算 | **待定 T-3** | 见第 8 节 |

心跳的作用是让"模型思考很久"和"连接已经死了"可区分。没有心跳，反向代理和客户端都无法分辨这两种沉默，只能靠一个很长的超时兜底，用户体验和故障定位都会变差。

断连（`TRUNCATED`）的处理：

1. 客户端**不得**用新的 `clientMessageId` 重发，必须复用原值（沿用 `phase-1-api.md` 10.1 第 6 条）；
2. 客户端可以先调 `GET .../messages` 看该轮是否其实已经完成——服务端生成不依赖客户端连接存活，连接断了生成仍在继续；
3. 服务端侧：客户端断开**不**自动取消生成。理由见 7.3 节。

违反时：客户端为断连生成新 UUID → 数据库里出现两条内容相同的 USER 消息，幂等约束形同虚设。

---

## 3. 消息状态机

### 3.1 状态集合

状态只加在 **ASSISTANT 消息**上。USER 消息一次写入即终态，没有状态列。

```text
PENDING ──► STREAMING ──► COMPLETED
   │            │      └─► FAILED
   │            └────────► CANCELLED
   ├──────────────────────► FAILED
   └──────────────────────► CANCELLED
```

| 状态 | 含义 | 是否终态 |
| --- | --- | --- |
| PENDING | 占位消息已落库，尚未产出任何事件 | 否 |
| STREAMING | 已产出至少一个 `delta` / `tool_call` 事件 | 否 |
| COMPLETED | 收到 `done` | 是 |
| FAILED | 收到 `error`（`code != AGENT_CANCELLED`），或协议违例，或孤儿恢复 | 是 |
| CANCELLED | 用户主动取消 | 是 |

### 3.2 合法迁移表

| 编号 | 迁移 | 触发条件 |
| --- | --- | --- |
| M1 | PENDING → STREAMING | 写出第一个 `delta` 或 `tool_call` |
| M2 | PENDING → FAILED | 首字节前失败：模型不可用/限流、内容过滤命中、参数装配失败 |
| M3 | PENDING → CANCELLED | 首字节前收到取消 |
| M4 | STREAMING → COMPLETED | 写出 `done` |
| M5 | STREAMING → FAILED | 写出 `error`；或消费端检出协议违例；或孤儿恢复扫描命中 |
| M6 | STREAMING → CANCELLED | 收到取消且生成已实际停止（时序见 3.6 节） |
| M7 | FAILED → PENDING | **仅**由同 `clientMessageId` 的重试触发，`attempt` +1（见 7.2 节） |
| M8 | CANCELLED → PENDING | 同 M7 |

M7/M8 是本状态机唯一的"终态回退"，且**只能由用户显式重试驱动**，不能由任何后台任务驱动。没有这两条，一次失败的回答就永远无法在原地重试，用户只能换 `clientMessageId`，而那会污染消息历史。

### 3.3 非法迁移的处理

| 情况 | 处理 |
| --- | --- |
| COMPLETED → 任何状态 | **拒绝**，抛 `IllegalStateException`，记 ERROR 日志。成功的回答不可被覆盖 |
| 终态 → 终态（含同状态重复写入） | **幂等忽略**，返回当前状态，不报错。重复的 `done`/取消请求是正常的网络重传 |
| PENDING → COMPLETED（跳过 STREAMING） | **拒绝**。零 `delta` 的成功回复不存在，见 `INV-M4` |
| STREAMING → PENDING | **拒绝**，只有 M7/M8 从终态回退是合法的 |

违反时：任何非法迁移都必须让写入方**失败快**并留日志，绝不静默丢弃。静默丢弃会造成"数据库状态和用户看到的不一致"，且没有任何线索可查。

### 3.4 各终态的数据库残留

这是"断连后一致性"测试的断言依据。下表的"必须"就是测试要逐条检查的东西。

| 编号 | 不变量 | 适用终态 |
| --- | --- | --- |
| INV-M1 | 一个 `(conversationId, clientMessageId)` 至多一条 USER 消息、至多一条 ASSISTANT 消息，无论重试几次 | 全部 |
| INV-M2 | `errorCode` 非空 ⟺ 状态为 FAILED。CANCELLED 的 `errorCode` 必须为 `null`（取消不是错误） | 全部 |
| INV-M3 | 不允许存在未被任何进程持有、且停留在 PENDING/STREAMING 的消息（由 3.5 节恢复扫描保证） | 全部 |
| INV-M4 | 不允许 `status=COMPLETED` 且 `content` 为空的 ASSISTANT 消息 | COMPLETED |
| INV-M5 | 每个 `tool_call` 在库中都有终态（`OK`/`ERROR`/`ABORTED`），不允许残留 `RUNNING` | 全部 |
| INV-M6 | 落库的引用条数 == 正文中不同内联标记数（见 `INV-C3`） | COMPLETED |
| INV-M7 | 部分正文必须保留：FAILED / CANCELLED 时已产出的 `delta` 必须已落库，`content` 可为空字符串但不得为 `null` | FAILED、CANCELLED |

`INV-M7` 值得单独解释：失败时把已生成的半截回答丢掉，是很自然的实现（"反正失败了"），但对用户很糟——他已经看到那半截文字了，刷新后凭空消失。更糟的是排查故障时丢失了"模型当时到底说了什么"这个最关键的证据。

### 3.5 孤儿轮次恢复

进程崩溃或重启时，正在进行的轮次没有机会写终态，会留下"看起来还在生成、其实没人在生成"的消息（违反 `INV-M3`）。

恢复约定：

1. 应用启动后、以及之后每 60 秒，扫描 `status IN (PENDING, STREAMING)` 且 `startedAt` 早于 `now - 5 分钟` 的 ASSISTANT 消息；
2. 将其置为 FAILED，`errorCode = AGENT_INTERRUPTED`；
3. 同时把其下 `RUNNING` 的工具调用置为 `ABORTED`（满足 `INV-M5`）；
4. 该消息随后可被用户用同一 `clientMessageId` 重试（M7）。

5 分钟阈值必须**大于**"单次模型超时 + 整轮可能的步数"，否则会把正在正常工作的长轮次误杀。这个阈值与待定项 T-3（整轮预算）耦合，定 T-3 时必须一起复核。

### 3.6 取消的时序与幂等语义

取消是**异步**的：`POST .../cancel` 只登记取消意图，**不等待**生成实际停止。

| 项 | 约定 | 违反时 |
| --- | --- | --- |
| 响应码 | 登记成功即返回 `202 ACCEPTED`，响应体为空 | 若改为阻塞到生成停止再返回，cancel 请求本身就需要一套自己的超时策略，且与 7.3 节"断连不取消生成"的线程模型难以对齐 |
| 幂等 | 重复 cancel 同一 `messageId` 一律返回 `202`，不报错，也不产生第二次状态迁移 | 前端重复点击或网络重传会打出 4xx，用户看到莫名其妙的报错 |
| 对终态消息 cancel | 消息已是 COMPLETED / FAILED / CANCELLED 时，按 3.3 节"终态 → 终态幂等忽略"返回 `202`，**不改变已有状态** | 允许 CANCELLED 覆盖 COMPLETED，成功的回答会被抹掉，违反 3.3 节第一行 |
| 对不存在或不可见的消息 cancel | `404 NOT_FOUND` | 区分"不存在"与"不是你的"等于提供了一个 ID 探测器，理由同 5.6 节 |

**取消的生效窗口**，按强度从高到低：

1. cancel 登记后，**不得再发起任何新的模型调用或新的工具调用**；
2. 已在执行中的工具调用允许跑完，其 `tool_result` 正常写出（中断执行中的工具会留下不确定的副作用状态，比强行中止更糟）；
3. 流上**至多再出现 2 个 `delta`**（已在途的增量），随后必须写出 `error` `AGENT_CANCELLED` 并终止。

第 3 条的"2"是一个可测的上界而不是目标值。没有这个上界，"取消生效了"和"取消没生效但模型刚好也停了"在测试里无法区分，断言就只能退化成"最终状态是 CANCELLED"——那条断言在实现完全忽略 cancel 请求时也会通过。

| 编号 | 不变量 | 违反时 |
| --- | --- | --- |
| INV-M8 | 取消后流的最后一个事件必为 `error` `AGENT_CANCELLED`，其后无任何事件；cancel 登记之后不再有新的模型调用或新的 `tool_call` | `PROTOCOL_VIOLATION`。这是 `INV-S2` 在取消路径上的具体化 |
| INV-M9 | CANCELLED 消息的 `errorCode` 必须为 `null`（取消不是错误，见 `INV-M2`），且已产出的部分正文必须保留（见 `INV-M7`） | 取消会在监控上被统计成故障，掩盖真实故障率；用户看到的半截回答刷新后凭空消失 |

取消走 `error` 通道但 `errorCode` 落库为 `null`，这个不对称是刻意的：**流协议**需要"非成功即 error"来保住 `INV-S2` 的形状，**数据模型**需要区分"失败"和"用户不想要了"。2.7 节的 `statusCode: 200` 是同一件事在 HTTP 层的体现。

### 3.7 建议的持久化形态（提案，未落地）

本文不新增迁移脚本。以下是满足 3.4 节不变量所需的最小数据形态，供实现阶段参考：

`messages` 表新增列：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| status | varchar | 仅 ASSISTANT 行有值；`PENDING`/`STREAMING`/`COMPLETED`/`FAILED`/`CANCELLED` |
| finish_reason | varchar | 见 2.6 节，可空 |
| error_code | varchar | 见 `INV-M2`，可空 |
| attempt | int | 从 1 开始，重试 +1（见 7.2 节） |
| started_at / finished_at | datetime | 用于 3.5 节恢复扫描与延迟指标 |

新增表：

| 表 | 关键约束 | 用途 |
| --- | --- | --- |
| `agent_tool_calls` | `UNIQUE(message_id, call_id)`；另有 `UNIQUE(message_id, tool_name, args_hash)` 用于副作用工具幂等（见 7.4 节） | 工具调用审计与幂等 |
| `message_citations` | `UNIQUE(message_id, marker)` | 引用落库，支撑 `INV-M6` |

不建 `agent_steps` 表：步号可由 `agent_tool_calls.step_index` 推出，单独建表属于"为假想场景创建结构"（AGENTS.md 1.2）。

---

## 4. 引用（citation）

### 4.1 字段语义与可空性

| 字段 | 类型 | 可空 | 语义 |
| --- | --- | --- | --- |
| marker | number | 否 | 正文内联标记 `[^n]` 的编号，从 1 开始 |
| docId | string | 否 | 被引文档标识；格式见 4.4 节 |
| chunkId | string | **是** | 分块标识；分块策略定型前一律为 `null`（见待定项 T-1） |
| snippet | string | 否 | 支撑该论断的原文片段 |
| score | number | 否 | 检索相关度，归一化到 `[0, 1]` |
| knowledgeBaseId | string | 否 | 所属知识库 ID；用于越权审计 |

`snippet` 的硬约束：

- 必须是被检索 chunk 原文的**连续子串**，不得是模型改写的结果；
- 长度 ≤ 300 个 Unicode 码点，超长从尾部截断并追加 `…`；
- 只能来自**当前用户有权访问**的 chunk。

违反时：`snippet` 不是原文子串 → 落库前拒绝该条引用并记 WARN。允许模型改写 snippet 等于允许它伪造证据，引用就失去了全部价值。

`score` 为什么不可空：可空的分数会让检索指标（Recall@k、nDCG@k）计算里到处是 `null` 分支。若某种召回方式（如纯关键词匹配）没有原生分数，实现必须给出确定的归一化值，而不是留 `null` 把问题推给下游。

`knowledgeBaseId` 为什么必填：它让"越权泄露率恒为 0"（见 `docs/eval/metrics.md`）可以用一句 SQL 直接验证，不必反查 `docId`。

### 4.2 引用与正文的对应方式

**同时使用内联标记与独立事件**，两者通过 `marker` 关联：

- 正文中出现 `[^1]`、`[^2]`；
- 每个标记对应一个 `citation` 事件，携带该标记的元数据。

只用内联标记（把 `snippet` 塞进正文）会污染正文并让复制文本变得难看；只用独立数组（末尾列参考文献）则无法表达"这一句依据的是哪一篇"。

选 `[^n]` 这个记法是因为它是 Markdown 脚注语法，前端不做特殊处理时也能降级为可读文本，不会显示成乱码。

### 4.3 一致性不变量

| 编号 | 不变量 | 违反时 |
| --- | --- | --- |
| INV-C1 | 每个 `citation.marker` 在正文中至少出现一次 | 落库前丢弃该条引用并记 WARN（多余引用无害，但说明检索与生成脱节） |
| INV-C2 | 正文中每个 `[^n]` 都有对应的 `citation` 事件 | **必须从正文中剥离该标记**再落库，不得留悬空标记 |
| INV-C3 | 落库引用条数 == 正文中不同标记数（`INV-C1` + `INV-C2` 的结果） | 见上 |
| INV-C4 | 每条引用的 `knowledgeBaseId` 必须在本轮调用者的可见范围内 | **整轮标记 FAILED**，`errorCode = FORBIDDEN`，并记 ERROR 告警 |

`INV-C2` 的处理方式（剥离而不是报错）需要说明：悬空标记通常是模型幻觉出来的 `[^3]`，不是代码缺陷，为它整轮失败太重；但留在正文里会让用户点击一个不存在的引用。剥离是"无害化"，与注入防护的处理思路一致。

`INV-C4` 是**唯一**会因引用问题导致整轮失败的情形。它意味着检索层漏掉了权限过滤——这是安全缺陷而不是质量问题，必须响亮地失败。

### 4.4 `docId` 格式

`{knowledgeBaseId}/{documentId}`，两段均为十进制字符串，如 `1/50`。

评测集（`docs/eval/*.jsonl`）中使用可读的占位形式 `kb-1/doc-3`，因为评测题目在真实语料导入前没有数据库 ID。实现阶段导入真实语料时必须回填真实 ID。

---

## 5. 工具契约

### 5.1 注册条目格式

每个工具在服务端注册表里是一条这样的记录（示例为 `kb_search`）：

```json
{
  "name": "kb_search",
  "description": "在指定知识库中检索与问题相关的文档片段。只能检索调用者有权访问的知识库。",
  "parameters": {
    "type": "object",
    "additionalProperties": false,
    "required": ["query", "knowledgeBaseId"],
    "properties": {
      "query": {"type": "string", "minLength": 1, "maxLength": 500},
      "knowledgeBaseId": {"type": "string", "minLength": 1, "maxLength": 20},
      "topK": {"type": "integer", "minimum": 1, "maximum": 20, "default": 5}
    }
  },
  "requiredRole": "CUSTOMER",
  "resourceScope": "KNOWLEDGE_BASE_GRANT",
  "sideEffect": false,
  "idempotent": true,
  "timeoutMs": 3000,
  "failureMode": "RETRYABLE"
}
```

| 字段 | 说明 | 违反时 |
| --- | --- | --- |
| name | 见 5.2 节命名规范 | 应用启动失败（快速失败） |
| description | 喂给模型的说明，必须写清**边界**而不只是功能 | 无法机器校验；由 code review 保证 |
| parameters | JSON Schema，子集见 5.3 节 | 用到子集外的关键字 → 应用启动失败 |
| requiredRole | `CUSTOMER` \| `EDITOR`，调用者角色低于此值 → 拒绝 | `tool_result` `code=FORBIDDEN` |
| resourceScope | `NONE` \| `CURRENT_CONVERSATION` \| `KNOWLEDGE_BASE_GRANT`，决定 5.6 节的资源级检查 | 缺省视为注册错误 → 应用启动失败 |
| sideEffect | 是否改变系统状态 | `sideEffect=true` 且 `failureMode=RETRYABLE` → 应用启动失败（自动重试有副作用的工具会造成重复执行） |
| idempotent | 重复执行同参数是否等价于执行一次 | 见 7.4 节 |
| timeoutMs | 单次执行超时 | 超时 → `TOOL_EXECUTION_FAILED`，不终止轮次 |
| failureMode | `RETRYABLE`（至多重试 1 次）\| `NON_RETRYABLE` | — |

"应用启动失败"这个处理方式是刻意的：工具注册表是配置而不是运行时输入，配错了应该在部署时炸，而不是等到某个用户恰好触发那个工具才炸。

### 5.2 命名规范

| 对象 | 规范 | 正例 | 反例 |
| --- | --- | --- | --- |
| 工具名 | `snake_case`，`^[a-z][a-z0-9_]{2,31}$`，动宾结构，域前缀在前 | `kb_search`、`doc_fetch` | `search`（无域）、`getDocument`（camelCase）、`kb-search`（连字符） |
| 参数名 | camelCase，与 HTTP 层一致 | `knowledgeBaseId` | `knowledge_base_id` |

工具名里**不得**出现用户 ID、租户 ID 或任何身份信息（例如不允许 `kb_search_for_user_7`）。身份来自认证主体，不来自工具名或参数——这是 5.6 节信任边界的前提。

违反时：不符合正则 → 应用启动失败。

### 5.3 支持的 JSON Schema 子集

只支持以下关键字。**刻意保持很小**，因为这份 schema 有两个消费者：服务端校验器和模型；关键字越多，模型理解错的概率越高，校验器的实现也越容易和模型的理解产生偏差。

| 关键字 | 适用类型 | 说明 |
| --- | --- | --- |
| `type` | — | 根必须是 `object`；属性可用 `string`/`integer`/`number`/`boolean`/`array`/`object` |
| `required` | object | 必填属性名数组 |
| `additionalProperties` | object | **必须显式写 `false`** |
| `properties` | object | 属性定义 |
| `enum` | string/integer/number | 允许值 |
| `minLength` / `maxLength` | string | 按 **Unicode 码点**计数，不按 UTF-16 长度 |
| `minimum` / `maximum` | integer/number | 闭区间 |
| `items` | array | 元素定义 |
| `maxItems` | array | 元素数上限 |
| `default` | — | 仅用于喂给模型的文档说明；**校验器不做默认值填充**，缺省值由工具实现自己决定 |

明确**不支持**：`oneOf` / `anyOf` / `allOf` / `not` / `$ref` / `pattern` / `format` / `minProperties` / `patternProperties`。

违反时：注册表中出现不支持的关键字 → 应用启动失败，错误信息必须指出工具名与关键字名。

`minLength`/`maxLength` 按码点计数是为了与 `phase-1-api.md` 2.7 节保持一致（那里的长度校验也按码点）。若按 UTF-16 长度算，一个 emoji 会占 2，中文用户提交的内容长度限制就会莫名其妙地收紧一半。

`additionalProperties` 必须显式写出而不是给默认值：默认值会让"忘记写"和"故意允许"无法区分。

### 5.4 参数校验顺序与错误映射

校验器必须按**固定顺序**判定，第一条命中的违规决定 `reason`。顺序固定是为了让同一份输入永远得到同一个错误码——否则 fixture 驱动的测试会随实现内部的遍历顺序变绿变红。

| 序 | 检查 | code | reason |
| --- | --- | --- | --- |
| 1 | 工具名存在于注册表 | `TOOL_NOT_FOUND` | — |
| 2 | `arguments` 是合法 JSON **对象** | `TOOL_ARGUMENT_INVALID` | `MALFORMED_JSON` |
| 3 | `arguments` 序列化后 ≤ 8 KiB；单个字符串属性 ≤ 2000 码点 | `TOOL_ARGUMENT_INVALID` | `TOO_LARGE` |
| 4 | `required` 全部出现且不为 `null` | `TOOL_ARGUMENT_INVALID` | `MISSING_REQUIRED` |
| 5 | 无 schema 未定义的属性 | `TOOL_ARGUMENT_INVALID` | `UNKNOWN_PROPERTY` |
| 6 | 各属性类型匹配 | `TOOL_ARGUMENT_INVALID` | `TYPE_MISMATCH` |
| 7 | 长度 / 范围 / `enum` / `maxItems` 约束 | `TOOL_ARGUMENT_INVALID` | `CONSTRAINT_VIOLATION` |

补充规则：

- **不做隐式类型转换**。`"5"` 传给 `integer` → `TYPE_MISMATCH`。隐式转换会把模型的系统性错误（把 ID 当字符串还是数字搞混）掩盖成偶发问题；ID 字段上尤其危险。
- `integer` 接受**无小数部分**的数值（`5`、`5.0`），拒绝 `5.5` 与任何字符串。
- 校验器必须收集**全部**违规项放进 `details` 数组一并回喂模型（让模型一次改对，而不是被挤牙膏式地纠正 3 轮，白烧 3 次 token），但 `reason` 只取上表顺序中的第一条。
- **第 3 步的日志约束**：超长参数命中时，日志只允许记录`工具名 + 属性名 + 长度`，**不得记录参数值**。超长参数极可能是注入 payload 或被塞进去的大段上下文，写进日志等于把它复制到一个通常权限更宽、留存更久的地方。

违反时（顺序被打乱）：同一 fixture 在不同实现下得到不同 `reason`，测试失去判别力。因此这张表的顺序本身就是被测契约。

### 5.5 内置工具清单

第一版三个工具。选择理由：一个纯检索（无副作用）、一个纯读取（幂等）、一个有副作用（用来把审批与幂等语义落到实处）。

| name | 参数 | requiredRole | resourceScope | sideEffect | idempotent | failureMode |
| --- | --- | --- | --- | --- | --- | --- |
| `kb_search` | `query`(必), `knowledgeBaseId`(必), `topK` | CUSTOMER | KNOWLEDGE_BASE_GRANT | false | true | RETRYABLE |
| `doc_fetch` | `docId`(必) | CUSTOMER | KNOWLEDGE_BASE_GRANT | false | true | RETRYABLE |
| `conversation_title_set` | `title`(必, 1~100 码点) | CUSTOMER | CURRENT_CONVERSATION | **true** | true | NON_RETRYABLE |

**`conversation_title_set` 没有 `conversationId` 参数**，这是刻意的：会话 ID 由服务端从当前请求上下文注入，模型无法指定。凡是"当前上下文唯一确定"的资源标识都不应该暴露成工具参数——参数是模型可控的攻击面，上下文注入不是。

`kb_search` 的 `knowledgeBaseId` 只能是参数（用户可能有多个知识库，必须让模型选），所以它必须承受 5.6 节的资源级检查。这两个工具的对比正是"什么时候能用上下文注入消掉攻击面、什么时候必须做运行时检查"的样板。

### 5.6 权限与信任边界

**信任边界画在"模型输出"和"工具执行"之间。** 模型输出（工具名、参数、要访问的资源 ID）与用户提交的 HTTP 请求体属于同一信任级别：完全不可信。

| 编号 | 不变量 | 违反时 |
| --- | --- | --- |
| INV-T1 | 权限主体**只能**来自认证上下文（`AuthenticatedUser`），永不来自工具参数、模型输出或知识库文档内容 | 缺陷，必须整轮 FAILED 并告警 |
| INV-T2 | 每次工具执行前都重新做角色检查与资源级检查，不缓存上一步的结论 | 同上。授权可能在轮次进行中被撤销 |
| INV-T3 | 资源级检查复用 HTTP 层的同一段授权逻辑，不为 Agent 另写一份 | 两份实现必然漂移，其中一份会先出现漏洞 |
| INV-T4 | 用户 A 的轮次在任何模型输出、任何文档内容下，都不可能读到用户 B 的数据 | **硬门禁**：这是不可协商的安全边界 |

`resourceScope` 的检查规则：

| resourceScope | 检查 |
| --- | --- |
| `NONE` | 只查 `requiredRole` |
| `CURRENT_CONVERSATION` | 资源固定为当前会话，无需检查模型输入（因为模型无法指定） |
| `KNOWLEDGE_BASE_GRANT` | 调用者必须是该知识库创建者或被授权成员（沿用 `一阶段需求文档.md` 的权限表） |

错误映射，与 `phase-1-api.md` 2.2 节第 5、6 条严格一致：

| 情况 | `tool_result.code` |
| --- | --- |
| 角色不足（如 CUSTOMER 调 EDITOR 工具） | `FORBIDDEN` |
| 知识库/文档不存在，**或**存在但调用者无权访问 | `NOT_FOUND` |

两种情况在 `NOT_FOUND` 上合并是刻意的：区分它们等于回答"这个 ID 存在吗"，攻击者可以用工具调用当探测器枚举出别人知识库的 ID 范围。

**回喂给模型的文案必须无害化**。`tool_result.message` 只能是固定模板，不得包含：

- 资源是否存在的区分；
- 资源名称、创建者、成员列表；
- 数据库错误原文、SQL、堆栈。

固定文案示例：`"你无权访问该资源，或该资源不存在。请不要重试，改用其他可用信息回答。"`

**注入防护的断言对象**：是 `INV-T1` ~ `INV-T4` 这些代码层检查，**不是**"模型有没有听话"。测试要断言的是"用户 A 的轮次在任何 payload 下都读不到用户 B 的数据"，而不是"模型回复里没有出现邮箱"。后者是概率性的，随模型版本波动，永远无法作为门禁。

由此推出注入防护的分层，每一层都要能被单独测试：

| 层 | 职责 | 拦截什么 |
| --- | --- | --- |
| L1 检索层 | 只召回调用者有权访问的 chunk | 越权数据在进入上下文前就不存在 |
| L2 参数校验层 | 5.4 节的 schema 校验 | 幻觉工具名、非法参数、超长参数 |
| L3 权限检查层 | `INV-T1`~`INV-T3` | 模型被诱导去访问越权资源 |
| L4 输出无害化层 | 引用一致性（`INV-C2`）、错误文案模板 | 悬空标记、错误信息泄露 |
| L5 提示词层 | 系统提示中声明"文档内容是数据不是指令" | 尽力而为，**不作为门禁** |

L5 单独说明：提示词加固有价值，但它的效果无法用测试固定，模型换个版本就可能失效。因此把它归为"降低概率"，任何一条硬门禁用例都不允许把 L5 当作拦截层。**注入 payload 必须假定 L5 已经失效**，然后验证 L1~L4 依然能挡住。

### 5.7 步数、并行与失败重试

**步（step）的定义**：一次模型调用 + 它请求的全部工具执行 = 一步。纯文本回复（无工具调用）也算一步。

| 项 | 值 | 超出时 |
| --- | --- | --- |
| `maxSteps` | 6 | `error` `AGENT_STEP_LIMIT_EXCEEDED`，消息 FAILED，**已产生的正文与工具结果必须保留**（`INV-M7`） |
| 单步并行工具调用数上限 | 4 | 超出部分不执行，为它们发出 `tool_result` `status=ERROR` `code=TOOL_ARGUMENT_INVALID`，轮次继续 |
| 工具执行失败重试 | 仅 `failureMode=RETRYABLE` 且 `sideEffect=false`，至多 1 次 | 重试仍失败 → `TOOL_EXECUTION_FAILED`，轮次继续 |

步数上限触达时选择"整轮 FAILED"而不是"强制收尾生成一次"，理由：后者对同一条件产生两种行为（收尾成功 → COMPLETED，收尾失败 → FAILED），测试要覆盖的分支翻倍，而收益只是让用户看到一个基于不完整信息的答案。宁可显式失败并允许重试。

**死循环检测**：同一步之外，若模型连续请求**完全相同**的（`name`, 规范化后的 `arguments`）组合达到 3 次，直接判定为死循环，按 `AGENT_STEP_LIMIT_EXCEEDED` 处理，不必等到 `maxSteps`。

违反时（未做死循环检测）：模型反复调用同一个失败的工具会烧掉全部 6 步预算和相应 token，用户等待时间被拉到最长，而结果注定失败。

并行工具调用的结果配对：多个 `tool_call` 可以在同一 `step` 内并发执行，`tool_result` 的到达顺序**不要求**与 `tool_call` 顺序一致，但每个 `tool_result` 必须携带正确的 `callId`（`INV-S3`、`INV-S4`）。

违反时：按 `tool_call` 的顺序而不是 `callId` 配对结果 → 在并发执行下会把 A 工具的结果当成 B 工具的结果喂给模型，产生难以察觉的错误答案。这是 `parallel-tool-calls` fixture 要守的东西。

### 5.8 喂给模型的投影

注册表条目不是原样发给模型的。投影规则：

| 注册表字段 | 是否发给模型 |
| --- | --- |
| name、description、parameters | **发**（`parameters` 原样，模型需要 schema 才能填对参数） |
| requiredRole、resourceScope | 不发 |
| sideEffect、idempotent、timeoutMs、failureMode | 不发 |

不发权限相关字段的原因：模型知道"这个工具需要 EDITOR"没有任何用处，它无法自行判断当前用户的角色；反而会让它编造理由拒绝或尝试提权。权限是服务端的事，模型不需要参与。

`description` 必须写清边界（"只能检索调用者有权访问的知识库"），这是**降低概率**的措施（L5 层），不改变服务端必须做检查的事实。

---

## 6. 错误码

扩展 `phase-1-api.md` 2.6 节的表。原表中的通用码继续有效，本节只列新增。

| statusCode | code | 场景 | retryable | 出现位置 |
| --- | --- | --- | --- | --- |
| 504 | `MODEL_TIMEOUT` | 单次模型调用超过 60 秒 | true | `error` |
| 429 | `MODEL_RATE_LIMITED` | 模型侧限流 | true | `error` |
| 503 | `MODEL_UNAVAILABLE` | 模型服务不可达、鉴权失败、返回空内容且无工具调用 | true | `error` |
| 400 | `TOOL_NOT_FOUND` | 模型请求了注册表中不存在的工具名 | false | `tool_result` |
| 400 | `TOOL_ARGUMENT_INVALID` | 参数未通过 5.4 节校验；附 `reason` | false | `tool_result` |
| 500 | `TOOL_EXECUTION_FAILED` | 工具本身执行失败或超时 | 视工具 | `tool_result` |
| 400 | `AGENT_STEP_LIMIT_EXCEEDED` | 触达 `maxSteps` 或命中死循环检测 | false | `error` |
| 200 | `AGENT_CANCELLED` | 用户主动取消（见 2.7 节的特例说明） | true | `error` |
| 400 | `CONTENT_FILTERED` | 输入或输出命中内容安全策略 | false | `error` |
| 500 | `AGENT_INTERRUPTED` | 进程崩溃/重启导致轮次中断，由 3.5 节恢复扫描写入 | true | 仅落库，不出现在流中 |
| 500 | `PROTOCOL_VIOLATION` | 违反第 2 节任一 `INV-S*`（`INV-S11` 除外） | false | 仅落库，不出现在流中 |
| 403 | `FORBIDDEN` | 工具调用者角色不足（复用通用码） | false | `tool_result` |
| 404 | `NOT_FOUND` | 工具目标资源不存在或不可见（复用通用码） | false | `tool_result` |

> `AGENT_INTERRUPTED` 与 `PROTOCOL_VIOLATION` 是本文在 `agent-test-plan.md` 第 2 节清单之外新增的两个码。前者是 3.4 节 `INV-M2`/`INV-M3` 的必然要求（孤儿恢复必须写一个 `errorCode`，复用 `MODEL_UNAVAILABLE` 会让"模型挂了"和"我们自己挂了"在监控上无法区分）；后者是第 2 节全部不变量的落库归宿。

`retryable` 的语义：**用同一 `clientMessageId` 重试是否可能得到不同结果**。`TOOL_NOT_FOUND` 是 false，因为注册表不会因为重试而改变；`MODEL_RATE_LIMITED` 是 true。

违反时：把 `retryable=false` 的失败做自动重试 → 白烧 token 且用户等待时间翻倍；把 `retryable=true` 的失败标成 false → 用户遇到一次限流就永久卡住。

`CONTENT_FILTERED` 的 `message` 不得包含被过滤的原文片段，也不得说明命中了哪条策略。

---

## 7. 异步下的幂等语义

### 7.1 与第一阶段的结构差异

第一阶段：一个事务包住"校验 → 写 USER → 写 ASSISTANT → 更新会话"。

Agent 阶段拆成三段，**只有第一段和第三段在事务里**：

```text
[事务 1]  校验 → 查会话归属 → 写 USER 消息 → 写 PENDING 的 ASSISTANT 占位 → 更新会话 updatedAt
          （提交）
[无事务]  调模型 / 执行工具 / 推送 SSE 事件 / 增量落库 delta 与工具调用
[事务 2]  写终态：status / content / finishReason / errorCode / finished_at
```

为什么必须先提交事务 1 再调模型：

1. `messageId` 必须在第一个事件之前存在（见 2.1 节）；
2. 长事务会一直持有数据库连接和行锁，几十秒的模型调用会把连接池打穿；
3. 崩溃时至少留下一条可被 3.5 节恢复扫描处理的记录——如果什么都没提交，用户的问题就凭空消失了。

代价：不再有"两条消息要么都有要么都没有"的原子性。取而代之的保证是**"USER 消息一旦存在，ASSISTANT 消息必然存在且必然会到达某个终态"**，前半句由事务 1 保证，后半句由 3.5 节的恢复扫描保证。

### 7.2 重试语义

幂等键仍是 `(conversationId, clientMessageId)`，最终保证仍是数据库唯一约束 `uk_messages_conversation_client_message`，不靠"先查后插"。

命中已有记录时，按 ASSISTANT 消息的状态分派：

| 已有 ASSISTANT 状态 | 行为 | HTTP / 流 |
| --- | --- | --- |
| COMPLETED | 不调模型。**先按当前授权重新校验每条引用的 `knowledgeBaseId`**（见 `INV-I7`），全部通过才把已落库的正文与引用**重放**成一个完整的 SSE 流（delta × 1 + citation × n + done） | 200 + 流 |
| COMPLETED，但至少一条引用的知识库已不在可见范围 | **拒绝重放**，不返回任何正文 | 404（首字节前，走 HTTP 状态码） |
| PENDING / STREAMING，且有活跃生产者 | 不新起一轮，**附着**到既有轮次：先重放已落库的事件，再继续推送后续事件 | 200 + 流 |
| PENDING / STREAMING，但无活跃生产者（孤儿） | 先按 3.5 节置为 FAILED，再按下一行处理 | 200 + 流 |
| FAILED / CANCELLED | **允许重新生成**：不新建 USER 消息，把 ASSISTANT 置回 PENDING，`attempt` +1，新起一轮（M7/M8） | 200 + 流 |
| 任意状态，但 `content` 与首次请求不一致 | `IDEMPOTENCY_CONFLICT` | 409（首字节前，走 HTTP 状态码） |

| 编号 | 不变量 | 违反时 |
| --- | --- | --- |
| INV-I1 | 无论重试多少次，`(conversationId, clientMessageId)` 至多一条 USER + 一条 ASSISTANT | 消息历史出现重复气泡；用户以为自己发了两次 |
| INV-I2 | COMPLETED 的轮次重试**绝不**再调模型 | 重复计费；且用户可能拿到与第一次不同的答案，同一条消息内容却变了 |
| INV-I3 | 重新生成时 `attempt` 必须递增，且旧 attempt 的工具调用记录保留 | 丢失"上一次为什么失败"的证据 |
| INV-I4 | 重试请求的 `content` 与首次不一致 → 409，不得静默采用新内容 | 幂等键失去意义，同一 UUID 可以覆盖任意内容 |

**这是与第一阶段最大的语义差异**：第一阶段"命中即返回原结果"；Agent 阶段"命中且处于失败终态时允许原地重跑"。没有这一条，一次模型超时就会让那条消息永久失败，用户唯一的出路是换 `clientMessageId` 重发，而那会在历史里留下两条相同的问题。

`INV-I7` 与 3.3 节的"成功的回答不可被覆盖"存在张力，取舍如下：**生成时有权，不等于此刻有权。** 落库的正文里通常已经复述了越权知识库的内容，只剥离 `citation` 事件而照常重放正文，等于把数据本体留在了返回体里，防了个寂寞——所以是整条拒绝而不是部分剥离。同时这不算"覆盖"：消息在库里仍是 COMPLETED，`INV-I2`（不再调模型）依然成立，被拒绝的只是**这一次读取**。授权恢复后重放会重新可用。

同理，第一阶段的 `GET .../messages` 在读取带引用的历史消息时也必须做同一道校验，否则重放路径堵上了、列表路径还开着。这条属于 `phase-1-api.md` 的范围，实现时需要一并处理。

### 7.3 客户端断连不取消生成

客户端断开连接**不**触发取消。理由：

- 断连绝大多数是网络抖动或页面刷新，用户仍然想要那个答案；
- 生成已经花掉的 token 是沉没成本，取消并不省钱，重来才最贵；
- 用户重连后可以按 7.2 节附着回同一轮，体验是"刷新页面答案还在继续写"。

取消**只能**由显式的 cancel 端点触发。

违反时（把断连当取消）：移动端网络切换会随机杀掉正在进行的回答，且症状难以复现。

### 7.4 副作用工具的幂等

有副作用的工具在"重新生成"（`attempt` 递增）时可能被执行第二次。防护方式是给工具执行本身加幂等键：

**幂等键 = `(messageId, toolName, argsHash)`**，其中 `argsHash` 是**规范化后**参数的哈希（属性名按字典序排序、去掉未定义属性、数值规范化后序列化再哈希）。

命中已有成功记录时，直接复用上次的 `result`，不真正执行工具。

| 编号 | 不变量 | 违反时 |
| --- | --- | --- |
| INV-I5 | 幂等键**不含** `attempt`。同一条消息在多次 attempt 中用相同参数调用同一有副作用工具，只真正执行一次 | 重试一次失败的回答会把标题设置两次（本例无害），换成发通知、扣款一类工具则是真实事故 |
| INV-I6 | 幂等复用只对 `status=OK` 的记录生效；失败记录不复用 | 一次偶发失败会被永久缓存成失败 |
| INV-I7 | COMPLETED 轮次重放前，必须按**当前**授权重新校验每条引用的 `knowledgeBaseId`；任一条越权则整条拒绝重放，返回 `404 NOT_FOUND`，且不得返回任何正文片段 | 授权被撤销后重放旧消息，会成为一条绕开 `INV-T4` 的越权读取路径 |

`argsHash` 必须用规范化后的参数：`{"a":1,"b":2}` 和 `{"b":2,"a":1}` 是同一次调用，若直接对原始字符串哈希，模型换个属性顺序就能绕过幂等。

---

## 8. 待定项

以下事项本文**不下结论**，需要用户决策。每项都标注了"定了之后要改哪里"，便于评估代价。

| 编号 | 待定项 | 取舍 | 影响面 |
| --- | --- | --- | --- |
| T-1 | **模型 SDK 选型** | 决定模型响应 JSON 的具体结构。本文与 fixture 按 OpenAI `tool_calls` 形状书写（`{"tool_calls":[{"id","type","function":{"name","arguments"}}]}`，`arguments` 是 JSON **字符串**） | fixture 的字段结构、`ToolCallParser` 的解析入口。**场景清单与错误码不受影响** |
| T-2 | **SSE vs WebSocket** | 本文按 SSE。WebSocket 换来双向通信（前端可直接发打断帧、可多路复用），代价是握手期鉴权与心跳要自己搭 | 改则第 1、2 节重写；事件类型、字段、状态机、错误码可整体搬迁 |
| T-3 | **整轮墙钟预算** | 只有 `maxSteps=6` 和单步 60 秒超时，缺一个整轮上限。设了上限就需要一个新错误码（本文未预设，避免和 `AGENT_STEP_LIMIT_EXCEEDED` 语义混淆） | 第 6 节加一个码；3.5 节的 5 分钟孤儿阈值必须一起复核 |
| T-4 | **工具执行同步还是投递到队列** | 本文按同步执行（在推流的同一线程内）。投递到队列换来长任务能力和削峰，代价是要处理"工具做完了但连接已经断了"，还要引入消息中间件 | 影响 `agent-test-cases.md`「步数与失败路径」分组的用例形状 |
| T-5 | **是否需要人工审批环节** | `AGENTS.md` 第 5 节提到 Agent 的"审批"。本文**未**引入审批状态。若需要，状态机要加 `PENDING_APPROVAL`（在 M1 与 M4 之间），并新增审批端点与超时策略 | 第 3 节加状态与迁移；第 5 节注册表加 `requiresApproval`；测试规格加一组用例 |
| T-6 | **`chunkId` 与分块策略** | 分块策略未定，`citation.chunkId` 一律 `null`，评测集 `relevantChunkIds` 一律空数组 | 定型后回填；4.1 节的可空性改为不可空 |
| T-7 | **空内容是否需要专用错误码** | 本文把"模型返回空内容且无工具调用"归入 `MODEL_UNAVAILABLE`。若要区分，需新增 `MODEL_EMPTY_RESPONSE` | 第 6 节加一个码；`empty-content` fixture 的期望码随之改变 |
| T-8 | **`attempt` 是否设上限** | 7.2 节的 M7/M8 允许失败终态原地重试并把 `attempt` +1，但没有上限。无上限意味着同一条消息可以被无限次触发模型调用，是一条可被滥用的成本放大路径；设上限则需要回答"用完次数后用户还能怎么办" | 7.2 节的分派表加一行；第 6 节可能需要 `AGENT_RETRY_LIMIT_EXCEEDED`；`agent-test-cases.md` 异步幂等组加一条用例 |

T-1 值得强调：SDK 选型只影响**解析入口的形状**，不影响 5.4 节的校验顺序、错误码和场景清单。这是把"解析"和"校验"分成两层的直接收益——换 SDK 只需要重写解析层。

---

## 9. 变更流程

沿用 `phase-1-api.md` 第 13 节，并补充一条：

**本文任一 `INV-*` 编号的语义发生变化时，必须同步检查 `docs/plans/agent-test-cases.md` 中引用该编号的用例。** 只改文档不改用例，或只改用例不改文档，都会让"测试到底在守什么"这个问题失去答案。

删除或重命名 `INV-*` 编号时，不得复用旧编号表达新语义——旧编号会散落在测试注释和提交记录里，复用会造成长期误读。

---

## 10. 六项清单对照

`agent-test-plan.md` 第 2 节要求写清六件事，逐项对应：

| 计划要求 | 本文章节 |
| --- | --- |
| 1. SSE 事件协议（类型、payload、顺序不变量、心跳超时） | 第 2 节（2.1~2.9） |
| 2. 消息状态机（状态集合、迁移表、非法迁移、终态残留、取消时序） | 第 3 节（3.1~3.7） |
| 3. 引用结构（字段语义可空性、与正文的对应） | 第 4 节 |
| 4. 工具契约格式（name、schema、权限、副作用、幂等、失败语义、命名规范） | 第 5 节（5.1~5.8） |
| 5. Agent 错误码 | 第 6 节 |
| 6. 异步幂等语义（中途失败重试、与第一阶段的差异） | 第 7 节 |
