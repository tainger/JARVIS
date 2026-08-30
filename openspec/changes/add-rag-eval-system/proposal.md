## Why

现有 RAG 评测仅为"最小闭环"（44 条标注集 + JUnit 跑分器 + 基线断言）：报告写入 `target/` 随构建丢弃，无历史趋势；全靠手动触发；标注集静态、与真实使用脱节；指标缺忠实度与时延维度；结果只有控制台和 MD 表格，不可浏览。它只能拦单次回归，称不上"评测系统"，无法支撑 RAG 的持续演进（BM25、重排器、Qdrant 迁移等 Phase 2 改造都缺乏量化决策依据）。

## What Changes

- **评测历史与趋势**：每次运行归档机器可读结果（metrics + 配置快照 + git commit）到 git 追踪目录，并提供与上一次运行的 diff 对比输出
- **评测触发机制**：提供一键命令、可安装的 git pre-push 钩子（路径感知：仅 rag 相关代码变更时运行检索层评测）、GitHub Actions workflow（仓库远端为 github.com/tainger/JARVIS）
- **case 供给流水线**：新增候选池（MySQL 表 + 后端 API），前端聊天 👎 按钮一键提交 bad case，提供候选转正/丢弃的管理接口，转正即写入标注集文件
- **指标深度**：生成层新增 LLM-as-judge 忠实度评分（答案是否仅由检索片段支撑）、检索/端到端时延统计（p50/p95）、按文档维度分层报告
- **评测可视化**：前端新增"评测"页面——最新指标卡片、历史趋势图、case 明细表、候选池_triage 界面；后端新增只读 API

## Capabilities

### New Capabilities

- `eval-history`: 评测结果归档（机器可读 summary + 配置快照）与跨运行趋势对比
- `eval-trigger`: 评测的一键命令、pre-push 钩子与 CI 工作流
- `eval-case-pipeline`: bad case 候选池的提交、triage 与转正入集流程
- `eval-metric-depth`: 忠实度 judge、时延统计与文档维度分层指标
- `eval-report-ui`: 评测中心的 Web 可视化（指标卡、趋势、明细、候选池管理）

### Modified Capabilities

<!-- 无：现有检索/注入行为不变，本变更只外挂评测能力 -->

## Impact

- **代码**：`src/test/java/.../RagEvalTest.java`（重构为可复用的评测运行内核）、新增 `EvalReportService`/`EvalCandidateController`、`schema.sql` 新增 `eval_candidate` 表、`RagEvalTest` 输出改写；前端新增 `web/src/pages/EvalCenter.jsx` 与 client.js API；Chat.jsx 增加 👎 入口
- **构建/CI**：新增 `.github/workflows/eval.yml`、`deploy/hooks/install.sh`（pre-push 安装脚本）
- **依赖**：前端趋势图引入 `@ant-design/charts`（或 recharts）；后端零新增依赖
- **数据**：MySQL 新表 `eval_candidate`；git 内新增 `docs/eval/history/` 归档目录
- **不受影响**：检索链路、注入逻辑、chat API 行为均不变
