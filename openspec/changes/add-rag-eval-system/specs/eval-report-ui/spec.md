## Purpose

让评测结果可浏览、可回溯、可操作：前端新增评测中心页面，聚合最新指标、历史趋势、用例明细与候选池 triage，使"跑评测→看结果→沉淀 case"不需要离开浏览器。

## ADDED Requirements

### Requirement: 评测中心页面

前端 SHALL 提供 `/eval` 路由页面（登录态、沿用管理布局与黏土视觉风格），包含：最新运行指标卡片（与基线对比的达标状态）、历史趋势图（按时间轴展示 Recall@4 / MRR / 误注入率曲线）、用例明细表（可按类型/结果筛选、展开查看失败用例的 Top-K 命中详情）。无任何归档时页面 SHALL 展示空状态引导（如何跑第一次评测）。

#### Scenario: 查看趋势

- **WHEN** 已有 ≥2 次归档运行时打开评测中心
- **THEN** 趋势图展示各次运行的指标曲线，可定位到具体某次运行查看 summary

#### Scenario: 无历史数据

- **WHEN** 归档目录为空时打开评测中心
- **THEN** 显示空状态与首次评测的执行指引

### Requirement: 评测历史只读 API

后端 SHALL 提供 `GET /api/knowledge/eval/history`（归档运行列表，含各次 summary 摘要）与 `GET /api/knowledge/eval/history/{runId}`（单次完整内容 + 与前一次的 diff）。数据源为归档目录文件；目录不存在或为空 MUST 返回空列表而非 500。接口 SHALL 纳入登录鉴权。

#### Scenario: 未初始化时请求历史

- **WHEN** 归档目录不存在时调用历史 API
- **THEN** 返回 200 与空数组

### Requirement: 候选池 triage 界面

评测中心 SHALL 包含候选池管理区块：待处理候选列表（问题、备注、来源、提交时间），每条提供"转正"（表单补全 type/expectDoc/关键词）与"丢弃"操作，调用候选池 API 并实时刷新列表。

#### Scenario: 转正一条候选

- **WHEN** 用户在 triage 界面为候选补全标注并点击转正
- **THEN** 列表中该候选移出待处理区
- **AND** 标注集文件新增对应 case（下一次评测生效）
