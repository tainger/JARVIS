# Design: Add Agent Observability

## Context

### 现状分析

当前系统的可观测性缺口：

| 维度 | 现状 | 缺口 |
|------|------|------|
| StreamOptions | `REASONING, AGENT_RESULT` | 缺 `TOOL_RESULT`、`HINT`、`SUMMARY` |
| SSE 事件 | `conversation` `sources` `reasoning` `message` `done` `error` | 缺 `tool_call` `tool_result` |
| 数据库 | `message` 表只存 `role+content+token_count` | 缺推理轨迹表 |
| 前端 | 渲染 `reasoning` + `message` + `sources` | 缺工具调用链可视化 |
| 日志 | Agent 创建、RAG 注入、记忆操作 | 缺 ReAct 每步的 structured log |

AgentScope 1.0.12 的 `EventType` 枚举有 6 个值：`REASONING`、`TOOL_RESULT`、`HINT`、`AGENT_RESULT`、`SUMMARY`、`ALL`。当前只订阅了前两个中的第一个。

### 关键代码位置

- `AgentController.chatStream()` 第 115-118 行：StreamOptions 配置
- `AgentController.sendDelta()` 第 219-249 行：事件分发逻辑
- `AgentFactory.create()` 第 75-89 行：Agent 构建
- `web/src/api/client.js` 第 228-268 行：streamChat SSE 解析
- `web/src/pages/Chat.jsx` 第 204-257 行：SSE 事件消费

## Goals / Non-Goals

**Goals:**
- SSE 实时推送 `tool_call` 和 `tool_result` 事件，前端能看到 Agent 正在调用什么工具、参数是什么、返回了什么
- 每轮对话的完整 ReAct 推理轨迹持久化到数据库，事后可查
- 前端 assistant 消息展示完整的推理路径面板（思考 → 工具调用 → 工具结果 → 回答）
- 只读 API 查询历史对话的推理轨迹

**Non-Goals:**
- 不做实时链路追踪（如 OpenTelemetry/Jaeger），V1 用数据库表 + structured log 即可
- 不做 Agent 性能监控（P99 延迟、token 消耗趋势等），属于运维范畴
- 不做 Agent 行为评估（自动判断幻觉/错误），V1 靠人工查看推理路径诊断
- 不修改 AgentScope 框架内部代码，只扩展 StreamOptions 订阅和增加 SSE 事件发送

## Decisions

### 决策 1：新增 `agent_trace` 表而非扩展 `message` 表

**选择**：新建 `agent_trace` 表，每轮 ReAct 迭代一行记录，通过 `message_id` 关联到 `message` 表。

**理由**：
- `message` 表是短期记忆的载体，每条消息一行，结构简单。推理轨迹是"一条 assistant 消息对应 N 步 ReAct 迭代"，是一对多关系，塞进同一张表会导致 `content` 字段语义混乱
- `agent_trace` 表独立后，可按 `message_id` 查询某条回复的完整推理路径，不影响消息加载逻辑
- 后续如需删除旧 trace（如 30 天清理），可独立操作不删消息

**备选**：在 `message` 表加 `reasoning_trace` JSON 字段 → 否决，大 JSON 字段查询不便、无法按步骤索引

### 决策 2：StreamOptions 增加 `TOOL_RESULT` 而非 `ALL`

**选择**：`StreamOptions.builder().eventTypes(REASONING, TOOL_RESULT, AGENT_RESULT).incremental(true).build()`

**理由**：
- `ALL` 会包含 `HINT` 和 `SUMMARY`，增加前端处理复杂度。V1 先关注核心链路：思考 → 工具调用 → 工具结果 → 最终回答
- `HINT` 事件（RAG/memory 注入提示）已有 `sources` 事件覆盖，无需重复
- `SUMMARY` 事件（maxIters 到达时的摘要）在 V1 可先不订阅，`AGENT_RESULT` 已包含最终回复
- 后续如需可逐步加 `HINT`、`SUMMARY`

**备选**：订阅 `ALL` → 否决，信息量过大、前端处理复杂、SSE 帧过多

### 决策 3：trace 收集在内存中，对话完成后批量写入

**选择**：在 `AgentController.chatStream()` 的 subscriber 中维护 `TraceState` 状态机，`onComplete` 时批量 INSERT。

**理由**：
- SSE 流式过程中逐条 INSERT 会增加延迟，影响用户体验
- 批量写入只需一次数据库往返
- 如果 SSE 中途断开（`onError`），已收集的部分 trace 仍可写入，便于诊断断连原因

