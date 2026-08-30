# LLM Wiki 技术方案调研（JARVIS 集成用）

> 调研日期：2026-08-29
> 适用范围：规划 JARVIS 项目如何从"简易 RAG 问答"升级为"LLM 驱动、持久化、可积累、可协作的结构化知识库（LLM Wiki / DKR）"
> 参考与引用见文末 §8。

---

## 1. 什么是 LLM Wiki / DKR

### 1.1 来源

LLM Wiki 这个名字最早由 Andrej Karpathy 在 2025 年末发布的一份 Gist
([`karpathy/442a6bf555914893e9891c11519de94f`](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f))
中提出，他本人的实践方式是：**不要在每次回答时重新做一遍 RAG，而是让 LLM 在"文档导入时"一次性处理并写入持久化的 Wiki 页面，知识随导入复合累积**。

Karpathy 把这种模式命名为 **DKR（Dynamic Knowledge Repository，动态知识库）**，与传统 RAG 的核心差异如下——

| 维度 | 传统 RAG（JARVIS 当前形态）| LLM Wiki / DKR |
|---|---|---|
| **知识什么时候被"整理"** | **查询时**（每次提问：分块检索 → 拼 prompt → LLM 生成）| **导入时**（一次处理 → 写入结构化 Wiki 页面 / 知识图谱 / 树 / 思维导图）|
| **同样的问题问两次** | LLM 每次从头推理，答案可能漂移，也不记得上次结论 | Wiki 已经把要点固化了，回答会引用同一份持久化页面 |
| **跨文档引用、概念关联** | 靠分块拼接，链接是"碰巧的" | LLM 在编译时主动建立 Concept Page + 双向链接 + 关系边（图）|
| **审计 / 追溯** | 最多做到"引用某文档第几段" | 每一页有 Git 提交历史、版本 diff、置信度、维护人、状态标签 |
| **数据归属** | 向量库 / 云服务 | 你的 Markdown 文件 / Git 仓库，LLM 外部化，可随时切模型 |
| **工程复杂度** | 低，几天能搭 MVP | 高，但一旦跑起来知识会越用越准 |

一句话：**传统 RAG 每次都在开卷考试找原文；LLM Wiki 提前雇助教把原文整理成一本带目录、交叉引用、大纲、思维导图的精修课本。**

### 1.2 两种衍生解读

社区对 "LLM Wiki" 实践出了两条路线：

- **路线 A（交互式维基，Human-in-the-loop）**：Web UI 让大家上传 → 点 Compile → 生成 Wiki 页面 → 人审核/编辑 → 再写回。代表：`yuan-phd/llm-wiki`（Python Flask，5001 Web UI）、`nashsu/llm_wiki`（Tauri 桌面端）。
- **路线 B（无头引擎，Agent 通过 MCP 直接读写）**：把 Wiki 做成一套 23 个 MCP 工具（`wiki_search` / `wiki_content_read` / `wiki_content_new` / `wiki_graph` / `wiki_lint`…），Agent 自己"维护"知识库。代表：`geronimo-iia/llm-wiki`（Rust 单二进制，Git-backed + tantivy 索引 + 类型化 frontmatter JSON Schema）。
- **路线 C（知识编译引擎，多形态 Artifact）**：在 RAG 架构之上加一层 Compilation Operator，把原始文档编译为 Wiki / Graph / Tree / Page Index / Mind Map / Timeline / Skills 七类制品。代表：RAGFlow v0.27（2026-08-24 发布，官方称这是其最后一个 Python 主版本，后续将重构为"智能 Agent 数据底座"）。

---

## 2. 主流开源实现速览（5 选 1 对照）

### 2.1 总览对比表

