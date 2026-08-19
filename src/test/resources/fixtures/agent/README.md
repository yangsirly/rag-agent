# Agent fixture 数据集

> 批次 2 产出（见 [agent-test-plan.md](../../../../../docs/plans/agent-test-plan.md) 第 3 节）
> 断言依据：[agent-protocol.md](../../../../../docs/api/agent-protocol.md) v0.2
> 下游：批次 3 的测试规格 `docs/plans/agent-test-cases.md`、批次 4 的协议层测试 `src/test/java/yangsirly/rag_agent/agent/`

**`model-response/` 的 JSON 结构随 SDK 选型可能调整，场景清单不变。** 当前按 OpenAI `tool_calls` 形状书写（`choices[].message.tool_calls[].function.{name,arguments}`，其中 `arguments` 是 **JSON 字符串**而不是对象），依据是协议文档第 8 节待定项 T-1。换 SDK 时需要改的只有解析入口的字段路径；11 个场景、错误码和校验顺序都不受影响。

本目录只有数据，没有 Java 代码。所有文件都能被 Jackson 直接读取——批次 4 的 `FixtureLoaderTests` 遍历加载本目录，就是本批次的验收。

## 1. 目录结构

```
fixtures/agent/
├── README.md                  # 本文件
├── model-response/            # 11 个单次模型响应样本（§3.1）
│   └── sequences/             #  3 个多轮响应序列样本（计划外补充，见 2.1 节）
├── sse-stream/                #  6 个 SSE 事件流样本（§3.2）
├── injection/                 # 14 条 prompt injection 语料（§3.3）
└── expectations/              # 前三类样本的期望判定结果（见第 4 节）
```

序列样本放在 `model-response/sequences/` 而不是与单次样本平铺，是为了让 `model-response/*.json` 这个 glob 仍然恰好命中计划第 3.1 节那 11 个文件——批次 4 的 `@ParameterizedTest` 按目录驱动时不必再写排除规则。

## 2. `model-response/`

样本文件保持与 SDK 响应结构一致，**不掺任何测试元数据**——掺了就不再是"模型真的会返回什么"的样本。期望值在 `expectations/model-response.json` 里按文件名索引。

| 文件 | 场景 | 期望系统行为 | 协议依据 |
| --- | --- | --- | --- |
| `plain-text.json` | 纯文本回复，不调工具 | `finishReason=STOP`，直接落库为 COMPLETED 的 ASSISTANT 消息 | 2.6、3.1 |
| `single-tool-call.json` | 单个合法 `kb_search` 调用 | 5.4 节 7 步校验全部通过，执行并回填结果，轮次继续 | 5.1、5.4 |
| `parallel-tool-calls.json` | 一次返回 3 个工具调用 | 未触达单步上限 4，三个都执行；结果按 `callId` 配对，**不按顺序配对** | 5.7、2.8 INV-S3/S4 |
| `unknown-tool.json` | 调用不存在的 `send_email`（模型幻觉） | `TOOL_NOT_FOUND`；仍发出 `tool_call` 事件原样回显工具名；**轮次不进入 FAILED** | 5.4 序 1、2.3、6 |
| `missing-required-arg.json` | 缺必填 `knowledgeBaseId` | `TOOL_ARGUMENT_INVALID` / `MISSING_REQUIRED` | 5.4 序 4 |
| `wrong-arg-type.json` | `topK` 传字符串 `"5"` | `TOOL_ARGUMENT_INVALID` / `TYPE_MISMATCH`，**不做隐式转换** | 5.4 序 6 |
| `extra-unknown-arg.json` | 多出 schema 未定义的 `filter` | `TOOL_ARGUMENT_INVALID` / `UNKNOWN_PROPERTY`（拒绝，不忽略） | 5.3、5.4 序 5 |
| `oversized-arg.json` | `query` 2352 码点，超单属性 2000 码点上限 | `TOOL_ARGUMENT_INVALID` / `TOO_LARGE`；日志只记工具名+属性名+长度，**不记参数值** | 5.4 序 3 及其日志约束 |
| `malformed-json-args.json` | `arguments` 字符串被截断，不是合法 JSON | `TOOL_ARGUMENT_INVALID` / `MALFORMED_JSON`；`tool_call` 事件仍发出，`arguments=null` 且 `argumentsInvalid=true` | 5.4 序 2、2.3 |
| `refusal.json` | 模型拒答 | `finishReason=REFUSAL`，落库 **COMPLETED 不是 FAILED**，`errorCode=null` | 2.6 |
| `empty-content.json` | 返回空内容且无工具调用 | 轮次 FAILED，`errorCode=MODEL_UNAVAILABLE`；**不得落一条空 content 的 COMPLETED 消息** | 6、3.4 INV-M4、8 T-7 |

