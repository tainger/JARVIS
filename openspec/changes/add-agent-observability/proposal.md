# Proposal: Add Agent Observability

## Why

当前 JARVIS 的 Agent 可观测性严重不足：

1. **SSE 事件覆盖不全**：StreamOptions 只订阅了 `REASONING` 和 `AGENT_RESULT`，未订阅 `TOOL_RESULT`、`HINT`、`SUMMARY`。Agent 调用了什么工具、工具返回了什么、是否触发总结——前端和日志都看不到。
2. **推理路径不可追溯**：`message` 表只存 `role + content`，不记录推理过程。Agent 回答"幻觉"（编造不存在的能力）、"权限错误"（调用了不该调的工具）、"回答错误"（工具返回了错误数据但 Agent 照常回答）时，无法事后追溯诊断。
3. **前端只展示思考过程和回复**：没有工具调用链的可视化，用户无法理解 Agent "为什么这么回答"。

需要一套完整的可观测性体系：SSE 事件扩展 → 推理轨迹持久化 → 前端诊断视图，让每次对话的完整 ReAct 推理路径（思考 → 工具调用 → 工具结果 → 最终回答）可追溯、可诊断。

## What Changes

- **SSE 事件扩展**：StreamOptions 增加 `TOOL_RESULT` 事件订阅；AgentController 新增 `tool_call` 和 `tool_result` SSE 事件帧，实时推送工具调用名、参数、返回结果
- **TraceState 状态机**：AgentScope `incremental(true)` 模式下每个 token 产生独立事件，引入 `TraceState` 内部类按步骤边界累积+flush reasoning 缓冲，避免 token 级碎片 trace；跳过 `__fragment__` 伪工具调用事件；`persistTraces()` 兜底 `isTruncated=false`
- **推理轨迹持久化**：新增 `agent_trace` 表（Flyway V4），每轮 ReAct 迭代记录 step_type（reasoning/tool_call/tool_result/summary）、step_index、content、tool_name、tool_args、tool_result、is_truncated；对话完成后批量写入
- **后端日志增强**：AgentController.sendDelta 中对 TOOL_RESULT 事件增加 structured log（key=value 格式），含 step/tool/user/conv/truncated
- **前端推理路径展示**：Chat.jsx 和 SourceAnalysis.jsx 中 assistant 消息新增可折叠的"推理路径"面板，按步骤展示：思考过程 → 工具调用（名称+参数） → 工具结果（摘要） → 最终回答；支持诊断标注（如"此步工具返回错误但 Agent 忽略"）
- **新增可观测性只读 API**：`GET /api/agent/traces/{conversationId}` 返回该会话所有消息的推理轨迹，供前端诊断视图加载历史数据
- **新增独立诊断页面**：`/traces` 路由 + `AgentTraces.jsx` 页面 + 侧边栏"推理轨迹"菜单，支持选择会话后按 messageId 分组展示完整 ReAct 推理路径

## Capabilities

### New Capabilities

- `agent-observability`: Agent 推理路径全链路可观测——SSE 实时推送工具调用/结果事件、推理轨迹持久化到数据库、前端展示完整 ReAct 步骤链，支持事后诊断幻觉/权限/回答错误等问题

### Modified Capabilities

<!-- 现有聊天和源码分析页面的 assistant 消息新增推理路径面板，不影响已有功能 -->

## Impact

- **代码**：
  - 修改 `AgentController.java`：扩展 StreamOptions、引入 `TraceState` 状态机、新增 `tool_call`/`tool_result` SSE 事件发送、对话完成后批量持久化 trace
  - 新增 `model/AgentTrace.java` 实体类 + `mapper/AgentTraceMapper.java` + MyBatis XML
  - 新增 `AgentTraceController.java`：只读查询 API
  - 修改 `web/src/pages/Chat.jsx`：assistant 消息新增推理路径面板 + 历史会话 trace 精确加载（traceMap[msg.id]）
  - 修改 `web/src/pages/SourceAnalysis.jsx`：同上
  - 新增 `web/src/pages/AgentTraces.jsx`：独立诊断页面，会话选择器 + Timeline 推理步骤展示
  - 修改 `web/src/App.jsx`：新增 `/traces` 路由
  - 修改 `web/src/layouts/AdminLayout.jsx`：侧边栏新增"推理轨迹"菜单
  - 修改 `web/src/api/client.js`：streamChat 新增 `tool_call`/`tool_result` 事件处理 + `traceApi.list()` API
- **数据库**：Flyway V4 迁移，新增 `agent_trace` 表
- **依赖**：零新增第三方依赖
- **安全**：trace API 沿用 `/api/**` JWT 鉴权；trace 数据按 user_id 隔离
- **性能**：trace 收集在内存中进行，对话完成后批量写入（单次 INSERT batch），不影响 SSE 流式性能；trace 查询 API 按 conversation_id 索引
- **不受影响**：RAG 检索链路、知识库导入、任务管理、评测系统、记忆系统均不变
