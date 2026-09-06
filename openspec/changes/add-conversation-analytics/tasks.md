# Tasks: 对话分析仪表盘

## 1. 后端 — Mapper + SQL

- [ ] 1.1 新建 `AnalyticsMapper.java`（@Mapper），定义 4 个聚合查询方法：
  - `selectSummary(days)` → 总会话数、总 trace 数、总工具调用数、平均步数、截断率、dislike 数
  - `selectToolFrequency()` → 工具名 + 调用次数 + 平均耗时 + 截断次数
  - `selectDailyTrend(days)` → 日期 + 会话数 + 工具调用数 + 截断数 + dislike 数
  - `selectSkillDistribution()` → 技能名 + 会话数 + 百分比
  - 验证：`mvn compile` 通过

- [ ] 1.2 新建 `src/main/resources/mapper/AnalyticsMapper.xml`，编写 SQL：
  - Summary：`SELECT COUNT(DISTINCT t.conversation_id), COUNT(*), SUM(step_type='tool_call'), AVG(...), SUM(is_truncated=1)/COUNT(*), (SELECT COUNT(*) FROM eval_candidate WHERE source='chat')`
  - ToolFrequency：`SELECT tool_name, COUNT(*), AVG(duration_ms), SUM(is_truncated=1) FROM agent_trace WHERE step_type='tool_call' AND tool_name IS NOT NULL GROUP BY tool_name ORDER BY COUNT(*) DESC`
  - DailyTrend：`SELECT DATE(t.created_at), COUNT(DISTINCT t.conversation_id), SUM(t.step_type='tool_call'), SUM(t.is_truncated=1) FROM agent_trace t WHERE t.created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY) GROUP BY DATE(t.created_at)`
  - SkillDistribution：按 conversation_id 分组，根据 tool_name 组合推断技能
  - 验证：MySQL 执行 SQL 返回正确结果

## 2. 后端 — Service + Controller

- [ ] 2.1 新建 `AnalyticsService.java`，封装 4 个方法，调用 Mapper 并做轻度转换（如百分比计算）
  - 验证：单元测试或 curl 验证返回 JSON 结构正确

- [ ] 2.2 新建 `AnalyticsController.java`，4 个 GET 端点：
  - `GET /api/analytics/summary?days=7`
  - `GET /api/analytics/tool-frequency`
  - `GET /api/analytics/daily-trend?days=7`
  - `GET /api/analytics/skill-distribution`
  - 所有端点要求 admin 角色（与 UserController 一致）
  - 验证：`curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/analytics/summary` 返回 JSON

## 3. 前端 — API + 页面

- [ ] 3.1 `api/client.js` 新增 `analyticsApi` 对象：summary、toolFrequency、dailyTrend、skillDistribution
  - 验证：浏览器控制台能调用 `analyticsApi.summary()`

- [ ] 3.2 新建 `pages/ConversationAnalytics.jsx`：
  - 4 个统计卡片（复用 Dashboard.jsx 的 statCards 风格）
  - 工具频率水平柱状图（ECharts）
  - 技能分布环形饼图（ECharts）
  - 每日趋势双 Y 轴折线图（ECharts）
  - 工具性能明细表（Ant Design Table）
  - 时间范围选择器（7/30/全部）
  - 验证：页面渲染无报错，图表显示数据

- [ ] 3.3 `App.jsx` 新增路由 `/analytics` → `<ConversationAnalytics />`
- [ ] 3.4 `AdminLayout.jsx` 菜单新增 `{ key: '/analytics', icon: <BarChartOutlined />, label: '对话分析' }`，位于"推理轨迹"之后

## 4. 集成验证

- [ ] 4.1 编译通过：`mvn compile` 无错误
- [ ] 4.2 前端构建通过：`cd web && npx vite build` 无错误
- [ ] 4.3 端到端测试：启动后端 → 登录 admin → 打开对话分析页 → 验证 4 个模块正常显示数据
- [ ] 4.4 权限测试：非 admin 用户访问 /api/analytics/* 返回 403
- [ ] 4.5 空数据降级：清空 agent_trace 表后访问页面，不报错，显示空状态提示