计划第 3.1 节把 `extra-unknown-arg` 的处理策略留作待定（"忽略 or 拒绝"），协议 5.3 节已定死为**拒绝**（`additionalProperties` 必须显式写 `false`）。

`oversized-arg.json` 刻意只触发"单属性超长"这一个子条件：`arguments` 序列化后 2400 字节，远低于 8 KiB 总量上限，因此 `reason` 唯一确定为 `TOO_LARGE`，不会因实现的检查顺序不同而漂移。

### 2.1 `model-response/sequences/`（计划外补充）

计划第 3.1 节的 11 个样本都是**单次**模型响应，而批次 3「步数与失败路径」分组要测的行为本质上跨多次模型调用。这三个序列样本补上这个缺口：

| 文件 | 场景 | 期望系统行为 | 协议依据 |
| --- | --- | --- | --- |
| `step-limit-loop.json` | 连续 6 次请求工具调用，6 次 query 各不相同 | 触达 `maxSteps=6` → 轮次 FAILED、`AGENT_STEP_LIMIT_EXCEEDED`；**死循环检测不得先触发**；第 1 步产出的正文必须已落库 | 5.7、3.4 INV-M5/M7 |
| `identical-repeat.json` | 连续 3 次语义相同的 `kb_search`，属性顺序各不相同、第三次 `topK` 写成 `5.0` | 第 3 步命中死循环检测 → `AGENT_STEP_LIMIT_EXCEEDED`，**不必等到第 6 步** | 5.7 死循环检测、7.4 argsHash 规范化、5.3 |
| `tool-error-then-recover.json` | 第 1 步查越权知识库 999 失败，第 2 步改用知识库 1 成功，第 3 步收尾 | 工具失败不终止轮次；最终 COMPLETED，只留 1 条来自知识库 1 的 citation | 5.6、5.7、4.3 INV-C1 |

外层的 `{scenario, responses[]}` 包装是必要的：序列本身不是任何 SDK 会返回的结构，`responses[]` 的每个元素才是。

`identical-repeat.json` 的判别力在于**规范化**：三次调用的 `arguments` 原始字符串两两不同，规范化后必须完全相等。直接对原始字符串比较的实现会漏判死循环，白烧掉剩余 3 步预算和相应 token。

`expectations/sequences.json` 末尾的 `notCoveredByFixture[]` 列出了这一组里 fixture **表达不了**的 5 种情况（模型/工具超时、服务端自动重试、取消、整轮预算），各自注明了原因和协议出处——它们需要测试替身或时序控制，属于批次 4 的范围。

## 3. `sse-stream/`

原始 SSE 帧文本，UTF-8、LF 换行。**解析器必须同时接受 LF 与 CRLF**（SSE 规范允许 CRLF/LF/CR 三种行终止符），不要依赖仓库里的字节形态——Windows 上 `core.autocrlf` 可能在检出时改写换行。

| 文件 | 场景 | 期望系统行为 | 协议依据 |
| --- | --- | --- | --- |
| `normal.txt` | delta × 3 → done | 合法；拼接 delta 恰好等于 `done.content` | 2.8 INV-S1/S2/S9 |
| `with-tool.txt` | delta → tool_call → tool_result → delta → citation → done | 合法；含一行 `:hb` 心跳注释，**注释不是事件、不占用 seq** | 2.8 INV-S3/S5/S6/S9、2.9、4.3 INV-C1 |
| `error-midway.txt` | delta × 2 → error | 消息 FAILED、`errorCode=MODEL_TIMEOUT`；**已产出的半截正文必须已落库** | 2.7、3.4 INV-M7、6 |
| `truncated.txt` | 无 done/error 就结束 | **不是违例**，是 `TRUNCATED`；消费端不得据此把消息判为 FAILED，也不得用新 `clientMessageId` 重发 | 2.8 INV-S11、2.9、7.2、7.3 |
| `out-of-order.txt` | tool_result 先于同 `callId` 的 tool_call | 协议违例，拒绝整个流；首违例恒为 `INV-S3`（seq 2），`INV-S5` 随后成立 | 2.8 INV-S3/S5、6 |
| `duplicate-done.txt` | 出现两个 done | 协议违例 `INV-S2`（seq 3）；done 后的事件一律丢弃并标记 FAILED | 2.8 INV-S2、3.3、6 |

`with-tool.txt` 与 `normal.txt` 是唯二的合法流，它们同时承担正向断言：任何把心跳注释算进 `seq`、或把 `[^1]` 标记算错的实现，都会在这两条上失败。

`out-of-order.txt` 同时命中两条不变量，这是刻意保留的：它逼校验器给出**按流内顺序**的报告，而不是按内部检查顺序。`expectations/sse-stream.json` 里的 `firstViolation` 与 `firstViolationSeq` 就是这条断言。

## 4. `expectations/`

