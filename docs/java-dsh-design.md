# JARVIS：DeepSeek Harness 的 Java 实现 — 设计文档

> 版本：v0.1（设计稿）
> 状态：设计阶段，实现前先评审
> 范围：在现有 JARVIS Spring Boot 工程上，以 Java/Spring 生态实现 DeepSeek Harness（DSH）核心架构

---

## 1. 背景与目标

### 1.1 什么是 DeepSeek Harness

DeepSeek Harness（`dsh`）是 DeepSeek AI 开源的 agent harness，核心设计理念是 **"一切皆插件"**：

- 基于 Cordis 插件框架，模型适配器、工具注册表、会话日志、agent loop 本身都是可替换的插件；
- **会话事件日志（`SessionEvent`）**是唯一事实来源：模型看到的所有内容都必须能从日志重放出来（"模型可见即已记录"不变式）；
- 存在明确的能力接缝（seam）：`ctx.llm`（模型适配）、`ctx.tools`（工具）、`ctx.fs` / `ctx.shell`（文件与子进程）、`ctx.agents`（agent 生命周期）等。

### 1.2 本项目目标

在现有 JARVIS 工程（Spring Boot 4 + MyBatis + H2 + AgentScope + web 前端）之上，实现 DSH 核心架构的 **Java 版**，与现有 AgentScope/MCP/任务库代码共存并逐步整合。

本轮（第一里程碑）聚焦 DSH 四大核心：

| 能力 | 说明 |
|---|---|
| 会话事件日志 | 追加式 `SessionEvent` 日志 + H2 持久化 + 从日志派生模型上下文（`deriveMessages`） |
| Agent loop | turn/step 状态机：claim 输入 → 组装 prompt + tool schema → 流式调用模型 → 执行工具 → 循环 |
| LLM 适配层 | `LlmAdapter` 接缝 + DeepSeek（OpenAI 兼容）流式适配器，支持 tool calling |
| SSE 流式 Web 聊天 | 改造现有 Chat 页面，流式展示 assistant 增量与工具调用过程 |

MCP 集成、subagent/后台任务、目标管理、插件/事件体系等放入后续里程碑（见 §9）。

---

## 2. DSH 核心架构回顾（移植蓝本）

### 2.1 Turn / Step 流程

DSH 中一次对话处理被划分为 **step**（一次模型请求 + 其调用的工具）与 **turn**（零个或多个 step）：

```text
turn/start
  claim 输入
  组装 prompt sections + tool schemas
  step/start
  append 用户消息到日志
  从日志派生模型历史（deriveMessages）
  llm/stream → assistant/chunk* → assistant/message
  tool/call* → tools/pre-execute → tools/execute → tools/post-execute → tool/result*
  step/end
  若还有工具待执行或新输入到达 → 继续下一 step
turn/end
```

### 2.2 会话日志与不变式

- 会话日志是**追加式**的持久化事件流；
- **模型可见即已记录**：任何进入模型请求的内容，都必须能从日志重建；
- `deriveMessages()` 从日志投影出模型历史（user/assistant/tool 消息），原始 `assistant/chunk` 保留用于 UI 重放。

### 2.3 能力接缝

DSH 通过"服务定义（接口）→ 服务提供者（实现）→ 消费者（通常是模型工具）"三层结构，实现一个提供者替换影响全产品（如文件系统从本地切到远程沙箱，bash/PTY/LSP 跟随迁移）。

---

## 3. 总体设计（Java 映射）

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────┐
│  web 层（Spring MVC）                                 │
│  DshChatController / DshSessionController /         │
│  DshToolController（SSE / REST）                      │
├─────────────────────────────────────────────────────┤
│  agent 层                                            │
│  AgentLoopService（turn/step 状态机，线程模型：每会话串行）│
├──────────────┬──────────────┬───────────────────────┤
│  session 层  │   llm 层     │   tools 层             │
│ SessionService│ LlmAdapter   │ ToolRegistry           │
│ SessionEvent  │ DeepSeek…    │ Tool 接口 + 内置工具    │
│ Repository    │ Adapter      │ 执行管道（pre/execute/ │
│ (H2/JDBC)    │              │  post 钩子，简化版）    │
└──────────────┴──────────────┴───────────────────────┘
        （持久化：H2；模型：DeepSeek/OpenAI 兼容端点）
