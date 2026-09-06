# Proposal: Agent 推理调试器

## 动机

JARVIS 已有 `agent_trace` 表记录完整 ReAct 路径（reasoning → tool_call → tool_result），但现有 `AgentTraces.jsx` 只做只读时间线展示。当 Agent 回答错误时，无法精确定位"在哪一步走偏"——缺少 IDE 风格的步骤导航、断点检查、上下文重建和路径对比能力。

## 变更范围

### 新增
- **数据库**：`debug_breakpoint` 表（Flyway V8）— 存储用户断点标记
- **后端**：
  - `DebugService` — 步骤导航、上下文重建、断点管理、路径 diff
  - `DebugController` — 4 个 API 端点
- **前端**：`AgentDebugger.jsx` — IDE 风格调试器界面

### 修改
- `AdminLayout.jsx` — 菜单新增"推理调试"项
- `App.jsx` — 路由新增 `/debugger`

### 不修改
- `AgentTrace` 模型和 Mapper — 复用现有查询
- `AgentController` — trace 收集逻辑不变
- `AgentTraces.jsx` — 保留现有轨迹页面，调试器是独立页面

## 影响分析

| 维度 | 影响 |
|------|------|
| 数据库 | 新增 debug_breakpoint 表（V8 迁移） |
| 后端 | 新增 2 个类 |
| 前端 | 新增 1 个页面，修改 2 个文件 |
| 性能 | 上下文重建从现有 trace + message 数据组装，无额外存储 |
| LLM 成本 | 零 — 纯数据查询和组装，不调用 LLM |