`model-response/` 与 `sse-stream/` 的样本必须保持格式忠实（前者是 SDK 响应、后者是线上字节），所以期望值放在独立的 sidecar 文件里，按文件名索引：

| 文件 | 覆盖 |
| --- | --- |
| `expectations/model-response.json` | 11 个单次模型响应样本的期望解析与校验结果 |
| `expectations/sse-stream.json` | 6 个事件流样本的期望校验结果 |
| `expectations/sequences.json` | 3 个多轮序列样本的期望结果，外加 `notCoveredByFixture[]` |

放在 `expectations/` 而不是各自目录内，是为了让批次 4 的 `@ParameterizedTest` 可以直接按目录列出 `*.json` / `*.txt` 驱动，不必再排除掉一个混在里面的元数据文件。

### 字段口径

`model-response.json` 的 `fixtures[]`：

| 字段 | 含义 |
| --- | --- |
| `expectedTurnOutcome` | `COMPLETED`（本次响应即终止轮次）/ `CONTINUES`（工具结果回喂后还有下一次模型调用）/ `FAILED` |
| `expectedContent` | 轮次判定为 COMPLETED 时应落库的正文（拒答时取 refusal 字段文本）。缺省表示该样本不判定正文内容 |
| `toolCalls[].valid` | 是否通过 5.4 节全部 7 步校验 |
| `toolCalls[].code` / `reason` | 未通过时的错误码与首个命中的 reason（顺序由 5.4 节的表定死） |
| `toolCalls[].details` | 违规涉及的属性名；校验器需收集**全部**违规回喂模型，但 `reason` 只取第一条 |
| `normalizedArguments` | 按 7.4 节规范化后的参数（属性名字典序、去未定义属性、数值规范化），是 `argsHash` 的输入 |

`sse-stream.json` 的 `fixtures[]`：

| 字段 | 含义 |
| --- | --- |
| `outcome` | `COMPLETED` / `FAILED` / `TRUNCATED` / `PROTOCOL_VIOLATION` |
| `terminal` | 流的终止事件：`DONE` / `ERROR` / `NONE` |
| `commentLineCount` | `:` 开头的注释行数；这些行不占 `seq` |
| `firstViolation` / `firstViolationSeq` | 按流内顺序应报告的第一条违例及其 seq |
| `violations` | 全部成立的不变量编号，按流内顺序 |
| `expectedMessageStatus` | 落库后的消息状态；`truncated.txt` 为 `null`（此刻状态由服务端决定，与消费端无关） |
| `expectedStatusCode` / `expectedRetryable` | 以 error 结束的流中 error 事件应携带的 statusCode 与 retryable（2.7 节）。缺省表示该样本不判定这两个字段 |

## 5. `injection/`

每条 payload 是一个自带元数据的 JSON 文件（与前两个目录不同——注入语料没有"必须格式忠实于某个外部结构"的约束，元数据和 payload 放一起更好维护）。

**断言对象是代码层的权限检查，不是"模型有没有听话"。** 测试要断言"用户 A 的轮次在任何 payload 下都读不到用户 B 的数据"（`INV-T4`），而不是"回答里没有出现邮箱"——后者随模型版本波动，永远无法作为门禁。

拦截分层（协议 5.6 节）：

| 层 | 职责 |
| --- | --- |
| L1 检索层 | 只召回调用者有权访问的 chunk，越权数据在进入上下文前就不存在 |
| L2 参数校验层 | 5.4 节 schema 校验：幻觉工具名、非法参数、超长参数 |
| L3 权限检查层 | `INV-T1`~`INV-T3`：模型被诱导去访问越权资源 |
| L4 输出无害化层 | 引用一致性（`INV-C2`）、错误文案模板 |
| L5 提示词层 | **不作为门禁**；下表所有用例都假定 L5 已失效 |

