## Purpose

让 AI Agent 的 ReAct 推理过程全链路可观测：实时 SSE 推送工具调用与结果事件、推理轨迹持久化到数据库、前端展示完整推理路径面板，支持事后诊断幻觉、权限错误、回答错误等问题。

## ADDED Requirements

### Requirement: SSE 事件扩展——工具调用与结果实时推送

系统 SHALL 扩展 StreamOptions 订阅 `TOOL_RESULT` 事件类型，使 AgentController 在 ReAct 循环中能接收工具调用相关事件。系统 SHALL 新增两种 SSE 事件帧：

- `tool_call`：Agent 决定调用工具时发送，包含 step（步骤编号）、tool（工具名）、args（参数 JSON）
- `tool_result`：工具执行完成后发送，包含 step、tool、duration_ms（耗时）、summary（结果摘要）、truncated（是否截断）

每个 SSE 事件帧 MUST 为 JSON 格式，前端可直接解析。

#### Scenario: 用户看到 Agent 调用 grepCode

- **WHEN** 用户在对话中问"Nacos 的服务注册逻辑在哪"
- **THEN** SSE 流中依次出现 `reasoning` → `tool_call`（grepCode）→ `tool_result` → `reasoning` → `tool_call`（readFile）→ `tool_result` → `message`（最终回答）
- **AND** 前端推理路径面板按步骤展示：思考 → grepCode（参数+结果摘要+耗时） → 思考 → readFile（参数+结果摘要+耗时） → 回答

#### Scenario: 工具结果被截断时标注

- **WHEN** Agent 调用 `readFile` 读取一个 500 行文件
- **THEN** `tool_result` 事件的 `truncated` 字段为 `true`
- **AND** `summary` 字段注明"200/500 lines"
- **AND** 前端在推理路径中显示截断标记

#### Scenario: 工具调用失败时展示错误

- **WHEN** Agent 调用 `readFile("../../etc/passwd")` 被沙箱拒绝
- **THEN** `tool_result` 事件的 `summary` 字段包含错误信息"path traversal denied"
- **AND** 前端推理路径中以红色标注此步骤
- **AND** 后端日志记录此工具调用失败的 structured log

### Requirement: 推理轨迹持久化到数据库

系统 SHALL 新增 `agent_trace` 表，每轮 ReAct 迭代记录一行。表结构 MUST 包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| message_id | BIGINT NOT NULL | 关联 message.id（assistant 回复消息） |
| conversation_id | BIGINT NOT NULL | 关联 conversation.id（冗余，便于查询） |
| user_id | BIGINT NOT NULL | 用户ID（隔离用） |
| step_index | INT NOT NULL | 步骤序号（从 1 开始） |
| step_type | VARCHAR(20) NOT NULL | reasoning / tool_call / tool_result / summary |
| content | MEDIUMTEXT | 思考内容（step_type=reasoning 时） |
| tool_name | VARCHAR(100) | 工具名（step_type=tool_call/tool_result 时） |
| tool_args | TEXT | 工具参数 JSON |
| tool_result | TEXT | 工具结果摘要 |
| duration_ms | INT | 工具执行耗时（step_type=tool_result 时） |
| is_truncated | TINYINT DEFAULT 0 | 工具结果是否截断 |
| created_at | DATETIME DEFAULT CURRENT_TIMESTAMP | 创建时间 |

系统 SHALL 在对话完成后（SSE onComplete 或 onError）批量写入该轮对话的所有 trace 记录。

#### Scenario: 对话完成后 trace 可查

- **WHEN** 一轮对话完成（SSE 发送 `done` 帧）
- **THEN** `agent_trace` 表中存在该 assistant 消息的所有 ReAct 步骤记录
- **AND** 每条记录的 `message_id` 指向同一条 assistant 消息
- **AND** `step_index` 从 1 递增，顺序与 ReAct 执行一致

#### Scenario: SSE 断连时部分 trace 仍写入

- **WHEN** 用户在 Agent 推理过程中关闭浏览器（SSE 断连）
- **THEN** onError 回调中仍写入已收集的部分 trace 记录
- **AND** trace 记录的 `step_index` 连续无跳跃

#### Scenario: 增量模式下的碎片处理

- **WHEN** AgentScope `incremental(true)` 模式下每个 token 产生独立事件
- **THEN** TraceState 状态机按步骤边界累积 reasoning 文本，切换 phase 时才创建一条 trace
- **AND** 一轮对话产生 2-5 条 trace（而非 29 条 token 级碎片）
- **AND** `name="__fragment__"` 的 ToolUseBlock 碎片事件被跳过，不创建伪 trace

