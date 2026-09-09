# 第二阶段 RAG 学习与实现流程

> 状态：执行基线  
> 建立日期：2026-09-07  
> 适用范围：第二阶段 RAG、Retrieval、Evaluation 与 Agentic RAG 的学习和实现。

## 1. 核心原则：项目驱动 + Just-in-time 学习

第二阶段不采用“先完整学完 RAG 理论，再开始项目”的课程式推进方式。

默认流程固定为：

```text
遇到一个真实工程问题
        ↓
只补解决这个问题必需的知识
        ↓
识别真实 decision gate
        ↓
做最小工程决策
        ↓
实现一个可独立验证的垂直切片
        ↓
测试 / 观察结果
        ↓
再根据失败现象补底层原理
        ↓
进入下一个切片
```

理论学习服务于当前工程问题，不以覆盖知识点数量为目标。

如果一个知识点当前不会改变实现、测试、诊断或工程决策，就延迟到真正需要时再学习。

## 2. 前置知识控制规则

开始一个新切片前，只补满足以下任一条件的知识：

1. 不理解它就无法解释当前数据流；
2. 不理解它就无法在多个方案之间做决策；
3. 不理解它就无法设计有效测试；
4. 不理解它会造成明显的安全、一致性或性能风险；
5. 当前失败现象直接依赖这个原理才能定位。

避免为了“知识完整性”提前展开：

- HNSW 完整算法细节；
- IVF / PQ 等其他 ANN 算法；
- 所有 Embedding 距离度量的数学推导；
- Hybrid Search、Reranker、Query Rewrite 的完整体系；
- Agent 框架和多 Agent 编排；
- 与当前数据规模无关的分布式向量基础设施。

这些内容用到时再补。

## 3. 当前已经具备的最小前置知识

进入 P2.1 实现前，当前已经建立以下概念：

```text
Document
   ↓ Chunking
Chunk
   ↓ Embedding
Vector
   ↓ Similarity Search / Vector Index
Top-K Chunks
   ↓
Context
   ↓
LLM
   ↓
Answer
```

已明确：

- RAG 不等于模型训练，知识不会因为一次 RAG 调用写入模型参数；
- Document 通常不能简单作为一个大向量，Chunk 用于形成更精细的检索单元；
- Embedding 是文本到向量表示的过程，Vector 是结果；
- Cosine Similarity 主要比较向量方向；归一化向量下 Dot Product 与 Cosine 排序等价；
- 大规模向量检索不能长期依赖逐个精确比较，ANN / HNSW 属于向量索引问题；
- Chunking 解决“文档如何拆”，Vector Index 解决“海量向量如何快速找候选”，两者职责不同。

以上知识已经足够开始 P2.1，不继续扩充无直接用途的前置理论。

## 4. P2.1 的实施顺序

P2.1 不直接从完整问答链路开始，而是拆成可独立验证的垂直切片。

### Slice 1：Retrieval Baseline

第一刀只验证检索，不接 LLM。

```text
选一个现有 Document
        ↓
读取 documents.content
        ↓
Chunking
        ↓
生成 Embedding
        ↓
持久化 Chunk + Vector + Metadata
        ↓
输入 Query
        ↓
Query Embedding
        ↓
限制在指定 Knowledge Base 内检索
        ↓
Top-K Chunks
```

核心验收不变量：

> 给定一个已知文档和一个已知问题，包含正确答案的 chunk 应进入 Top-K，并且返回结果能够追溯到真实 document/chunk。

这一切片明确不做：

- LLM 最终回答；
- Prompt 设计；
- Citation UI；
- Reranker；
- Hybrid Search；
- Query Rewrite；
- Agent；
- PDF / Word 解析；
- 复杂异步索引任务。

### 为什么先只做 Retrieval

如果一开始直接接入 LLM，回答错误时会同时存在多个可能原因：

```text
Chunk 切错？
Embedding 不合适？
检索没召回？
Top-K 不合理？
Prompt 拼接有问题？
LLM 没有忠实使用上下文？
```

先把 Retrieval 独立出来，可以把第一阶段问题收敛为：

```text
正确证据有没有被找到？
```

只有这个问题稳定后，再增加 Generation。

### Slice 2：Generation Baseline

Slice 1 通过后再接：

