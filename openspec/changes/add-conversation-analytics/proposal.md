# Proposal: 对话分析仪表盘

## 动机

JARVIS 已有 `agent_trace` 表积累推理数据（56 条 trace，覆盖 7 种工具调用），但现有 UI 只支持按会话查看推理轨迹，缺少**跨会话的聚合分析视图**。Admin 无法一眼看到"Agent 最常用哪个工具""哪种技能回答质量最差""平均 ReAct 步数趋势如何"。

同时 `eval_candidate` 表（Chat 页面的 👎 反馈）也缺少聚合视图——这些候选是 Agent 幻觉率/不满意的直接信号，但没有被纳入运营分析。

## 变更范围

### 新增
- **后端**：`AnalyticsController` + `AnalyticsMapper` + `AnalyticsService`，提供 4 个聚合查询 API
- **前端**：新 `ConversationAnalytics.jsx` 页面 + 菜单项"对话分析"，含 4 个可视化模块

### 修改
- `AdminLayout.jsx`：菜单新增"对话分析"项
- `App.jsx`：路由新增 `/analytics` 路径
- `api/client.js`：新增 `analyticsApi` 接口

### 不修改
- 现有 `AgentTraceController` 和 `AgentTraces.jsx`（会话级 trace 详情页面保持不变）
- 数据库 schema（利用现有 `agent_trace` + `eval_candidate` + `message` + `conversation` 表）

## 影响分析

| 维度 | 影响 |
|------|------|
| 数据库 | 无变更，利用现有表和索引 |
| 后端 API | 新增 4 个只读 GET 接口，JWT 鉴权 + admin 角色限制 |
| 前端 | 新增页面和菜单项，不影响现有功能 |
| 性能 | 聚合查询走索引，单次 < 100ms；前端按需加载，首屏只拉 summary |