#### Scenario: is_truncated null 兜底

- **WHEN** ToolUseBlock 的 trace 记录未设置 `isTruncated` 字段（值为 null）
- **THEN** `persistTraces()` 在批量写入前兜底设置 `isTruncated = false`
- **AND** `batchInsert` 不因 NOT NULL 约束失败

#### Scenario: trace 按用户隔离

- **WHEN** 用户 A 查询 `GET /api/agent/traces/{conversationId}`
- **THEN** 只返回用户 A 自己的 trace 记录
- **AND** 用户 B 的 conversationId 查询返回 404 或空列表

### Requirement: 推理轨迹只读 API

系统 SHALL 提供 `GET /api/agent/traces/{conversationId}` 只读 API，返回该会话所有 assistant 消息的推理轨迹。API MUST 遵循：

- JWT 鉴权（沿用 `/api/**`）
- user_id 隔离（只能查自己的会话）
- 按 message_id + step_index 排序
- 返回 JSON 数组

#### Scenario: 加载历史对话的推理路径

- **WHEN** 前端切换到某历史会话
- **THEN** 调用 `GET /api/agent/traces/{conversationId}` 获取该会话所有 trace
- **AND** 前端按 message_id 精确匹配（`traceMap[msg.id]`），渲染每条 assistant 消息的推理路径面板
- **AND** 不会因某些消息无 trace 而错位到下一条消息

#### Scenario: 无 trace 的旧对话

- **WHEN** 查询一个在 trace 功能上线前创建的会话
- **THEN** API 返回空数组 `[]`
- **AND** 前端不显示推理路径面板（降级为只显示 reasoning + content）

### Requirement: 前端推理路径面板

前端 SHALL 在 assistant 消息气泡内新增可折叠的"推理路径"面板，按 step 顺序展示每个 ReAct 步骤：

- reasoning 步骤：展示思考文本（灰色背景）
- tool_call 步骤：展示工具图标 + 工具名 + 参数 JSON（代码块）
- tool_result 步骤：展示结果摘要 + 耗时 + 截断标记；错误结果以红色标注
- summary 步骤：展示摘要文本

面板 MUST 默认折叠，用户手动展开。面板 MUST 在流式过程中实时更新（SSE 事件到达即追加步骤）。

#### Scenario: 流式过程中实时更新推理路径

- **WHEN** Agent 正在推理，SSE 依次推送 `tool_call` 和 `tool_result` 事件
- **THEN** 前端推理路径面板实时追加新步骤
- **AND** 每步的工具名、参数、结果摘要、耗时在对应事件到达后立即可见

#### Scenario: 诊断幻觉

- **WHEN** 用户问"Agent 有没有调用 readFile 工具"，但 Agent 回答"我没有调用任何工具"
- **THEN** 用户展开推理路径面板
- **AND** 看到 Step 2 的 `tool_call` 记录：tool=readFile，证明 Agent 确实调用了工具
- **AND** 据此诊断 Agent 回答与实际行为不一致（幻觉）

#### Scenario: 诊断权限错误

- **WHEN** Agent 回答"无法读取文件内容"
- **THEN** 用户展开推理路径面板
- **AND** 看到 `tool_result` 的 summary 包含"path traversal denied"
- **AND** 据此诊断是路径沙箱拒绝了访问，而非文件不存在

#### Scenario: 诊断回答错误

- **WHEN** Agent 回答中引用了不存在的类名
- **THEN** 用户展开推理路径面板
- **AND** 查看每步 `tool_result` 的实际返回内容
- **AND** 发现 grepCode 未命中该类名（0 hits），但 Agent 仍在回答中引用
- **AND** 据此诊断 Agent 在无证据的情况下编造答案

### Requirement: 后端日志增强

系统 SHALL 在 AgentController 中对工具调用事件增加 INFO 级 structured log，包含：trace_id（conversation_id）、user_id、step_index、tool_name、duration_ms、is_truncated。错误时 SHALL 增加 WARN 级日志，包含工具错误摘要。

#### Scenario: 从日志快速定位工具调用

- **WHEN** 运维在 `jarvis-info.log` 中搜索某个 conversation_id
- **THEN** 能看到该对话所有 ReAct 步骤的工具名、参数、耗时
- **AND** 日志格式为 `TRACE step=1 tool=grepCode duration_ms=120 user=1 conv=42 truncated=false`