```

### 3.2 Cordis 插件树 → Spring IoC

| DSH（Cordis） | Java 版（Spring） |
|---|---|
| 插件（plugin）注册/卸载 | `@Component` / `@Configuration` Bean，`@ConditionalOnProperty` 控制开关 |
| `ctx` 上下文 | 构造器注入的 Spring Bean |
| 类型化事件 + waterfall 监听器 | 本轮：直接方法调用 + `AgentEventBus`（简单发布/订阅）；后续可升级为 Spring `ApplicationEvent` 或自研插件体系 |
| profile / bundle 分层配置 | `application.properties` + `dsh.*` 配置前缀 |
| 服务注册表（`ctx.llm`/`ctx.tools`） | 对应 Spring 单例 Bean（`LlmAdapter`、`ToolRegistry`） |

**设计取舍**：本轮不引入自研插件容器，先用 Spring IoC + 显式注册表达"可替换接缝"；接缝接口（`LlmAdapter`、`Tool`、`FsProvider`）与 DSH 一一对应，为后续插件化留出边界。

### 3.3 包结构

```
com.example.jarvis
├── dsh
│   ├── event
│   │   ├── SessionEventType.java     # 事件类型枚举
│   │   ├── SessionEvent.java         # 事件记录（id/sessionId/type/payload/ts）
│   │   └── SessionEventRepository.java  # H2 持久化（JdbcTemplate）
│   ├── session
│   │   ├── DshSession.java           # 会话实体
│   │   └── SessionService.java       # 创建/查询/append/deriveMessages
│   ├── llm
│   │   ├── ChatMessage.java          # role/content/toolCalls/toolCallId/name
│   │   ├── ToolCall.java             # id/name/arguments(JSON)
│   │   ├── ToolSpec.java             # function schema（name/desc/parameters）
│   │   ├── LlmAdapter.java           # 接缝接口（流式回调）
│   │   ├── LlmCallback.java          # onChunk/onToolCalls/onDone/onError
│   │   └── DeepSeekLlmAdapter.java   # OpenAI 兼容 /chat/completions + SSE
│   ├── tools
│   │   ├── Tool.java                 # 接缝接口
│   │   ├── ToolRegistry.java         # 注册/查询/schema 组装
│   │   ├── ToolExecutionContext.java # 执行上下文（工作区、超时、取消标志）
│   │   └── builtin
│   │       ├── BashTool.java         # 子进程执行（工作区内）
│   │       ├── ReadFileTool.java / WriteFileTool.java / ListFilesTool.java
│   │       ├── NowTool.java
│   │       └── TaskDbTools.java      # 复用现有 MyBatis 任务库
│   ├── agent
│   │   ├── AgentLoopService.java     # turn/step 状态机
│   │   ├── AgentStreamCallback.java  # 面向 SSE 的回调（事件名 + JSON payload）
│   │   └── AgentCancelRegistry.java  # 会话级取消
│   └── config
│       └── DshProperties.java        # @ConfigurationProperties("dsh")
├── controller
│   └── DshChatController.java / DshSessionController.java / DshToolController.java
```

---

## 4. 核心组件设计

### 4.1 会话事件日志

**事件类型（`SessionEventType`）**，对齐 DSH 的持久化事件域：

| 事件 | 含义 | payload 要点 |
|---|---|---|
| `TURN_START` | turn 开始 | `{turnId}` |
| `STEP_START` | step 开始 | `{turnId, stepIndex}` |
| `USER_MESSAGE` | 用户消息 | `{message}` |
| `ASSISTANT_CHUNK` | 流式增量（保留重放） | `{turnId, stepIndex, delta}` |
| `ASSISTANT_MESSAGE` | 助手完整消息 | `{content, toolCalls:[{id,name,arguments}]}` |
| `TOOL_CALL` | 工具调用开始 | `{toolCallId, name, arguments}` |
| `TOOL_RESULT` | 工具执行结果 | `{toolCallId, name, output, error?}` |
| `STEP_END` / `TURN_END` | 阶段结束 | `{turnId, stepIndex}` / `{turnId, reason}` |
| `ERROR` | 异常 | `{message}` |

**存储**：H2 两张表（`schema.sql` 增量，见 §5），写入走 `JdbcTemplate`（追加式，`id` 自增保证顺序）。

**`deriveMessages(sessionId)`**：从日志投影模型上下文：

```
USER_MESSAGE        → {role: user, content}
ASSISTANT_MESSAGE   → {role: assistant, content, tool_calls}
TOOL_RESULT         → {role: tool, tool_call_id, name, content}
（其余事件不进入模型上下文；ASSISTANT_CHUNK 仅用于 UI 重放）
```

**不变式**：`AgentLoopService` 中模型请求的输入消息，只能来自 `deriveMessages()` 的投影结果 + 系统 prompt 组装，禁止手写注入未记录的消息（对应 DSH "Model-visible means logged"）。

### 4.2 LLM 适配层

```java
public interface LlmAdapter {
    /** 流式调用；结果通过 callback 回调（可能多次 onChunk / 一次 onToolCalls / 一次 onDone） */
    void chat(List<ChatMessage> messages, List<ToolSpec> tools, LlmCallback callback);
}
```

- `DeepSeekLlmAdapter`：Java 11+ `HttpClient` 直连 `{baseUrl}/chat/completions`，`stream: true`；手写 SSE 帧解析（`data:` 行，`[DONE]` 结束），零新增依赖；
- 支持 OpenAI 兼容 tool calling（DeepSeek `deepseek-chat` 已支持）：请求带 `tools`，响应 `delta.tool_calls` 增量聚合；
- 错误映射：401/429/超时 → 中文可读错误消息（复用现有 `AgentController.toUserMessage` 的思路）；
- `ToolSpec` 即 JSON Schema 的函数声明，由 `ToolRegistry.schemas()` 组装。

**配置**（独立于 AgentScope，互不影响）：`dsh.llm.base-url / api-key / model`。

### 4.3 工具注册与执行管道

```java
public interface Tool {
    String name();                                   // 如 bash / read_file
    String description();                            // 给模型的自然语言描述
    Map<String, Object> parametersSchema();          // JSON Schema（properties/required）
    String execute(ToolExecutionContext ctx, String argumentsJson); // 返回字符串结果
}
```

- `ToolRegistry`：`@PostConstruct` 时从 Spring 上下文收集所有 `Tool` Bean；`schemas()` 供 prompt 组装；`execute(name, args, ctx)` 带 **pre → execute → post** 三段钩子（本轮 pre/post 为日志埋点，为后续鉴权/审批留位）；
- **内置工具**：

| 工具 | 说明 | 安全约束 |
|---|---|---|
| `bash` | 执行 shell 命令 | 仅工作区内 cwd；默认 60s 超时；输出上限 |
| `read_file` / `write_file` / `list_files` | 工作区文件操作 | 路径必须解析到工作区内（防穿越） |
| `now` | 当前时间 | 无 |
| `task_list` / `task_get` / `task_create` | 复用现有 MyBatis 任务库 | 只读/白名单操作 |

- 工具执行结果统一字符串化回填 `TOOL_RESULT`，超长结果截断。

### 4.4 Agent Loop（turn/step 状态机）

对应 DSH 的 `agent-loop`，每会话一个串行执行流（`ExecutorService` 每会话一个 task，`AgentCancelRegistry` 支持取消）：

```
run(sessionId, userMessage):
  turn = TURN_START
  append(USER_MESSAGE)
  for step in 1..maxSteps:
    STEP_START
    systemPrompt = 组装(角色设定 + 工具说明)          # 不写入日志（由配置派生）
    history = deriveMessages(sessionId) + systemPrompt
    llm.chat(history, toolSchemas) →
      onChunk:  append(ASSISTANT_CHUNK); SSE 推送 assistant_delta
      onToolCalls: append(ASSISTANT_MESSAGE)
      onDone: 若 finishReason==tool_calls → 执行工具；否则 → 结束
    对每个 toolCall：
      append(TOOL_CALL); SSE 推送 tool_call
      output = registry.execute(...)
      append(TOOL_RESULT); SSE 推送 tool_result
    STEP_END
    若无 tool_calls → break（turn 完成）
  TURN_END; SSE 推送 done