| 项目 | 开发语言 | 协议 | 形态 | 核心亮点 | 能否直接嵌 JARVIS | 社区热度 |
|---|---|---|---|---|---|---|
| **karpathy gist 原版** | Python | — | 概念原型 | 最小化 DKR 定义，无生产能力 | ✗（纯概念）| 超高影响力 |
| **yuan-phd/llm-wiki** | Python (Flask) | MIT | Web + Obsidian vault | 拖拽上传 docx/pdf → Compile → Wiki 浏览/问/图/保存答回写 Wiki | ✗ 独立服务，非 Java 生态 | 小（3 commits，早期）|
| **nashsu/llm_wiki** | Tauri(Rust)+Svelte+Python | GPLv3 | 跨端桌面应用+MCP server | 打包成 App；内置 mcp-server，能和 AgentScope 直接相连 | ⚠ 独立应用，MCP 侧可用 | 中（533 commits，v0.4.20）|
| **geronimo-iia/llm-wiki** | Rust | MIT/Apache | 单二进制 + 23 MCP tools + Git + tantivy | **最"Headless Agent 友好"**：JSON Schema 校验 frontmatter、Git 每次提交即保存版本、Graph 实时建边、 tantivy 全文+分面搜索；安装一行 curl | ✅ **可作为独立服务通过 MCP 接入 AgentScope** | 中偏新 |
| **RAGFlow v0.27** | Python (Flask→异步)+C++ Infinity 引擎 | Apache | Docker 一键起全栈 | **最生产级**：7 种 Knowledge Artifact（Wiki/Graph/Tree/PageIndex/MindMap/Timeline/ToSkill）+ Notion/Confluence/GoogleDrive 同步 + Docling 解析 + GraphRAG + Agent 画布；但资源要求 16GB 起步 | ✗ 太重，Java 项目嵌不动 | **超高**（infiniiflow/ragflow）|

### 2.2 geronimo-iia/llm-wiki（重点关注，Agent 友好度最高）

这是和 JARVIS（Spring Boot + AgentScope + MCP 已就绪）**架构结合成本最低**的一款。关键特征：

```
部署（一行）：
  curl -fsSL https://raw.githubusercontent.com/geronimo-iia/llm-wiki/main/install.sh | bash
  llm-wiki spaces create ~/wikis/jarvis --name jarvis
  llm-wiki serve                                 ← 启动 MCP server（stdio/sse/tcp）
```

数据模型：**每一个 Wiki 页面就是一个带 frontmatter 的 Markdown 文件**，存 Git 仓库：

```markdown
---
type: concept
title: 向量数据库
status: active
confidence: 0.92
tags: [rag, storage]
sources:
  - sources/rag-enterprise-arch-2026.md
  - sources/rfc-qdrant-vs-milvus.md
related:
  - concepts/embedding-model
  - patterns/hybrid-search
created: 2026-08-20
updated: 2026-08-28
---

向量数据库是专门用于存储和检索高维向量...
```

然后暴露 23 个 MCP 工具，**正好能被 AgentScope 的 McpClientBuilder stdio/SSE 模式直接注册成工具**。
本项目之前已经按这个套路跑通 filesystem MCP server，所以接入它几乎 0 改动：只要在 `application.properties` 加一个 MCP server 条目指向 `llm-wiki serve`，ReActAgent 立刻就获得了 `wiki_search`、`wiki_content_read`、`wiki_content_new`、`wiki_graph`、`wiki_lint` 等能力。

### 2.3 RAGFlow v0.27 Knowledge Compilation Engine（生产级上限参照）

2026-08-24 发布。把"知识编译"这个 Karpathy 的口头概念工程化成了 7 种 Artifact：

| Artifact | 说明 | 适合的检索场景 |
|---|---|---|
| **Wiki** | 把碎片化文档拼成互链的 Wiki 页面实体/概念导航视图 + 图视图 | 浏览式问答、新人入职培训知识库 |
| **Graph** | 抽取实体/关系→交互式知识图谱 | 跨文档溯源、"A 和 B 有什么关系"类问题 |
| **Tree** | 语义层级（主题→细节），保留原文顺序 | 文档超大时的分层下钻 |
| **Page Index** | 保留章节层级的目录索引 | 手册/合同/规范查具体条款 |
| **Mind Map** | 围绕中心主题分主题发散思维导图 | 快速把握文档全景 |
| **Timeline** | 抽取时间+事件，按时间线排列 | 公司里程碑/政策演进/历史类 |
| **To Skill** | 把知识进一步打包成 Skill 文件（name/层级/概览）| 给其他 Agent 复用知识导航 |

