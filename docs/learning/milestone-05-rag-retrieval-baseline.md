# 里程碑 05：RAG Retrieval Baseline

> 状态：P2.1 Slice 1 进行中  
> 当前范围：Document -> Chunk；Embedding / Exact Retrieval 尚未接入真实模型  
> 推进方式：见 [phase2-rag-learning-workflow.md](./phase2-rag-learning-workflow.md)

## 1. 当前目标

先独立验证 Retrieval，不接聊天和 LLM：

```text
Document
  -> Chunk
  -> Embedding
  -> MySQL vector persistence
  -> Query Embedding
  -> Java Exact Search
  -> Top-K Chunks
```

第一条验收不变量是：给定已知文档和已知问题，正确证据 chunk 必须进入 Top-K。

## 2. 已确定的方案

### 2.1 向量存储 baseline：方案 B

P2.1 不直接引入专业 Vector DB。

第一版使用：

```text
MySQL: chunk metadata + embedding JSON
Java: cosine similarity + exact search
```

原因：当前评测语料规模很小，核心问题是先证明 Retrieval 链路正确，而不是提前解决百万级向量性能问题。

专业向量库 / HNSW 等方案等到真实数据量或延迟指标证明 Exact Search 成为瓶颈后再引入。

### 2.2 document_chunks 数据模型

Flyway migration：`../../src/main/resources/db/migration/V9__create_document_chunks.sql`

当前字段包括：

- `knowledge_base_id`
- `document_id`
- `chunk_index`
- `content`
- `start_offset` / `end_offset`
- `embedding`
- `embedding_model`
- `embedding_dimension`
- `source_document_updated_at`
- `created_at` / `updated_at`

`start_offset` / `end_offset` 与现有 `DocumentService` 的 `content_length` 一样使用 Unicode code point 口径：start inclusive，end exclusive。

### 2.3 检索权限资源绑定

`knowledge_base_id` 会直接作为 Retrieval 的可信过滤条件，因此不能只分别给 `knowledge_base_id` 和 `document_id` 建两个外键。

当前 V9 为 `documents (knowledge_base_id, id)` 增加复合唯一键，并让 `document_chunks (knowledge_base_id, document_id)` 使用复合外键引用它。

这保证数据库层无法出现：

```text
chunk.document_id -> 实际属于 KB1 的文档
chunk.knowledge_base_id -> 却伪造为 KB2
```

这是 RAG 权限不变量的一部分，而不仅是数据整洁问题。

## 3. 当前分块 baseline

实现：

- `../../src/main/java/yangsirly/rag_agent/rag/ParagraphChunker.java`
- `../../src/main/java/yangsirly/rag_agent/rag/TextChunk.java`

策略：

1. 最大长度使用 Unicode code point；
2. 最大范围内优先按空行分段；
3. 无空行时优先按换行；
4. 再无边界时硬切；
5. 第一版不做 overlap。

不做 overlap 是为了让 baseline 确定、可解释。若后续 Recall@K 显示答案频繁跨 chunk 边界，再把 overlap 作为单一实验变量加入。

## 4. 测试语料

本地 seed：`../eval/seed-p2-rag-baseline.sql`

固定开发数据：

- KB `900001`：P2 RAG 评测知识库
- Document `900001`：差旅申请流程
- Document `900002`：差旅住宿与交通标准
- Document `900003`：费用报销管理办法
- Document `900004`：办公用品采购规定
- Document `900005`：实习生管理规定

语料与现有 `docs/eval/retrieval-golden.jsonl` 的五类问题对齐：事实型、多文档、否定型和无答案。

seed 不创建虚假的业务用户，而是复用数据库中第一个 `ACTIVE EDITOR`。若本地没有有效编辑者，脚本应失败而不是生成权限语义错误的数据。

## 5. 当前验证状态

- V9 migration：**AI 验证通过**；已在本地 MySQL 8.0.40 执行，Flyway V1～V9 均为 success，`document_chunks` 及其复合外键已核验。
- H2 `schema.sql`：**已同步结构，尚未运行测试**。
- `ParagraphChunkerTests`：**AI 验证通过**；4 tests，0 failures，0 errors。
- 本地 seed：**尚未写入 MySQL**；当前数据库没有 `ACTIVE EDITOR` 用户，按设计不创建虚假业务用户，等待注册真实编辑者后再执行。

执行记录：原先的 V5 在本地库中处于失败状态，但 `deleted_at` 已落盘、旧索引仍存在；已先完成索引替换并将 V5 历史状态修复为 success，再由 Flyway 顺序执行 V6～V9。执行前已生成本地数据库备份。

Hanis Docker 已配置 `host.docker.internal:3306`、专用 `hanis_mcp@%` 数据库账号和 Compose secret；MCP 的 `database_query` 已通过 `SELECT 1` 连接验证。seed 仍不能表述为已完成，直到存在可复用的 `ACTIVE EDITOR`。

## 6. 下一真实 Decision Gate

下一步需要选择 Embedding 边界和首个模型方案，重点比较：

- 中文 / 中英混合质量；
- 是否远程 API 或本地模型；
- 成本与延迟；
- Java/Spring 集成复杂度；
- 向量维度；
- 未来模型升级导致的重建索引成本。

选定后才继续实现：

```text
Document -> ParagraphChunker -> Embedding -> document_chunks
Query -> Embedding -> cosine exact search -> Top-K
```

## 7. 2026-09-09：Chunk 版本化与延迟回收决策

本轮确认：**旧 chunk 不在 Document 更新/软删除请求中立即物理删除。**

当前正确性规则改为由读路径判断：

