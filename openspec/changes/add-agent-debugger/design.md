# Design: Agent 推理调试器

## 1. 数据模型

### 1.1 debug_breakpoint 表

```sql
CREATE TABLE IF NOT EXISTS debug_breakpoint (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    message_id      BIGINT NOT NULL,
    step_index      INT    NOT NULL,
    note            VARCHAR(500) COMMENT '断点备注',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp (user_id, conversation_id, message_id, step_index),
    INDEX idx_bp_conv (conversation_id)
);
```

### 1.2 复用现有数据

调试器不新增 trace 数据，完全基于现有数据重建上下文：

| 数据 | 来源 | 用途 |
|------|------|------|
| 推理步骤 | `agent_trace` (step_index, step_type, content, tool_name, tool_args, tool_result) | 步骤列表 |
| 对话消息 | `message` (role, content, created_at) | 上下文重建 |
| 会话信息 | `conversation` (title) | 页面标题 |
| System Prompt | `application.properties` (agentscope.agent.sys-prompt) | 上下文头部 |

## 2. 上下文重建算法

在步骤 N 处，Agent 的完整上下文 =：

```
[1] System Prompt（从配置读取）
[2] 历史消息（该 assistant 消息之前的所有 user/assistant 消息）
[3] 当前 ReAct 步骤 1..N-1 的累积结果：
    - reasoning: 思考文本
    - tool_call: 工具名 + 参数
    - tool_result: 工具返回值
[4] 当前步骤 N 的内容（正在检查的步骤）
```

### 2.1 实现

```java
public DebugContext buildContext(Long conversationId, Long messageId, int stepIndex, Long userId) {
    // 1. 读取 system prompt
    String sysPrompt = agentScopeConfig.getAgent().getSysPrompt();

    // 2. 读取该消息之前的所有历史消息
    List<Message> allMessages = messageMapper.findByConversationId(conversationId, null);
    List<Message> priorMessages = filterBeforeMessage(allMessages, messageId);

    // 3. 读取该消息的所有 trace
    List<AgentTrace> allTraces = findTracesByMessageId(messageId);
    List<AgentTrace> priorTraces = allTraces.stream()
        .filter(t -> t.getStepIndex() < stepIndex)
        .toList();
    AgentTrace currentStep = allTraces.stream()
        .filter(t -> t.getStepIndex() == stepIndex)
        .findFirst().orElse(null);

    // 4. 组装上下文
    return new DebugContext(sysPrompt, priorMessages, priorTraces, currentStep);
}
```

## 3. 路径 Diff 算法

对比两个对话的推理路径：

1. 取两个对话中**最后一条 assistant 消息**的 trace 列表
2. 按步骤序号对齐：`step A[i]` vs `step B[i]`
3. 对每对步骤比较：step_type、tool_name、tool_args 的差异
4. 标记分叉点：第一个 tool_name 或 tool_args 有显著差异的步骤
5. 输出对齐表 + 分叉标记

### 3.1 Diff 输出格式

```json
{
  "conversationA": { "id": 1, "title": "..." },
  "conversationB": { "id": 2, "title": "..." },
  "forkStep": 3,
  "steps": [
    {
      "stepIndex": 1,
      "typeA": "reasoning",
      "typeB": "reasoning",
      "toolA": null,
      "toolB": null,
      "contentA": "让我分析...",
      "contentB": "让我分析...",
      "isSame": true
    },
    {
      "stepIndex": 3,
      "typeA": "tool_call",
      "typeB": "tool_call",
      "toolA": "webSearch",
      "toolB": "knowledgeSearch",
      "argsA": "{\"query\":\"Spring Boot 3.2\"}",
      "argsB": "{\"query\":\"Spring Boot 虚拟线程\"}",
      "isSame": false
    }
  ]
}
```

## 4. API 设计

### 4.1 GET /api/debug/{conversationId}/steps

获取对话的调试步骤列表（按 assistant 消息分组）。