输入→输出的编译链本身也是**一个可编排的 Compilation Operator**（选 LLM、选 Artifact、设全局 prompt），和 RAGFlow 已有的 Agent Canvas 是同一块画布。
这点对我们的启发很大：**Karpathy 的 "Compile" 不应该是一个函数调用，而应该是一个独立于 RAG 检索链的完整 Operator**。

---

## 3. 企业级 LLM Wiki 六层参考架构

综合《企业级 RAG 知识库架构》与《私有化 AI 知识库六层架构》，一份 LLM Wiki 的工程化架构应当是（相比传统 RAG 在处理层多了"Wiki 编译/图谱"，存储层多了"页面 Git 库"）：

```
┌─────────────────────────────────────────────────────────────────────┐
│ 第六层：应用层（Wiki Browse / Ask / Graph View / Maintain / Share）    │
├─────────────────────────────────────────────────────────────────────┤
│ 第五层：推理层（LLM Chat / Agent / Prompt模板 / 引用拼装 / 缓存）       │
├─────────────────────────────────────────────────────────────────────┤
│ 第四层：检索层  向量检索 + BM25 + Rerank + 元数据过滤 + RRF 融合         │
├─────────────────────────────────────────────────────────────────────┤
│ 第三层：索引层  向量索引(ANN HNSW) + 全文索引 + 知识图谱 + Git Blame    │
├─────────────────────────────────────────────────────────────────────┤
│ 第二层：存储层  向量库 + 对象存 + 关系库 + Markdown Git 仓              │
├─────────────────────────────────────────────────────────────────────┤
│ 第一层：采集层  文档解析(PDF/Docx/MD/HTML) + 数据源同步(Notion/Confluence)│
└─────────────────────────────────────────────────────────────────────┘
            贯穿：物理级多租户隔离 · 权限审计 · 版本回溯 · 指标观测
```

### 3.1 每一层的关键工程化问题

- **采集层**：企业 80% 是 PDF，PDF 解析占效果的 70%。主流方案 Docling / unstructured，要区分扫描型 vs 文本型、表格语义化、图文对齐、页眉页脚去重。
- **存储层**：
  - 向量库选型：100万条以下 Qdrant 或 pgvector；千万~亿 Qdrant/Milvus；**JARVIS 当前的 H2 内存向量在文档>5000 块需要换。**
  - Markdown Git 仓建议 `wiki/` 目录独立 Git 仓库，每次 Compile 提交一个 commit（geronimo-iia 的做法）。
- **索引层**：向量用 HNSW（O(logN)）+ 全文用 tantivy 或 Elasticsearch/Lucene。
- **检索层**：企业级必备三件套——混合检索（向量+BM25 RRF 融合）、Rerank（Cross-Encoder，Top-50→Top-5）、元数据过滤（产品/团队/文档类型/时间窗口）。
- **推理层**：Wiki 编译时的 LLM prompt 模板要细分成 "概念抽取、页面摘要、矛盾检测、frontmatter 规范化" 等独立子任务，不要一个大 prompt 一次做完。
- **应用层**：四大视图——Browse Wiki（人看）/ Ask（问答）/ Graph（图探索）/ Maintain（编译+Lint+版本）。

---

## 4. 技术细节四连问（RAG→Wiki 升级必须回答）

### 4.1 分块与切分策略怎么选？