```text
chunk 可参与 Retrieval
= 源 Document 未软删除
+ chunk.source_document_updated_at == documents.updated_at
+ embedding 已生成
```

对应 SQL 位于 `DocumentChunkMapper.listCurrentEmbeddedChunksByKnowledgeBaseId`：

```sql
-- deleted_at 保证被删除文档立即失去检索资格；
-- updated_at 比较保证旧版本 chunk 立即失去检索资格。
WHERE c.knowledge_base_id = #{knowledgeBaseId}
  AND d.deleted_at IS NULL
  AND c.source_document_updated_at = d.updated_at
  AND c.embedding IS NOT NULL
```

因此，后台物理清理是否及时只影响存储/扫描成本，不再决定知识正确性。

### 数据库版本演进

V9 已在本地 MySQL 执行，视为不可变历史，不能修改 checksum。

新增：`V10__version_document_chunks_by_source_updated_at.sql`。

V10 把唯一键从：

```text
(document_id, chunk_index)
```

改为：

```text
(document_id, source_document_updated_at, chunk_index)
```

否则同一 Document 的 V1/V2 chunk 都存在 `chunk_index=0` 时会发生唯一键冲突。

### 写路径

新增 `DocumentIndexService.buildCurrentVersion`：只为当前 Document 版本 INSERT 新 chunks，不 DELETE 历史 chunks。

接入本地 Ollama 后，`DocumentService.create/update` **不再自动调用 Embedding**。Document 更新只负责推进 `updated_at`，使旧 chunk 立即失效；`DocumentIndexApplicationService` 显式读取有权限的当前 Document，再在数据库事务外执行 Chunk + Embedding。全部向量成功后，由 `DocumentChunkVersionWriter` 用短事务原子写入整版 chunks。

当前使用 `documents.updated_at` 作为版本标识，因此标题、摘要或正文的真实变化都会产生新 chunk 版本。该方案优先保证模型简单和一致性；若后续证明无正文变化时重复索引成本明显，再考虑独立 `content_version/content_updated_at`。

### 当前验证状态

- V10 migration：代码已落盘，**本轮尚未在真实 MySQL 执行验证**。
- `DocumentChunkEntity` / `DocumentChunkMapper` / `DocumentIndexService`：代码已落盘。
- `DocumentServiceTests` 已更新，新增 `DocumentIndexServiceTests`。
- 本轮尝试运行相关 Maven tests，但当前 Hanis shell 没有 `java/javac` 且 `JAVA_HOME` 未设置，因此**不能声称本轮新增测试已通过**。

## 8. 2026-09-09：本地 Embedding 与 Exact Retrieval

### Embedding 方案

当前 baseline 固定为：

```text
Spring Boot
  -> 项目自定义 EmbeddingService
  -> Spring AI EmbeddingModel
  -> 本地 Ollama
  -> qwen3-embedding:0.6b
```

Spring AI 只做 provider 适配，不使用其 VectorStore；Chunk 持久化、cosine 和 Top-K 仍由项目自己实现，以保留学习和可观测性。

`EmbeddingService` 的核心不变量：

- `embedAll` 返回顺序必须与输入文本顺序一致；
- 返回数量必须等于输入数量；
- 不接受空向量；
- `modelName()` 必须稳定写入 `document_chunks.embedding_model`。

模型名也是索引版本的一部分。Query 只允许比较 `embedding_model` 与当前模型一致的 chunks；不同模型即使维度恰好相同，也不能假定处在同一向量空间。

### 事务边界

Embedding 属于本地 HTTP/模型推理，不能长期包在数据库事务里。

当前写路径：

```text
Document 当前版本
  -> Chunking
  -> 批量 Embedding            [无数据库事务]
  -> 在内存中准备完整 rows
  -> insertVersion(rows)      [短事务]
```

这样模型失败不会产生半套索引；INSERT 中途失败又会整版回滚。

### Exact Retrieval

`ExactChunkRetriever` 当前执行：

```text
Query
 -> Embedding
 -> SQL 获取当前 KB 的有效 chunks
 -> JSON -> float[]
 -> 每个候选计算 cosine
 -> score DESC
 -> Top-K
```

候选 SQL 同时要求：

```text
documents.deleted_at IS NULL
source_document_updated_at = documents.updated_at
embedding_model = 当前 Query 模型
embedding IS NOT NULL
```

因此删除、文档更新、Embedding 模型切换三种旧索引都会自动退出 Retrieval，而无需立即物理删除。

### 权限边界

`ExactChunkRetriever` 是底层算法组件，不作为 HTTP/API 权限入口。`RagRetrievalService` 先通过现有 `KnowledgeBaseService.getDetail(userId, knowledgeBaseId)` 校验访问范围，再调用底层 Retriever。

当前知识库权限模型仍以创建者为主；后续成员/reader 权限落地时，只替换可信访问判断，不改变 Exact Search 算法。

### 当前验证状态

- Spring AI 2.0.1 / Ollama Embedding API：已按官方当前文档核对接口。
- `EmbeddingService`、`SpringAiEmbeddingService`、`DocumentChunkVersionWriter`、`ExactChunkRetriever`、`RagRetrievalService`：代码已落盘。
- 已补 `DocumentIndexServiceTests`、`SpringAiEmbeddingServiceTests`、`ExactChunkRetrieverTests`、`RagRetrievalServiceTests`。
- 当前 Hanis 执行环境没有 `java/javac` / `JAVA_HOME`，Maven 测试无法实际启动，因此新增测试仍标记为**待本机运行验证**。
- 当前 Hanis shell 也没有 `ollama` 命令，真实本地模型调用尚未在该执行环境验证。