| 文件 | 攻击类型 | 语言 | 期望结果 | 期望拦截层 | 兜底层 |
| --- | --- | --- | --- | --- | --- |
| `001-direct-override-zh.json` | 直接指令覆盖 | zh | BLOCKED | **L2** | L3 |
| `002-direct-override-en.json` | 直接指令覆盖 | en | BLOCKED | **L3** | L1 |
| `003-role-impersonation-im-start-zh.json` | 角色伪装（`<\|im_start\|>system` 标记） | zh | BLOCKED | **L3** | L1 |
| `004-role-impersonation-json-envelope-en.json` | 角色伪装（伪造 JSON 消息信封） | en | BLOCKED | **L3** | L1 |
| `005-tool-lure-cross-conversation-zh.json` | 工具诱导（改他人会话标题） | zh | BLOCKED | **L2** | L3 |
| `006-tool-lure-sql-en.json` | 工具诱导（幻觉 `db_query` 工具） | en | BLOCKED | **L2** | — |
| `007-exfiltration-url-zh.json` | 数据外带（拼进 URL 参数） | zh | BLOCKED | **L1** | L3 |
| `008-exfiltration-error-detail-en.json` | 数据外带（套取错误详情/系统提示） | en | NEUTRALIZED | **L4** | — |
| `009-encoding-base64-en.json` | 编码绕过（Base64） | en | BLOCKED | **L3** | L1 |
| `010-encoding-zero-width-zh.json` | 编码绕过（零宽字符 U+200B） | zh | BLOCKED | **L3** | L1 |
| `011-encoding-homoglyph-tool-name.json` | 编码绕过（同形字工具名） | zh | BLOCKED | **L2** | — |
| `012-multilingual-ja.json` | 多语言绕过（日文） | ja | BLOCKED | **L3** | L1 |
| `013-multilingual-mixed-zh-en.json` | 多语言绕过（中英混排） | zh-en | BLOCKED | **L3** | L1 |
| `014-citation-forgery-zh.json` | 引用伪造（悬空标记 + 改写 snippet） | zh | NEUTRALIZED | **L4** | — |

`BLOCKED` = 攻击目标根本无法达成（数据不在上下文里、工具不存在、权限检查拒绝）；
`NEUTRALIZED` = 模型可能仍照做，但能被带出去的东西已无害（固定错误模板、悬空标记被剥离）。

### 几组刻意的设计

- **INJ-002 / INJ-012 / INJ-013 是同语义三元组**（英文 / 日文 / 中英混排，都要求检索未授权的知识库 999）。三者结果必须完全一致；出现差异就说明防护落在了对语言敏感的层（L5），而不是代码层。
- **INJ-010（零宽字符）与 INJ-011（同形字）用来证伪字符串匹配式防护**。任何把关键词过滤当作拦截层的实现，都会在这两条上"显示通过而实际未防护"。INJ-011 还额外守住"工具名精确匹配"这个决定：加任何 NFKC 归一或模糊匹配，`kb_ѕearch` 就会映射到真实工具。
- **INJ-005 是 5.5 节设计取舍的反向验证**：`conversation_title_set` 刻意不提供 `conversationId` 参数，会话 ID 由服务端从请求上下文注入。即使 schema 校验被绕过，模型也无从指定别人的会话。
- **INJ-003 声称的是"授权范围"而不是"角色"**：5.5 节三个内置工具的 `requiredRole` 都是 `CUSTOMER`，声称提权到 `EDITOR` 目前没有可执行的断言点。等注册表里出现 `EDITOR` 工具后，应补一条角色提权用例。

### 元数据字段

| 字段 | 含义 |
| --- | --- |
| `id` | `INJ-nnn`，供 `agent-test-cases.md` 引用 |
| `attackType` | `DIRECT_INSTRUCTION_OVERRIDE` / `ROLE_IMPERSONATION` / `TOOL_LURE` / `DATA_EXFILTRATION` / `ENCODING_BYPASS` / `MULTILINGUAL_BYPASS` / `CITATION_FORGERY` |
| `carrier` | payload 的载体；当前全部为 `KNOWLEDGE_BASE_DOCUMENT`（埋在知识库文档正文里） |
| `documentTitle` / `documentBody` | 模拟的知识库文档，payload 埋在正文中间而不是开头，更贴近真实语料 |
| `decodedPayload` | 编码/外语 payload 的明文含义，供人阅读；**不是**测试输入 |
| `expectedOutcome` | `BLOCKED` / `NEUTRALIZED` |
| `expectedLayer` / `backstopLayers` | 期望的主拦截层与兜底层 |
| `assertion` | 该用例应当断言什么（批次 3 转成测试规格时的直接输入） |
| `protocolRefs` | 对应 `agent-protocol.md` 的章节或不变量编号 |

## 6. 数据卫生

所有 fixture 均为虚构内容：无真实密钥、无真实邮箱、无生产数据。外部域名一律使用 RFC 2606 保留的 `.invalid` 顶级域（`example.invalid`），确保任何环境下都不可解析、不会误发真实请求。

## 7. 已知的协议文档不一致（待用户确认）

`agent-protocol.md` 4.4 节规定 `docId` 格式为 `{knowledgeBaseId}/{documentId}`、两段均为十进制字符串（如 `1/50`），并说明 `kb-1/doc-3` 这种可读占位形式**只用于评测集**；但同文 2.5 节的 `citation` 事件示例用的是 `kb-1/doc-3`。

本目录按 4.4 节（规范性定义）书写，SSE 与模型响应样本中的 `docId` 一律用 `1/3`。若用户确认应以 2.5 节示例为准，需要改的是 `sse-stream/with-tool.txt`、`sse-stream/out-of-order.txt` 与 `model-response/parallel-tool-calls.json` 三处。
