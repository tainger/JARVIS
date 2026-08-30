# JARVIS 简易 RAG：工作流程、原理、缺点与演进路线

> 更新时间：2026-08-30。
> 范围：当前已落地的 RAG 实现（`src/main/java/com/example/jarvis/rag/`）。
> 关联文档：[setup-guide.md](setup-guide.md)（启动与配置）、[llm-wiki-research.md](llm-wiki-research.md)（下一代知识库技术调研）。

---

## 一、总体架构

```
                    ┌──────────────────────────────────────────┐
                    │              导入链路（写时）              │
 POST /api/knowledge/documents                                   │
   │                  ① 清洗：剥 HTML（script/style 删除、        │
   │                     块级标签转行、实体解码）                  │
   │                  ② 分块：标题感知 + 面包屑前缀                │
   │                     （"手册 > 报销制度"）500/800/重叠80       │
   ▼                  ③ 向量化：分批（4条/批）Ollama bge-m3        │
 MySQL ──────────►     1024 维 float[]，keep_alive=60m            │
 knowledge_document    ④ 入库：先向量化后写库（不留孤儿）           │
 knowledge_chunk       ⑤ 内存索引写时复制刷新（无锁）              │
                    └──────────────────────────────────────────┘

                    ┌──────────────────────────────────────────┐
 用户 query ───────►│              检索链路（读时）              │
   │                  query 向量化 → 全量内存遍历：               │
   │                  score = 0.75×cosine + 0.25×词面重合         │
   │                  （中文 bigram + 英文词元）→ Top-4           │
   ▼                  └──────────────────────────────────────────┘
 ┌─────────────┐        ┌─────────────────────────────┐
 │ 入口A：注入  │        │ 入口B：工具调用              │
 │ Top-4 拼进   │        │ ReAct agent 调              │
 │ prompt 问    │        │ knowledge_search 工具，      │
 │ DeepSeek     │        │ 结果交给模型组织回答         │
 │ (min-score   │        │                             │
 │  0.25 过滤)  │        │                             │
 └─────────────┘        └─────────────────────────────┘
```

