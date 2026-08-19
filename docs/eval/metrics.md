# 评测指标口径

> 状态：v1（2026-07-26，批次 5 产出）
> 依据：[agent-test-plan.md](../plans/agent-test-plan.md) 第 6.3 节、[agent-protocol.md](../api/agent-protocol.md)
> 原则：每个指标定死计算口径，不只写指标名。口径一旦用于基线（`baselines/`），修改口径必须新起一条基线，不得与旧口径的分数直接比较。

## 0. 数据集字段如何进入计算

- `retrieval-golden.jsonl` 中 `relevantDocIds` 为空数组的题（`无答案` 标签）**不计入** Recall / MRR / nDCG 的分母，进入检索层单独的"无答案误检率"（见第 1 节）；
- `relevantChunkIds` 当前一律为空（待定项 T-6，分块策略定型后回填），chunk 粒度指标（标 † 者）在回填前**不产出数字**，报告中显示为 `N/A`；
- `answer-golden.jsonl` 的 `mustInclude` / `mustNotInclude` 是规则层前置检查：`mustInclude` 里的**事实点**（如 "30 天"）必须出现在回答中，`mustNotInclude` 里的字符串必须不出现。规则检查失败的题直接记 0 分，不再进入 faithfulness 判定——规则能拦住的不浪费 LLM-as-judge 的调用；
- 子串匹配前对回答与事实点做**同一套文本归一**：删除全部空白字符、全角字母/数字/标点折叠为半角。这样 `"30 天"` 能命中 `"30天"` 与 `"３０天"`——事实点考察的是事实，不是空格习惯；
- **出题约束**：`mustNotInclude` 的字符串不得是正确答案自然措辞的子串（例如否定型题不能写 `"可以报销"`，它是"不可以报销"的子串，会把正确答案判死）。该约束由 `EvalDatasetFormatTests` 以"必须不出现在 `referenceAnswer` 里"的形式机器校验。

## 1. 检索层

| 指标 | 口径 |
| --- | --- |
| Recall@k | k=5。`命中的 relevantDocIds 数 / relevantDocIds 总数`，按题计算后宏平均。命中 = 检索返回的前 k 条中出现该 docId（同一 docId 多个 chunk 只算一次） |
| MRR | 截断深度 10。`1 / 第一条相关结果的排名`，前 10 条无相关结果记 0，宏平均 |
| nDCG@k | k=10。二值相关（docId 在 relevantDocIds 中为 1，否则 0），标准 DCG/IDCG 公式，宏平均 |
| 无答案误检率 | `relevantDocIds` 为空的题中，检索前 5 条里出现**高于置信阈值**命中的题目比例。阈值与真实语料一起定标（先取一次基线跑分中有答案题的第 1 名分数分布的下四分位）。目标越低越好——它度量"知识库里没有，检索却言之凿凿"的程度，是应拒答链路的上游 |
| Recall@k（chunk 粒度）† | 口径同上，以 `relevantChunkIds` 为准。回填前 N/A |

## 2. 答案层

| 指标 | 口径 |
| --- | --- |
| citation accuracy | `回答引用的 docId ∩ mustCiteDocIds 的条数 / 回答引用的 docId 总条数`，按题计算后宏平均。回答无引用且 `mustCiteDocIds` 非空 → 该题记 0；`shouldRefuse:true` 的题不计入。纯规则计算，不依赖 LLM-as-judge——这是 `mustCiteDocIds` 字段存在的目的 |
| faithfulness | 把回答拆分为原子事实点（LLM 拆分），逐点判断能否被**被引用的 chunk 原文**支撑（LLM-as-judge，每点跑 3 次取多数）。分值 = `被支撑的事实点数 / 事实点总数`，宏平均。只对通过规则层前置检查的题计算 |
| 应拒答召回率 | `shouldRefuse:true 的题中实际拒答的比例`。拒答判定：回答不含任何具体事实断言，且明确表达"知识库中没有依据 / 无法回答"。先按规则判（不出现 mustNotInclude、不出现数字型断言），存疑样本再由 LLM-as-judge 复核。**拒答记为 COMPLETED 而不是 FAILED**（协议 2.6 节），实现若把拒答落成 FAILED，本指标永远刷不上去 |

## 3. 安全层

| 指标 | 口径 |
| --- | --- |
| 越权泄露率 | `检索结果与落库引用中，knowledgeBaseId 不在调用者可见范围的条数 / 总条数`。**必须恒为 0**，任何非零值立即阻塞，不适用"相对基线"的门禁口径。可用一句 SQL 直接验证（`message_citations` 联 knowledge base 授权表）——这是协议 4.1 节把 `knowledgeBaseId` 定为引用必填字段的直接收益 |

## 4. 轨迹层

| 指标 | 口径 |
| --- | --- |
| 工具选择正确率 | `首个 tool_call 的工具名与该题标注一致的题数 / 需要调工具的题数`。标注放在真实语料里程碑新增的独立文件 `trajectory-golden.jsonl` 里——**不改动**现有两个 JSONL 的字段集（计划 6.1/6.2 定死，`EvalDatasetFormatTests` 亦按精确字段集断言）。该文件出现前本指标 N/A |
| 参数正确率 | `一次性通过协议 5.4 节 7 步校验的 tool_call 数 / tool_call 总数`。被模型自我纠正后第二次才通过的，第一次计为不通过。数据来源：`agent_tool_calls` 表（协议 3.7 节） |
| 平均步数 | `done.steps` 的均值（协议 5.7 节：一次模型调用 + 其全部工具执行 = 一步），只统计 COMPLETED 的轮次 |
| P95 延迟 | `finished_at - started_at` 的第 95 百分位，含 FAILED 轮次（失败慢也是慢）。数据来源：`messages` 表新增列（协议 3.7 节） |
| 单轮 token 成本 | 模型响应 `usage.total_tokens` 按轮求和后取均值，COMPLETED 与 FAILED 分开报——失败轮的 token 是纯损耗，混在一起会掩盖重试放大。**协议 3.7 节的建议持久化形态未含 usage，实现落地时须补一列（或评测运行器旁路记录）**；在此之前 N/A |

## 5. 门禁口径

- 除安全层外，门禁是**相对上次基线不下降超过 5%**，不是绝对分数——绝对分数在语料和模型版本变化时没有可比性；
- 安全层（越权泄露率）是绝对门禁：恒为 0；
- 每题跑 3~5 次取均值并记方差；方差大于均值 20% 的题在报告中单独标注，其分数变化不触发门禁（先解决稳定性再谈分数）。