```text
Top-K Chunks
    ↓
Context Builder
    ↓
LLM
    ↓
Answer + Sources
```

此时重点验证：

- 模型是否只基于提供的上下文回答；
- 无证据时是否明确说明缺少依据；
- 引用是否能回到真实 chunk/document。

### Slice 3：Evaluation Baseline

在完整最小 RAG 跑通后，把现有 `docs/eval/` 数据集接入真实 pipeline，先建立 Retrieval 的可重复 baseline，再逐步增加 Answer Evaluation。

## 5. 每个切片的协作学习闭环

每个核心切片按以下顺序进行：

### A. 先看项目事实

阅读当前真实代码、数据库迁移、配置、测试和已有文档，画出调用链和数据流，不根据教程假设项目结构。

### B. 只补当前必要原理

例如进入 `DocumentChunk` 设计时才学习：

- chunk size 太大/太小分别造成什么问题；
- overlap 为什么存在；
- chunk metadata 为什么需要保留来源位置。

不提前展开其他检索技术。

### C. Decision Gate

只有真实工程取舍才暂停交给用户，例如：

- Chunk 与 Embedding 如何存储；
- 使用哪个 Embedding 模型；
- 使用哪种向量存储；
- 文档更新后的索引一致性策略；
- 会话与知识库的绑定关系。

机械代码和无争议实现不重复提问。

### D. 实现最小切片

只修改完成当前外部行为必须的代码，不同时引入下一阶段能力。

### E. 代码展示与讲解规则

代码不是隐藏实现细节。每个切片在落代码后，都要把关键实现拿出来讲，并遵守以下规则：

1. **展示的代码块内直接写关键中文注释**，让读者在阅读代码本身时就能知道每一步在做什么；
2. 代码块之后再解释“为什么这样设计”，但不能只贴一段无注释代码后马上跳去讲别的概念；
3. 只展示当前切片关键路径，不为了完整性贴大量无关 getter、import 或机械样板；
4. 对关键变量说明它代表的数据含义、单位和边界，例如 `cursor` 是 UTF-16 char index，而持久化 offset 使用 Unicode code point；
5. 对防御性分支说明它在保护什么不变量，例如“cursor 必须前进”“不能产生空 chunk”；
6. 测试代码也要带注释，明确每条测试锁定的是哪条行为契约或不变量；
7. 如果实现里存在已知限制、未来替换点或尚未验证项，要和代码一起指出，不把 baseline 写成最终方案。

推荐展示顺序：

```text
先给带注释的关键代码
        ↓
逐段解释数据如何流动
        ↓
解释为什么这样设计
        ↓
指出测试验证什么
        ↓
指出当前限制与下一步
```

### F. 用可观察行为验证

优先验证：

```text
输入是什么
→ 数据如何变化
→ 检索返回什么
→ 为什么判断正确
```

不得只以“接口 200”或“程序没报错”作为 RAG 正确性的证明。

### G. 从失败现象反推下一知识点

例如：

- 正确 chunk 没召回 → 再研究 Chunk / Embedding / Top-K；
- 召回正确但排序差 → 再研究 Rerank；
- 专有名词搜不到 → 再考虑 Hybrid / BM25；
- 用户表达和文档措辞差异大 → 再考虑 Query Rewrite；
- 固定 RAG 流程不能满足决策需求 → 再进入 Agentic RAG。

技术的引入必须由真实失败模式驱动。

## 6. 当前立即执行的任务

当前停止继续扩充前置理论，正式进入 P2.1 Slice 1。

接下来依次完成：

1. 阅读现有 `documents` 数据模型、迁移、Service 和测试；
2. 明确 `Document -> Chunk` 的最小数据模型和索引一致性边界；
3. 到达 Chunk 策略 decision gate 时，只学习第一版切分所需知识；
4. 到达 Embedding / Vector Store decision gate 时，对项目可选方案做最小比较；
5. 实现第一条 `Document -> Chunk -> Embedding -> Top-K Search` 路径；
6. 用可控文档和 query 验证正确 chunk 是否进入 Top-K；
7. 通过后再进入 LLM Generation。

后续如果学习过程开始偏离当前实现问题，应优先回到本流程检查：这个知识点是否会改变当前的实现、测试或决策。