| 组件 | 文件 | 职责 |
|---|---|---|
| 检索核心 | [KnowledgeService.java](../src/main/java/com/example/jarvis/rag/KnowledgeService.java) | 清洗 / 分块 / 内存索引 / 混合评分 / 上下文拼装 |
| 向量客户端 | [OllamaEmbeddingClient.java](../src/main/java/com/example/jarvis/rag/OllamaEmbeddingClient.java) | HTTP 直调 `/api/embed`，批量 + keep_alive，零新增依赖 |
| 工具 | [KnowledgeSearchTools.java](../src/main/java/com/example/jarvis/rag/KnowledgeSearchTools.java) | AgentScope `@Tool`，注册进 Toolkit 供 ReAct 调用 |
| API | [KnowledgeController.java](../src/main/java/com/example/jarvis/controller/KnowledgeController.java) | 文档增删查 + `/search` + `/stats` |
| 注入 | [AgentController.java](../src/main/java/com/example/jarvis/controller/AgentController.java#L116-L139) | 聊天前检索并拼 prompt |
| 存储 | MySQL `jarvis` 库 | `knowledge_document` / `knowledge_chunk`，schema.sql 幂等建表 |
| 种子 | [docs/kb-seeds/](kb-seeds/) | 手册 / 简历 / 调研三篇，数据丢失时可一键重导 |

---

## 二、核心原理

### 2.1 为什么要分块 + 面包屑

embedding 对**长文本会发生语义稀释**（一段 2000 字混了三个主题，向量落在三个主题的"平均位置"）。分块（500 字符）让每块语义聚焦；**面包屑前缀**（"JARVIS 团队手册 > 报销制度"）把文档结构信息注入块文本，向量化和词面匹配都能利用它——这也是"办公环境"块能命中 WiFi 问题的原因之一。

### 2.2 混合评分的两个分量

| 分量 | 擅长 | 失效场景 |
|---|---|---|
| cosine（0.75） | 同义改写："餐补多少钱" ≈ "伙食补助标准" | 精确 token：`JARVIS-Office`、人名会被"平均化"模糊掉 |
| 词面重合（0.25） | 精确词直中：查"JARVIS-Office"直接砸中 | 无 IDF 权重，常见字虚高（见 §三.1） |

实测案例：查 `JARVIS-Office` 时词面分量把该块从向量分 0.5x 推到综合 0.778。

### 2.3 内存索引 + 写时复制

块向量全部加载进 Java 堆，检索时全量遍历。导入/删除后**重建新快照、原子替换引用**，读无锁、实现简单。当前 33 块规模下单次检索全链路（含 query 向量化）约 2 秒，其中向量化占大头。

### 2.4 双入口的消费设计

- **入口 A（注入）**：用户无感，**双阈值**（由评测跑分校准，见 §四基线）：
  - Top1 ≥ `inject-score 0.50`：强相关，自动注入 + 来源卡片；
  - 0.40 ~ 0.50 模糊带：**不注入**——多为"工具意图"等域内噪声（"帮我列出所有任务"能和简历算出 0.47），交给 agent 自主调 `knowledge_search` 工具兜底（生成层评测已验证兜底有效）；
  - < 0.40：干净不注入。单阈值时代（0.25）无关题 100% 误注入；0.40 单阈值挡不住域内噪声。
- **入口 B（工具）**：ReAct agent 判断"这题我需要查资料"才调用 `knowledge_search`，多跳问题（先查 A 再查 B）天然支持。
- `/api/knowledge/search` 接口**永不截断**：分数再低也返回 Top-K，把"没有相关内容"的判断权交给调用方。

---

## 三、当前缺点（按痛感排序）

| # | 缺点 | 具体表现 | 影响 |
|---|---|---|---|
| 1 | 词面打分太糙 | bigram 重合无 IDF 权重，"公司""标准"等常见字虚高，本质是简化版 BM25 | 中 |
| 2 | 无 query 改写 | 多轮对话"它一天多少钱"拿原始 query 检索必偏 | **高**（对话场景） |
| 3 | 回答无强制引用 | 偶尔带 [1] 但无约束，来源不可验证 | 中 |
| 4 | 规模天花板 | 内存暴力 cosine O(N×1024)/查询；10 万块 ≈ 400MB 堆 + 秒级延迟 | 低→中（随规模） |
| 5 | 无重排器 | 向量序直接用，Top-4 混"相关但不对"的块 | 中 |
| 6 | 解析面窄 | 只吃文本/Markdown；PDF/Word/网页/图片进不来 | 中 |
| 7 | 无评估体系 | 效果靠肉眼，没有 recall@k / MRR 回归集 | 中（制约迭代） |
| 8 | 工程杂项 | 同文档重导全量重嵌入（无内容 hash 缓存）；换 embedding 模型新旧向量混存（dim 列存了但无迁移机制）；单用户无文档级权限 | 低 |

> 已解决的历史问题（记录备查）：孤儿文档（先写库后向量化，失败留空壳）→ 已改为先向量化后入库；HTML 噪声污染（简历富文本导出）→ 导入时剥离；大批次撞超时墙（CPU ~6s/条）→ batch-size=4 + keep_alive。

---

## 四、演进路线

### Phase 1：低成本高收益（天级）

| # | 改造 | 解决 | 说明 |
|---|---|---|---|
| 1 | **查询改写** | 缺点2 | 检索前让 DeepSeek 把多轮对话改写成独立完整问句；一次 ~0.5s LLM 调用，性价比最高 |
| 2 | **强制引用** | 缺点3 | prompt 要求标注 `[n]`，前端渲染可点开的来源片段 |
| 3 | **词面升级 BM25** | 缺点1 | 引入 Lucene 内存索引替代 bigram，IDF 加权；混合权重 0.75/0.25 可再用评估集调 |
| 4 | 嵌入缓存 | 缺点8 | 文档内容 hash 相同的块跳过重嵌入 |
| 5 | 评估集 | 缺点7 | ✅ **已落地**（[RagEvalTest](../src/test/java/com/example/jarvis/rag/RagEvalTest.java) + 40 条标注集）。检索层：`RAG_EVAL=true ./mvnw test -Dtest=RagEvalTest`（零 token，需 Ollama）；生成层：`RAG_EVAL=true RAG_EVAL_LLM=true ... -Dtest=RagEvalTest#evalGeneration`（走真实链路，需后端运行）。报告写入 `target/rag-eval-report*.md`，指标不达基线即测试失败 |

### 首份基线（2026-08-30，标注集 32 相关 + 12 无关/工具意图）

| 指标 | 值 | 基线 | 结果 |
|---|---|---|---|
| Recall@4 | 1.000 | ≥ 0.80 | ✓ |
| MRR | 0.839 | ≥ 0.60 | ✓ |
| 分数区分度 | 0.208 | ≥ 0.10 | ✓ |
| 误注入率 | 0/12（min-score 0.25 时为 8/8 ❌ → 校准 0.40；发现"工具意图"域内噪声 0.47 仍穿透 → 双阈值）| ≤ 0.25 | ✓ |
| 注入召回 | 0.813（6 条落模糊带靠工具兜底，明细见报告）| ≥ 0.80 | ✓ |
| 答案要素命中（生成层 14 条真实链路） | 14/14 | ≥ 0.80 | ✓ |
| 引用指向正确 | 12/13 = 0.92 | ≥ 0.80 | ✓ |

> 评测的两个战果：① 量化出 min-score=0.25 门槛形同虚设（离题无关题 Top1 落 0.25~0.35 全部误注入），校准到 0.40；② 用户实测"帮我列出所有任务"仍出现假来源卡片——暴露"工具意图"域内噪声（Top1 0.47 > 0.40），引入双阈值 inject-score=0.50 后清零，模糊带题目由 agent 工具路径兜底（生成层 14/14 验证）。遗留：模型偶发编造超出注入数量的引用编号，后续在 prompt 中强调"只允许引用给出的编号"。

建议起步顺序：**1 + 2 一天内可完成、体感提升最明显**。

### Phase 2：结构升级（周级）

| # | 改造 | 解决 | 说明 |
|---|---|---|---|
| 6 | 重排器 | 缺点5 | 向量+BM25 召回 Top-50 → `bge-reranker-v2-m3` 精排取 Top-4（可继续跑 Ollama） |
| 7 | 向量存储外置 | 缺点4 | MySQL 管元数据 + Qdrant（HNSW，p99 ~8ms）管向量；或迁 PostgreSQL+pgvector 保单库 |
| 8 | 文档解析 | 缺点6 | Apache Tika/PDFBox 接 PDF/Word；表格按结构切块 |
| 9 | Parent-Child 检索 | 效果 | 小块（精准匹配）命中后返回父章节（完整上下文）——"检索要细、喂模型要粗" |
| 10 | 元数据过滤 | — | 按文档/标签/时间过滤检索范围；顺带补文档级权限 |

### Phase 3：形态跃迁（对齐 llm-wiki-research.md）

| 路线 | 工期 | 内容 |
|---|---|---|
| **M1：接入 llm-wiki** | 2~3 天 | geronimo-iia/llm-wiki（Rust 单二进制 + 23 个 MCP tools），导入时让 LLM 把文档"编译"成持久 Wiki 页面——知识从"切块 soup"变成"可累积、可引用的资产"；AgentScope 已跑通 MCP，接入零改动 |
| **M2：企业级混合栈** | 1~2 周 | Qdrant + BM25 + Rerank 的完整形态（即 Phase 2 的 6/7 落地版） |
| **M3：原生知识编译引擎** | 4~6 周 | 对标 RAGFlow v0.27 的 7 种 Artifact（Wiki/Graph/Tree/MindMap/Timeline...），Java 自研 WikiCompiler |
| 更远 | — | GraphRAG（实体关系图谱补向量）；Agentic RAG（多跳分解、自主决定检索）；embedding 领域微调；多模态（图/截图入库） |

### 演进原则

1. **先评估后优化**：没有评估集（Phase1-5）之前，任何检索改动都靠肉眼，改坏了都不知道
2. **每次只动一层**：分块、向量、评分、重排是独立层，逐层替换、逐层用评估集验收
3. **服务端无状态优先**：索引可随时从 MySQL 全量重建（现在的写时复制设计已保证），这让后面换 Qdrant / 加缓存都不会伤筋动骨
