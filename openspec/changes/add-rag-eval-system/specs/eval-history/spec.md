## Purpose

评测结果的生命周期管理：让每一次评测运行都留下机器可读、git 可追溯的档案，并能量化对比相邻两次运行的指标变化，为 BM25、重排器、向量库迁移等演进改造提供趋势决策依据。

## ADDED Requirements

### Requirement: 每次评测运行 MUST 生成归档

评测运行完成后，系统 SHALL 在 git 追踪目录 `docs/eval/history/` 下生成一次运行的归档，目录名格式 `<UTC日期>-<git短哈希>-<运行序号>`。归档 MUST 包含：`summary.json`（机器可读）、`report.md`（人可读，含检索层与生成层完整明细）。`summary.json` MUST 包含：运行时间戳、git commit 短哈希、全量指标值、影响指标的配置快照（向量/词面权重、min-score、inject-score、分块参数、embedding 模型名）、标注集用例数。

#### Scenario: 完成一次检索层评测

- **WHEN** 检索层评测运行结束（无论断言通过与否）
- **THEN** `docs/eval/history/` 下出现含 `summary.json` 与 `report.md` 的新归档目录
- **AND** `summary.json` 中的 config 快照与当时 `application.properties` 中 RAG 相关配置一致

#### Scenario: 评测中断

- **WHEN** 评测在检索层中途失败（如 Ollama 不可用）
- **THEN** 不产生该次运行的归档目录
- **AND** 控制台输出失败原因

### Requirement: 相邻运行趋势对比

评测运行器 SHALL 在运行结束时读取最近的既有归档（若有），输出相邻两次运行的指标 diff 表：每项指标标注 `↑变好 / ↓变差 / →持平`（按指标方向语义判断），变化幅度超过 ±0.03 的指标 MUST 显著标记。对比 SHALL 同时写入控制台输出与归档的 `report.md`。首次运行（无历史归档）SHALL 标注"基线首次建立"。

#### Scenario: 权重调整后对比

- **WHEN** 开发者将词面权重从 0.25 调到 0.30 后运行评测，且 MRR 从 0.839 降至 0.800
- **THEN** 报告输出 diff 表，MRR 行标记 `↓变差`
- **AND** summary.json 中两份配置快照可对照出权重差异

#### Scenario: 首次运行

- **WHEN** `docs/eval/history/` 目录为空时运行评测
- **THEN** 报告标注"基线首次建立"，不输出 diff 表
