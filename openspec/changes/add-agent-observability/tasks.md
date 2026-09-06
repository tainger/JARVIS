## 1. 数据库迁移与实体

- [x] 1.1 新建 `src/main/resources/db/migration/V4__agent_trace.sql`，创建 `agent_trace` 表（id/message_id/conversation_id/user_id/step_index/step_type/content/tool_name/tool_args/tool_result/duration_ms/is_truncated/created_at），含 `idx_trace_message_step (message_id, step_index)` 和 `idx_trace_conv (conversation_id)` 索引，验证：`./mvnw spring-boot:run` 启动后 Flyway 日志显示 V4 迁移成功
- [x] 1.2 新建 `src/main/java/com/example/jarvis/model/AgentTrace.java` 实体类（class + getter/setter），字段与表对应，验证：编译通过
- [x] 1.3 新建 `src/main/java/com/example/jarvis/mapper/AgentTraceMapper.java` 接口 + `src/main/resources/mapper/AgentTraceMapper.xml`，提供 `batchInsert(List<AgentTrace>)` 和 `findByConversationId(conversationId, userId)` 方法，验证：MyBatis 启动时无绑定错误

## 2. SSE 事件扩展与 TraceState 状态机

- [x] 2.1 修改 `AgentController.chatStream()` 中的 StreamOptions：`eventTypes(REASONING, TOOL_RESULT, AGENT_RESULT)`，验证：启动后无报错，Agent 能接收 TOOL_RESULT 事件
- [x] 2.2 修改 `AgentController.sendDelta()` 方法：增加对 `ThinkingBlock`、`ToolUseBlock`、`ToolResultBlock` 的处理，发送 `tool_call` SSE 事件帧（JSON: step/tool/args）和 `tool_result` SSE 事件帧（JSON: step/tool/summary/truncated），验证：浏览器 Network 面板能看到 `event: tool_call` 和 `event: tool_result` 帧
- [x] 2.3 引入 `TraceState` 内部类替代原始的 `List<AgentTrace> + int[]` 收集器：按步骤边界累积 reasoning/text 缓冲，切换 phase 时 flush 为一条 trace，验证：一轮对话产生 2-5 条 trace（而非 29 条 token 碎片）
- [x] 2.4 跳过 `name="__fragment__"` 的 ToolUseBlock 碎片事件，只记录完整工具调用，验证：trace 表中无 `__fragment__` 工具名
- [x] 2.5 `persistTraces()` 中对所有 trace 兜底 `if (t.getIsTruncated() == null) t.setIsTruncated(false)`，验证：`batchInsert` 不再因 NOT NULL 约束失败
- [x] 2.6 修改 `onComplete` 回调：flush 尾部 reasoning → 持久化消息 → `agentTraceMapper.batchInsert(ts.traces)` 批量写入 trace，验证：对话完成后 `agent_trace` 表中有对应记录
- [x] 2.7 修改 `onError` 回调：断连时仍写入已收集的部分 trace，验证：手动断开 SSE 后表中有部分 trace 记录
- [x] 2.8 在 `sendDelta()` 中对 TOOL_RESULT 事件增加 INFO 级 structured log：`log.info("TRACE step={} tool={} user={} conv={} truncated={}", ...)`，验证：日志中出现 TRACE 开头的日志行

## 3. 只读 API

- [x] 3.1 新建 `src/main/java/com/example/jarvis/controller/AgentTraceController.java`，`GET /api/agent/traces/{conversationId}`：校验 user_id 隔离（conversation 属于当前用户），调用 `agentTraceMapper.findByConversationId()` 返回 trace 列表，验证：curl 请求返回 JSON 数组
- [x] 3.2 确认 `SecurityConfig` 中 `/api/agent/**` 已有 JWT 鉴权覆盖 traces 路径，验证：无 token 请求返回 401
- [x] 3.3 验证 trace 按用户隔离：用户 A 查询用户 B 的 conversation 返回 404/空，验证：跨用户查询不泄露数据

## 4. 前端 SSE 事件处理

- [x] 4.1 修改 `web/src/api/client.js` 的 `streamChat()` yield 逻辑：新增 `tool_call` 和 `tool_result` 事件 yield（保持现有事件不变），验证：前端能收到两种新事件
- [x] 4.2 修改 `web/src/pages/Chat.jsx` 的 `send()` 函数 SSE 消费循环：新增 `tool_call` 和 `tool_result` 事件处理，将步骤追加到 assistant 消息的 `trace` 数组字段，验证：console.log 能看到 trace 数组增长
- [x] 4.3 修改 `web/src/pages/SourceAnalysis.jsx` 同步新增 `tool_call`/`tool_result` 事件处理，验证：源码分析页也能收到工具事件
- [x] 4.4 修改 Chat.jsx 和 SourceAnalysis.jsx 的 assistant 消息渲染：在 reasoning 面板下方、content 上方新增可折叠的"推理路径"面板（details/summary），按 step 顺序渲染每个步骤（图标+工具名+参数+结果摘要+错误标注），验证：浏览器中展开面板能看到完整推理步骤链

## 5. 历史推理路径加载

- [x] 5.1 在 `web/src/api/client.js` 新增 `traceApi.list(conversationId)` 函数调用 `GET /api/agent/traces/{conversationId}`，验证：函数能返回 trace 数组
- [x] 5.2 修改 `Chat.jsx` 的 `switchConversation()` 函数：加载历史消息后同时调用 `traceApi.list()`，将 trace 按 `message_id` 精确匹配写入对应消息的 `trace` 字段（非顺序匹配），验证：切换到历史会话后展开推理路径面板能看到步骤
- [x] 5.3 降级处理：旧对话无 trace 时（API 返回空数组）不显示推理路径面板，只显示 reasoning + content，验证：旧对话页面渲染正常不报错

## 6. 独立诊断页面

- [x] 6.1 新建 `web/src/pages/AgentTraces.jsx`：会话下拉选择器 + 按 messageId 分组的 trace 展示 + Timeline 组件渲染步骤 + 统计标签（总步数/工具调用/错误/截断），验证：页面渲染正常
- [x] 6.2 在 `App.jsx` 新增 `/traces` 路由，验证：直接访问 URL 可达
- [x] 6.3 在 `AdminLayout.jsx` 侧边栏新增"推理轨迹"菜单项（`BugOutlined` 图标），验证：菜单可见且点击跳转

## 7. 端到端验证

- [x] 7.1 登录后在 Chat 页面问"列出所有任务"，验证：推理路径面板展示 Step 1 tool_call=listTasks → tool_result → message，trace 表有记录
- [x] 7.2 在源码分析页面问"Nacos 的服务注册逻辑在哪"，验证：推理路径展示 grepCode → readFile 多步链路，每步有参数+结果
- [x] 7.3 诱导 Agent 调用 `readFile("../../etc/passwd")`，验证：推理路径面板中该步 tool_result 标红显示"path traversal denied"
- [x] 7.4 刷新页面后切换到历史会话，验证：推理路径面板从 API 加载数据正常展示
- [x] 7.5 在 Agent 推理过程中关闭浏览器，验证：重新打开后 trace 表中有部分步骤记录（onError 写入）
- [x] 7.6 检查后端日志，验证：包含 `TRACE step=1 tool=listTasks user=1 conv=15 truncated=false` 格式的日志
- [x] 7.7 验证 `agent_trace` 表数据：`SELECT id, message_id, step_index, step_type, tool_name FROM agent_trace ORDER BY id DESC LIMIT 10` 返回正确的步骤记录
- [x] 7.8 运行 `./mvnw compile` 确认编译通过，运行 `cd web && npx vite build` 确认前端构建通过