**备选**：每步实时 INSERT → 否决，增加每步 5-10ms 延迟，且 ReAct 迭代快时连接池压力大

### 决策 3.1：增量模式下的 TraceState 状态机（实现期修复）

**背景**：AgentScope `incremental(true)` 模式下，每个 token 都产生一个独立事件。最初设计假设每个事件对应一个完整的 ReAct 步骤，直接在每个事件处理中创建 `AgentTrace`。实际运行后发现：

| 问题 | 现象 | 根因 |
|------|------|------|
| token 级碎片 trace | 一轮对话产生 29 条 1-2 字符的 reasoning trace | 每个 token 的 `ThinkingBlock` 都创建了一条 trace |
| `__fragment__` 碎片 | `tool_call` 事件中出现 `name="__fragment__"` 的伪工具调用 | 增量模式下工具调用被拆分为多个 fragment 事件 |
| `is_truncated` null | `batchInsert` 失败，`NOT NULL` 约束违反 | `ToolUseBlock` 的 trace 未设置 `isTruncated` |

**选择**：引入 `TraceState` 内部类，按步骤边界累积+flush：

```java
private static class TraceState {
    final List<AgentTrace> traces = new ArrayList<>();
    int stepCounter = 0;
    StringBuilder reasoningBuf = new StringBuilder();
    String currentPhase = null; // "reasoning" / "text" / null

    void flush() {
        if ("reasoning".equals(currentPhase) && reasoningBuf.length() > 0) {
            stepCounter++;
            AgentTrace t = new AgentTrace();
            t.setStepIndex(stepCounter);
            t.setStepType("reasoning");
            t.setContent(truncate(reasoningBuf, 2000));
            traces.add(t);
            reasoningBuf.setLength(0);
        }
        currentPhase = null;
    }
}
```

**处理逻辑**：
- `ThinkingBlock`：累积到 `reasoningBuf`，当 `currentPhase` 从 reasoning 切换到其他时 flush 为一条 trace
- `ToolUseBlock`：跳过 `name="__fragment__"` 的碎片事件，只对完整工具调用创建 trace；创建前先 flush 前一个 reasoning 步骤
- `ToolResultBlock`：创建前先 flush，设置 `isTruncated`（默认 false）
- `TextBlock`：累积到 `fullResponse`，切换 phase 时 flush 前一个 reasoning
- `onComplete`：最后再 flush 一次，确保尾部 reasoning 不丢失

**`is_truncated` null 兜底**：`persistTraces()` 中对所有 trace 兜底 `if (t.getIsTruncated() == null) t.setIsTruncated(false)`，避免 NOT NULL 约束违反。

**备选**：关闭 `incremental(true)` → 否决，非增量模式下 SSE 流式体验差（用户等待完整步骤后才推送），且无法实时展示推理过程

### 决策 4：SSE 事件帧格式

**选择**：`tool_call` 和 `tool_result` 事件使用 JSON 格式：

```
event: tool_call
data: {"step":1,"tool":"grepCode","args":{"pattern":"class DistroProtocol","dir":"nacos/core/"}}

event: tool_result
data: {"step":1,"tool":"grepCode","duration_ms":120,"summary":"3 hits found","truncated":false}
```

**理由**：
- JSON 结构化数据前端可直接解析渲染，无需正则
- `step` 字段让前端按步骤渲染推理路径
- `duration_ms` 帮助诊断性能问题（如 grepCode 遍历大目录超时）
- `summary` 字段对工具结果做摘要，避免大量数据撑爆前端渲染
- `truncated` 标记工具结果是否被截断（如 readFile 200 行限制）

**备选**：纯文本格式 → 否决，前端需正则解析、无结构化保证

### 决策 5：前端推理路径面板设计

**选择**：在 assistant 消息气泡内、`reasoning` 面板下方、`content` 上方，新增可折叠的"推理路径"面板。按 step 顺序展示：

```
┌─────────────────────────────────┐
│ 💭 思考过程  [可折叠]            │
│ (reasoning 文本)                │
├─────────────────────────────────┤
│ 🔧 推理路径  [可折叠]            │
│ Step 1: 🔍 grepCode             │
│   args: pattern="class Distro"  │
│   result: 3 hits (120ms)        │
│ Step 2: 📄 readFile              │
│   args: path="nacos/.../X.java" │
│   result: 200 lines (50ms)      │
│ Step 3: 📄 readFile              │
│   args: path="...", startLine=201│
│   result: 150 lines (30ms)     │
├─────────────────────────────────┤
│ (assistant 回复内容)            │
└─────────────────────────────────┘
```

