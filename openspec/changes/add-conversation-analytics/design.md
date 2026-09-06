# Design: 对话分析仪表盘

## 1. 数据源分析

### 1.1 agent_trace 表（核心数据源）

已有字段可支撑以下指标：

| 指标 | 查询方式 |
|------|----------|
| 工具调用频率 | `SELECT tool_name, COUNT(*) FROM agent_trace WHERE step_type='tool_call' GROUP BY tool_name` |
| 平均 ReAct 步数 | `SELECT conversation_id, COUNT(*) / COUNT(DISTINCT message_id) FROM agent_trace GROUP BY conversation_id` |
| 工具结果截断率 | `SELECT SUM(is_truncated=1) / COUNT(*) FROM agent_trace WHERE step_type='tool_result'` |
| 工具执行耗时分布 | `SELECT tool_name, AVG(duration_ms), MAX(duration_ms) FROM agent_trace WHERE step_type='tool_result' GROUP BY tool_name` |
| 日活趋势 | `SELECT DATE(created_at), COUNT(DISTINCT conversation_id) FROM agent_trace GROUP BY DATE(created_at)` |

### 1.2 eval_candidate 表（幻觉率代理指标）

Chat 页面的 👎 反馈流入 `eval_candidate` 表（`source='chat'`）。每条候选代表一次"Agent 回答不满意"，是幻觉率的最佳代理指标。

```
幻觉率 = eval_candidate(source='chat') 数量 / 对话总数
```

### 1.3 conversation + message 表（技能使用分布）

`conversation` 表有 `title` 字段，可通过标题关键词推断技能使用分布（如"源码分析" → source-code-analysis 技能）。更精确的方案是在 conversation 表加 `skill` 字段，但这不在本次范围内。

V1 方案：按 `mode` 字段分布（如果 message 表有 mode/skill 字段），否则按会话标题关键词分类。

**实际检查**：当前 conversation 表没有 `skill` / `mode` 字段。V1 改为**按工具使用组合推断技能**：
- 使用了 `readFile`/`grepCode`/`listFiles` → source-code-analysis
- 使用了 `listTasks`/`createTask`/`getTask` → task-management
- 使用了 `knowledgeSearch` → knowledge-qa
- 使用了 `webSearch` → general-chat
- 无工具调用 → general-chat

## 2. API 设计

### 2.1 GET /api/analytics/summary

总览卡片数据，一次请求返回 4 个核心数字。

```json
{
  "totalConversations": 27,
  "totalTraces": 56,
  "totalToolCalls": 56,
  "avgStepsPerConversation": 2.1,
  "truncationRate": 0.0,
  "hallucinationRate": 0.04,
  "totalDislikes": 1,
  "totalMessages": 58
}
```

### 2.2 GET /api/analytics/tool-frequency

工具调用频率，返回各工具的调用次数、平均耗时、截断次数。

```json
{
  "items": [
    { "toolName": "webSearch", "count": 32, "avgDurationMs": 1200, "truncatedCount": 0 },
    { "toolName": "knowledgeSearch", "count": 9, "avgDurationMs": 350, "truncatedCount": 0 },
    { "toolName": "listTasks", "count": 6, "avgDurationMs": 15, "truncatedCount": 0 }
  ]
}
```

### 2.3 GET /api/analytics/daily-trend?days=7

按日趋势，返回最近 N 天每天的会话数、工具调用数、截断数、dislike 数。

```json
{
  "items": [
    { "date": "2026-09-06", "conversations": 11, "toolCalls": 56, "truncated": 0, "dislikes": 1 }
  ]
}
```

### 2.4 GET /api/analytics/skill-distribution

技能使用分布，按工具使用组合推断技能。

```json
{
  "items": [
    { "skill": "source-code-analysis", "conversations": 5, "percentage": 18.5 },
    { "skill": "general-chat", "conversations": 15, "percentage": 55.6 },
    { "skill": "task-management", "conversations": 3, "percentage": 11.1 },
    { "skill": "knowledge-qa", "conversations": 4, "percentage": 14.8 }
  ]
}
```

## 3. 安全设计

- 所有 API 要求 JWT 鉴权 + `admin` 角色（与现有 `/users` 管理接口一致）
- 不返回 `tool_args` / `tool_result` 原文（仅返回聚合数字，避免敏感信息泄露）
- 不做 user_id 隔离（admin 视角是全局数据）

## 4. 前端设计

### 4.1 页面布局

```
┌─────────────────────────────────────────────┐
│  对话分析                                     │
├─────────────────────────────────────────────┤
│  [总会话] [总工具调用] [平均步数] [幻觉率]      │  ← 4 个统计卡片
├─────────────────────────────────────────────┤
│  工具调用频率 (柱状图)         │  技能使用分布  │  ← 左右布局
│                              │  (饼图)       │
├─────────────────────────────────────────────┤
│  每日趋势 (折线图，会话数 + 工具调用数)          │  ← 全宽
├─────────────────────────────────────────────┤
│  工具性能明细表 (工具名/调用次数/平均耗时/截断)   │  ← 表格
└─────────────────────────────────────────────┘
```

### 4.2 图表选型

- **统计卡片**：4 色染色卡（与 Dashboard.jsx 风格一致），每张卡显示数字 + 标签 + emoji
- **工具调用频率**：水平柱状图（ECharts），按调用次数降序排列
- **技能使用分布**：环形饼图（ECharts），显示百分比和技能名
- **每日趋势**：双 Y 轴折线图（ECharts），左轴会话数，右轴工具调用数
- **工具性能明细**：Ant Design Table，支持排序

### 4.3 交互

- 时间范围选择器（7 天 / 30 天 / 全部），控制 daily-trend 和 summary 数据
- 首屏加载 summary + tool-frequency + skill-distribution，daily-trend 延迟加载
- 点击工具频率柱状图的某一条，跳转到推理轨迹页面并过滤该工具

## 5. 关键决策

### 决策 1：幻觉率用什么数据？

**选项 A**：`eval_candidate` 表的 `source='chat'` 记录数 / 对话总数
**选项 B**：trace 中 `tool_result` 包含 error/failed 关键词的比例
**选项 C**：引入专门的"回答评分"机制

**选择 A**：`eval_candidate` 已有数据（Chat 页面 👎 按钮），是最直接的用户反馈信号。选项 B 是 Agent 的工具调用错误率，不完全等同于幻觉。选项 C 需要新功能开发，不在本次范围。

### 决策 2：技能分布用什么推断？

**选择工具使用组合推断**：不依赖 conversation 表的 skill 字段（目前不存在），而是根据 trace 中记录的 tool_name 组合推断。简单但足够准确。

### 决策 3：是否使用 ECharts？

**是**。项目前端已有 React + Ant Design 技术栈，ECharts 是 React 生态最成熟的图表库，且支持响应式。通过 `echarts-for-react` 集成。

### 决策 4：后端聚合 vs 前端聚合？

**后端聚合**。SQL GROUP BY 比在 JS 里 reduce 高效得多，且减少网络传输量。前端只负责渲染。