```

边界与错误恢复：
- `maxSteps` 超限 → 以说明消息结束 turn（记录 `TURN_END(reason=max_steps)`）；
- 工具异常 → 结果字符串化为错误信息回填模型（模型可自我纠正），不中断 turn；
- 模型/网络异常 → 记录 `ERROR`，SSE 推送 `error` 后结束；
- 客户端取消（SSE 断连 / 前端 Stop）→ 置取消标志，循环在安全点退出。

### 4.5 SSE Web 协议

`POST /api/dsh/chat/stream`（`Content-Type: text/event-stream`），事件名与 payload：

| 事件名 | payload | 说明 |
|---|---|---|
| `session` | `{sessionId}` | 会话建立（新建或复用） |
| `turn` | `{turnId, stepIndex}` | step 开始 |
| `assistant_delta` | `{delta}` | 流式文本增量（前端直接拼接） |
| `tool_call` | `{toolCallId, name, arguments}` | 工具调用开始 |
| `tool_result` | `{toolCallId, name, output}` | 工具结果 |
| `done` | `{reason}` | turn 正常结束 |
| `error` | `{message}` | 失败 |

前端改造（`web/src/api/client.js` + `web/src/pages/Chat.jsx`）：
- 升级 `streamChat` 为解析**命名事件**（当前只取 `data:` 行，需支持 `event:` 行）；
- 聊天气泡间穿插工具调用卡片（名称 + 参数 + 结果，可折叠）；
- 会话列表（新建/历史）在后续迭代接入，本轮先支持单会话流式。

---

## 5. 数据模型（schema.sql 增量）

```sql
DROP TABLE IF EXISTS dsh_session;
CREATE TABLE dsh_session (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    title      VARCHAR(255) NOT NULL DEFAULT '新会话',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS dsh_session_event;
CREATE TABLE dsh_session_event (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES dsh_session(id),
    type       VARCHAR(32) NOT NULL,              -- SessionEventType 名
    payload    CLOB,                              -- JSON
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_dsh_event_session ON dsh_session_event(session_id, id);
```

说明：`ASSISTANT_CHUNK` 可能较多，本轮为简化保留全部 chunk；若数据量增长，可引入 DSH 式的 compaction/摘要策略（后续里程碑）。

---

## 6. REST API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/dsh/chat/stream` | SSE 流式对话（body: `{sessionId?, message}`） |
| GET | `/api/dsh/sessions` | 会话列表 |
| POST | `/api/dsh/sessions` | 新建会话 |
| GET | `/api/dsh/sessions/{id}` | 会话详情（含事件日志） |
| DELETE | `/api/dsh/sessions/{id}` | 删除会话 |
| GET | `/api/dsh/tools` | 已注册工具（含 schema） |
| GET | `/api/dsh/health` | 健康检查（模型连通性可选） |

现有 `/api/agent/*`（AgentScope）、`/api/tasks/*`、`/api/mcp/*` 保持不变，后续逐步统一。

---

## 7. 配置项（application.properties 增量）

```properties
# ---------- DSH (Java harness core) ----------
dsh.llm.base-url=${DSH_LLM_BASE_URL:https://api.deepseek.com/v1}
dsh.llm.api-key=${DSH_LLM_API_KEY:sk-demo-key}
dsh.llm.model=${DSH_LLM_MODEL:deepseek-chat}
dsh.llm.timeout-seconds=${DSH_LLM_TIMEOUT_SECONDS:120}

dsh.agent.max-steps=${DSH_AGENT_MAX_STEPS:10}
dsh.agent.system-prompt=${DSH_AGENT_SYSTEM_PROMPT:You are JARVIS, an autonomous agent...}

dsh.workspace=${DSH_WORKSPACE:${user.dir}/workspace}
dsh.tools.bash.enabled=true
dsh.tools.bash.timeout-seconds=60
dsh.tools.file.max-bytes=1MB
```

---

## 8. 与现有代码的整合策略

| 现有组件 | 策略 |
|---|---|
| AgentScope（`agentscope.*`） | 保留，作为"第二模型适配器"候选；`LlmAdapter` 接缝后续可加 `AgentScopeLlmAdapter` 桥接，统一到 DSH loop |
| MCP 服务器注册（`AgentScopeConfig.registerMcpServers`） | 保留现状；后续里程碑：把 MCP 工具包装为 `Tool` 注册进 `ToolRegistry` |
| MyBatis 任务库（`TaskMapper`/`TaskTools`） | 新增 `TaskDbTools` 实现 DSH `Tool` 接口复用同一 Mapper，让 DSH loop 可直接操作任务库 |
| web 前端 | 新增/改造 Chat 页面为 SSE 命名事件协议；其余页面不动 |
| H2 | 追加 `dsh_session` / `dsh_session_event` 表，与 `task` 表共存 |

---

## 9. 实施计划（里程碑）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1（本轮） | 会话事件日志 + LLM 适配层 + 工具注册表（bash/文件/任务库）+ Agent loop + SSE 聊天 | 待实现 |
| M2 | 会话管理 API + 前端会话列表/历史加载；`ASSISTANT_CHUNK` 重放；日志 compaction |
| M3 | MCP 工具包装进 `ToolRegistry`；`AgentScopeLlmAdapter` 桥接 |
| M4 | subagent / 后台任务（jobs）；目标管理（goals）；取消与超时完善 |
| M5 | 简化插件/事件体系（`AgentEventBus` + 扩展点注册表）；settings 卡片 |

每步保持"可编译、可运行、可演示"。

---

## 10. 风险与后续演进

- **流式与事务**：chunk 事件写入频繁，H2 内存库可接受；未来切文件存储或 PostgreSQL 时保持 `SessionEventRepository` 接口不变；
- **安全**：bash/文件工具仅限工作区，路径穿越与命令注入防护是上线前硬性要求；审批/权限走 `ToolExecutionContext` + pre 钩子（M4）；
- **上下文长度**：长会话需 compaction/摘要（对齐 DSH 的 `packages/compaction`），列入 M2；
- **并发**：同一会话并发消息 → 串行队列化（每会话一个锁/队列），跨会话并行；
- **插件化**：本轮以 Spring IoC 表达接缝，若后续需要动态插件（热加载），可在接缝接口不变的前提下引入自定义 ClassLoader 注册表。