**理由**：
- 与现有 `reasoning` 面板风格一致（details/summary 折叠）
- 按步骤编号让用户清晰看到 ReAct 迭代顺序
- 工具图标 + 参数 + 结果摘要 + 耗时，信息密度高但不杂乱
- 折叠默认收起，不干扰正常阅读

### 决策 6：日志增强策略

**选择**：在 `sendDelta()` 中对 `TOOL_RESULT` 事件增加 INFO 级 structured log：

```java
log.info("TRACE step={} tool={} duration_ms={} user={} conv={} truncated={}",
    stepIndex, toolName, durationMs, userId, conversationId, truncated);
```

**理由**：
- INFO 级别写入 `jarvis-info.log`，生产环境可直接查看
- structured log（key=value 格式）便于后续 grep/jq 分析
- 不对每步 reasoning 打日志（太长、太频繁），只对工具调用打日志
- 错误时 `log.warn` 包含完整 trace 摘要

### 决策 7：只读 API 设计

**选择**：`GET /api/agent/traces/{conversationId}` 返回该会话所有 assistant 消息的推理轨迹。

**响应格式**：
```json
[
  {
    "messageId": 123,
    "stepIndex": 1,
    "stepType": "reasoning",
    "content": "用户问的是服务注册逻辑...",
    "toolName": null,
    "toolArgs": null,
    "toolResult": null,
    "durationMs": null,
    "createdAt": "2026-09-06T13:10:00"
  },
  {
    "messageId": 123,
    "stepIndex": 2,
    "stepType": "tool_call",
    "toolName": "grepCode",
    "toolArgs": "{\"pattern\":\"class InstanceController\",\"dir\":\"nacos/\"}",
    ...
  }
]
```

**理由**：
- 按 conversationId 查询，一次加载完整对话的推理轨迹
- `messageId` 让前端关联到具体消息
- `stepType` 区分 reasoning/tool_call/tool_result/summary
- 沿用 `/api/**` JWT 鉴权 + user_id 隔离

## Risk Mitigation

- **[SSE 帧过多]** TOOL_RESULT 事件可能每轮 ReAct 产生多帧 → 缓解：tool_result 只发 1 帧摘要（不含完整结果文本），完整结果在 trace 表中查
- **[数据库膨胀]** agent_trace 表可能快速增大 → 缓解：V1 不做自动清理，但表设计含 `created_at` 索引，后续可加定时清理 30 天前数据
- **[SSE 断连丢失 trace]** 用户中途断开 → 缓解：onError 回调中仍写入已收集的部分 trace
- **[性能]** 批量 INSERT trace 增加对话完成时的延迟 → 缓解：单次 batch INSERT，通常 < 10 条记录，延迟 < 5ms
- **[前端复杂度]** 推理路径面板增加渲染负担 → 缓解：默认折叠，只在用户展开时渲染完整步骤
- **[增量模式 token 碎片]** `incremental(true)` 下每个 token 产生独立事件，导致碎片 trace → 缓解：TraceState 状态机按步骤边界累积+flush（决策 3.1）
- **[`__fragment__` 伪工具调用]** 增量模式下工具调用被拆分为 fragment 事件 → 缓解：跳过 `name="__fragment__"` 的事件，只记录完整工具调用
- **[`is_truncated` null]** `ToolUseBlock` trace 未设置 `isTruncated` 导致 NOT NULL 约束违反 → 缓解：`persistTraces()` 兜底 `false`

## Migration Plan

1. Flyway V4 创建 `agent_trace` 表
2. 新增 `AgentTrace` 实体 + `AgentTraceMapper` + XML
3. 新增 `AgentTraceController` 只读 API
4. 修改 `AgentController`：扩展 StreamOptions + TraceState 状态机 + SSE 事件 + 批量写入
5. 修改前端 `client.js` + `Chat.jsx` + `SourceAnalysis.jsx`：SSE 事件处理 + 推理路径面板
6. 新增前端 `AgentTraces.jsx` 独立诊断页面 + 路由 + 侧边栏菜单
7. 重启后端 + 前端，验证推理路径面板可见、trace 表有记录、API 可查询