```json
{
  "conversation": { "id": 1, "title": "搜索 Spring Boot 新特性" },
  "messages": [
    {
      "messageId": 10,
      "role": "assistant",
      "content": "Spring Boot 3.2 的主要新特性...",
      "stepCount": 5,
      "steps": [
        { "stepIndex": 1, "stepType": "reasoning", "content": "用户想了解...", "toolName": null },
        { "stepIndex": 2, "stepType": "tool_call", "toolName": "webSearch", "toolArgs": "...", "content": null },
        { "stepIndex": 3, "stepType": "tool_result", "toolResult": "...", "durationMs": 1200 }
      ]
    }
  ]
}
```

### 4.2 GET /api/debug/{conversationId}/context/{messageId}/{stepIndex}

获取某步骤的完整上下文快照。

```json
{
  "systemPrompt": "You are JARVIS...",
  "priorMessages": [
    { "role": "user", "content": "搜索 Spring Boot 3.2 的新特性" }
  ],
  "priorTraces": [
    { "stepIndex": 1, "stepType": "reasoning", "content": "用户想了解..." },
    { "stepIndex": 2, "stepType": "tool_call", "toolName": "webSearch", "toolArgs": "..." }
  ],
  "currentStep": {
    "stepIndex": 3,
    "stepType": "tool_result",
    "toolResult": "搜索结果：...",
    "durationMs": 1200
  },
  "breakpoint": { "hasBreakpoint": true, "note": "这里搜索结果不对" }
}
```

### 4.3 POST /api/debug/breakpoints

添加/删除断点。

```json
// Request
{
  "conversationId": 1,
  "messageId": 10,
  "stepIndex": 3,
  "action": "add",   // "add" | "remove"
  "note": "这里搜索结果不对"
}
```

### 4.4 GET /api/debug/diff?convA=1&convB=2

对比两个对话的推理路径。

## 5. 前端设计

### 5.1 页面布局

```
┌──────────────────────────────────────────────────────┐
│  🐛 推理调试器                                         │
├──────────┬───────────────────────────────────────────┤
│          │  [步骤导航栏]                              │
│  会话     │  Step 1 💭 │ Step 2 🔍 │ Step 3 ↳ │ ...  │
│  选择器   │───────────────────────────────────────────│
│  (左栏)   │  [上下文检查面板]                          │
│          │  ┌─────────────────────────────────────┐  │
│  - Conv1 │  │ System Prompt: You are JARVIS...   │  │
│  - Conv2 │  │ ─────────────────────────────────── │  │
│  - Conv3 │  │ [User]: 搜索 Spring Boot 3.2        │  │
│          │  │ ─────────────────────────────────── │  │
│          │  │ [Step 1] reasoning: 用户想了解...    │  │
│          │  │ [Step 2] tool_call: webSearch(...)   │  │
│          │  │ ─────── 当前步骤 ─────────────────── │  │
│          │  │ [Step 3] tool_result: 搜索结果...    │  │
│          │  └─────────────────────────────────────┘  │
│          │  [断点: 🔴 已标记 "这里搜索结果不对"]      │
│          │───────────────────────────────────────────│
│          │  [路径对比面板]                            │
│          │  对话A vs 对话B  [对比]                    │
│          │  Step 1: 相同 ✓                           │
│          │  Step 3: 分叉 🔀 (webSearch vs knowledge) │
└──────────┴───────────────────────────────────────────┘
```

### 5.2 交互流程

1. **选择会话** → 左栏选择对话
2. **查看步骤** → 右侧显示该对话所有 assistant 消息的步骤列表
3. **点击步骤** → 展开上下文检查面板，显示该步骤的完整上下文
4. **设断点** → 在步骤上右键/点击断点按钮，添加备注
5. **对比路径** → 选择两个对话，点击"对比"按钮，展示步骤对齐表

## 6. 关键决策

### 决策 1：上下文重建 vs 存储

**选择重建**。原因：
- trace 数据已完整记录每步的 content/tool_args/tool_result
- message 表有历史消息
- 实时组装比存储快照更准确（配置变更时自动反映）
- 零额外存储成本

### 决策 2：断点持久化 vs 前端 localStorage

**选择数据库持久化**。原因：
- 跨设备同步
- 断点备注可包含长文本
- 便于团队共享调试标记

### 决策 3：Diff 对比粒度

**选择步骤级对齐**。原因：
- 对话级太粗（只说"这两个对话不同"）
- Token 级太细（无法人工比较）
- 步骤级是 ReAct 的天然边界，对齐有意义