企业数据实测（同一 5 万字制度文档）[[2]](https://juejin.cn/post/7663313773420183590)：

| 策略 | 原理 | 检索准确率 | 回答准确率 |
|---|---|---|---|
| 固定 512 token | 按 token 硬切 | 62% | 55% |
| 语义分块 | 句子/段落自然边界 | 71% | 68% |
| **递归分块** RecursiveCharacterTextSplitter（先段落→句子→标点）| 大小在 256~512 自适应 | **79%** | **76%** |

→ JARVIS 当前 `max-chars=800 / hard-limit=1200` 属于"语义分块的粗糙版"，升级 Wiki 时应改为 **Markdown 标题感知 + 递归切分**：
  - 切分器先按 `#/##/###` 切成逻辑段；
  - 每段内再按段落→句子→标点递归，压到 token 窗口上限（通常 256~512 tokens）。

### 4.2 向量库 & 检索升级路线

> 规模 < 100 万向量的中小企业/团队知识库最常见。

| JARVIS 当前 H2 内存向量的**瓶颈** | 建议替换方案 | 说明 |
|---|---|---|
| 无 ANN 索引，O(N) 全量 cosine | **Qdrant**（HNSW + 混合检索内置，Rust 写） | 单容器启动即可，支持 10M+ 规模，延迟 p99 8ms，Docker 一行 |
| 没做混合检索 | 在 Qdrant 里做 BM25 payload filter + 向量融合 / RRF | 对产品型号、人名等术语型问题召回提升明显 |
| 没有 Rerank | Ollama 部署 `bge-reranker-v2-m3` 或调用 DashScope rerank | Top50→Top5，效果一般 +15%~30% |
| 没做多租户 / 元数据过滤 | Qdrant 的 `filter` clause，或 pgvector SQL where | 按团队/项目/文档类型过滤 |

### 4.3 "Wiki 编译"到底要让 LLM 做哪些事？（Prompt 清单）

把 Karpathy 的 "Compile" 拆成可独立 prompt 的子任务：

```
T1 页面分类与 frontmatter 生成（§2.2 的 YAML 模板 + JSON Schema 校验）
T2 概念抽取：给文档里出现的名词/概念打标，输出到 concepts/ 页面草稿
T3 关系抽取：实体-关系-实体三元组，写 Graph 边
T4 摘要：每篇源文档生成一段 100~200 字摘要，挂 sources/ 页
T5 交叉引用：在概念页面插入 [[内部链接]]，检查 orphan pages
T6 矛盾检测：同一概念在多份文档里结论不一致的，生成 contradiction note
T7 Lint：frontmatter 是否合法、死链、过时页面、孤儿页面、置信度<0.5 提示
T8 版本摘要：每次 git commit 前生成一页 "本次变更"
```

这些子任务每个都可以单独选模型（比如 T6 选推理强的 DeepSeek-V4，T1/T4 选便宜的 deepseek-chat），
和 RAGFlow v0.27 的 "Compilation Operator 选 LLM + 选 Artifact 类型" 思路完全一致。

### 4.4 人在哪个位置参与？（协作流建议）

一个最小可用的团队协作流程：

```
 上传文档
    │
    ▼
 Compile（LLM 后台任务，异步进行，有进度条）
    │
    ▼
 生成的 Wiki / Graph / PageIndex → 放在「待审核」视图
    │
  ┌─┴──── 负责人审核：修正 frontmatter / 修改正文 / 删除幻觉页面 ───┐
  │                                                                    │
  ▼                                                                    ▼
 Commit 到 Git 仓库（永久保存，版本可追溯）             打回，重新 Compile 对应文档
    │
    ▼
 Lint（T7 自动跑，出报告）→ 团队每周例会点"Fix all"
```

---

## 5. 与 JARVIS 现状的差距 & 增量升级路线

### 5.1 当前 RAG 能力盘点（已具备）

| 模块 | 实现位置 | 现状 |
|---|---|---|
| 文本导入（MD/TXT 粘贴/前端读文件提交）| `KnowledgeController` + `Knowledge.jsx` | ✅ |
| 段落感知分块（char 800/1200）| `KnowledgeService.splitIntoChunks()` | ✅（可升级 §4.1）|
| 向量 Embedding（Ollama `/api/embed` bge-m3，1024d）| `OllamaEmbeddingClient` | ✅ |
| 向量存储（H2 `knowledge_chunk.embedding` JSON blob）| `KnowledgeMapper.xml` | ⚠ 规模瓶颈，§4.2 |
| 内存全量 Cosine TopK（写时复制快照）| `KnowledgeService.search()` | ⚠ O(N)，可换 Qdrant |
| 双入口：聊天自动注入 + knowledgeSearch 工具 | `AgentController` + `KnowledgeSearchTools` | ✅ |
| MCP 工具注册管道（stdio/SSE/streamableHttp）| `AgentScopeConfig` + McpController + application.properties | ✅（接 geronimo/llm-wiki 零改动）|
| Docker Compose 一键部署（前后端 + Ollama）| `docker-compose.yml` / `deploy.sh` | ✅（加新服务只要再加段 compose）|

### 5.2 三条升级路线（从低投入到高投入）

#### 路线 1：M1 — "把 geronimo/llm-wiki 作为独立 MCP 服务接入"（最低成本，2~3 天）

```
加一个服务 → 获得 Wiki 浏览/搜索/图/版本/双向链接 → 不破坏现有 RAG
```

**做什么**：
1. Dockerfile 或 compose 加一个 `llm-wiki` 服务：单二进制，MCP stdio 或 SSE 暴露。
2. `application.properties` 加一行 `agentscope.mcp.servers.llm-wiki.type=sse` 指向它。
3. Agent 立刻能拿到 23 个 MCP 工具。
4. 前端再加两个菜单项：Wiki Browse（拉 pages 列表+详情）+ Wiki Graph（Mermaid/Force 图）。

**代价**：后端 RAG 存储链路和 Wiki 存储是两套（现 H2 向量 + 新 Git Wiki）；短期可能双轨。

#### 路线 2：M2 — "升级现有 RAG 管线到企业级"（中成本，1~2 周）

```
保持 Java 技术栈，把当前简易 RAG 做成"生产可用"的知识库
```

**做什么**：
1. **向量库**：引入 Qdrant Docker 服务，替换 H2 JSON blob 方案（写一个 `QdrantVectorStore`）。
2. **混合检索**：在 H2 `knowledge_chunk(content)` 上建全文索引，或用 Qdrant payload 的全文搜索，用 RRF 融合向量分数。
3. **Rerank**：再启一个 Ollama 模型 bge-reranker-v2-m3，Top50 精排到 Top5。
4. **切分**：升级为 Markdown 标题感知递归切分（§4.1）。
5. **文档解析**：加 Docling 或 unstructured（Docker 化的微服务）支持 PDF/Word，前端传 multipart 文件。

**代价**：新增 1 个 Qdrant + 1 个 doc-parser 容器，工程改动中等，但完全在 Java 技术栈掌控内。

#### 路线 3：M3 — "实现原生 Java 版 Knowledge Compilation Engine"（高投入，4~6 周）

```
对标 RAGFlow v0.27 的 7 种 Artifact，在现有 Spring Boot 里做 Java 版编译引擎
```

**做什么**：
1. `WikiCompiler` 接口 + 多实现（WikiCompiler / GraphCompiler / TreeCompiler / PageIndexCompiler …）。
2. 在 AgentScope 里选 DeepSeek LLM 去跑 T1~T8 八个子任务（§4.3），把输出写回独立的数据模型：
   ```
   wiki_page(id, slug, type, status, confidence, frontmatter, body, git_sha, created_at)
   wiki_edge(from, to, edge_type, source_document_id)
   wiki_commit(sha, message, diff, operator)
   ```
3. 前端：Browse / Ask / Graph / Maintain 四大视图，Maintain 页是编译画布。
4. 和现有双入口融合：Ask 回答时不仅检索 chunk，也把命中的 Wiki Concept 页面一起拼上下文（质量显著提升）。

**代价**：自研工作量最大；但回报是——和 DSH（DeepSeek Harness Java 版）的 Roadmap M3/M4/M5 对齐，不再依赖外部 Python/Rust 服务。

---

## 6. 风险与边界

| 风险 | 发生概率 | 影响 | 缓解 |
|---|---|---|---|
| LLM 编译时产生幻觉：概念页编得很像真事但文档里没有 | 中 | 用户信了假 Wiki | 强制每个 Concept Page 必须带至少 1 条 sources 边；Lint 报告对"无来源页面"红标 + 置信度 <0.7 隐藏 |
| PDF 解析质量差 → 编译出的 Wiki 错误率高 | 高 | 全链路效果下降 30%+ | 只从 MD/TXT 起步（团队内部资料先 MD 化），PDF 留到 M2 加 Docling 后再开放 |
| 编译成本：一份长文档 Compile 要跑几百次 LLM 调用，token 费贵 | 中 | 成本失控 | 增量编译（hash 比较只重编变化了的源文档）；小模型做 frontmatter/关系，大模型只做摘要和矛盾检测 |
| 图谱/图可视化效果差（前端工程量高）| 中 | UI 体验不佳 | M1/M2 先不开 Graph 视图，用文字列表+锚点；M3 再做 Force 图 |
| Qdrant / Rerank 资源占用增加 | 中 | 部署最低 4C/8G 升到 8C/16G | 在 compose 里做 profile，默认 M2 开关；H2 向量作为 fallback |

---

## 7. 推荐建议

综合当前 JARVIS 的技术栈（Java Spring Boot + AgentScope + MCP 已跑通 + Docker Compose 已就绪 + 简易 RAG 已上线），
**建议立即做路线 1（M1：接 geronimo-iia/llm-wiki 作为 MCP 服务）+ 并行做路线 2 的前两步（换 Qdrant + 混合检索）**，M3 放在 DSH 里程碑 M3 之后作为独立大版本。

原因：
1. geronimo/llm-wiki 是现成的无头 Wiki 引擎，和 AgentScope 通过已存在的 MCP 管道**零改动对接**，2~3 天就能把"编译 Wiki、Agent 自维护 Wiki"这条最小闭环跑通。
2. Qdrant 也是 Docker 一行，且替换现有 H2 向量只需改 KnowledgeService，风险低、收益在"搜索质量/规模"立竿见影。
3. 自研 Knowledge Compilation Engine（M3）工作量大，等 DSH 主架构稳定后再做更合适。

最终落地后的目标形态：

```
JARVIS 用户：上传团队 wiki / 会议纪要 / 设计文档
              │
              ▼
         Compile 按钮 → LLM 调用（T1~T8）→ 写 Git-backed Markdown Wiki（MCP：llm-wiki）
              │                                            │
              ▼                                            ▼
     Java 侧 Qdrant 向量混合检索+Rerank          Agent 自维护/自引用 wiki 工具
              │                                            │
              └─────────── 拼入对话 prompt ───────────────┘
                                │
                                ▼
                         带引用 + 版本的 Wiki 风格回答
```

---

## 8. 参考与引用

[1] 开源 RAG 知识库框架盘点（15 大主流方案对比与选型指南）：
    https://zeeklog.com/kai-yuan-ragzhi-shi-ku-kuang-jia-pan-dian-15da-zhu-liu-fang-an-dui-bi-yu-xuan-xing-zhi-nan-xiang-xi-da-mo-xing-ru-men-dao-jing-tong-shou-cang-zhe-pian-jiu-zu-gou-liao-2

[2] 企业级 RAG 知识库架构设计：从文档处理到检索优化的完整方案（掘金 · 杰哥AI 2026-07）：
    https://juejin.cn/post/7663313773420183590

[3] RAGFlow v0.27 — Knowledge Compilation and Agentic Retrieval（官方博文 2026-08-24）：
    https://ragflow.io/blog/ragflow-0.27-knowledge-compilation-and-agentic-retrieval

[4] 私有化企业 AI 知识库六层架构（腾讯云开发者 2026-07）：
    https://developer.cloud.tencent.cn/article/2714871

[5] geronimo-iia/llm-wiki — headless wiki engine for agents（23 MCP tools, Rust, Git-backed）：
    https://github.com/geronimo-iia/llm-wiki

[6] Andrej Karpathy LLM Wiki gist（DKR 概念源头）：
    https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f

[7] nashsu/llm_wiki — 跨端桌面版 LLM Wiki + MCP server：
    https://github.com/nashsu/llm_wiki

[8] yuan-phd/llm-wiki — Web UI 版（Python Flask，Obsidian compatible）：
    https://github.com/yuan-phd/llm-wiki

[9] 从 0 到 1 搭建企业级 RAG 知识库（基于 RAGFlow 框架，CSDN 2026-06）：
    https://blog.csdn.net/ycy317/article/details/162016879
