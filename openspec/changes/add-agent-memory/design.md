## Context

当前系统完全无记忆：`ChatRequest` 只有单条 message，`AgentController` 不加载/保存历史，`ReActAgent` 未配置 memory 组件，数据库无对话表，前端不发历史。AgentScope 1.0.12 已提供 `Memory` 接口（短期消息列表）与 `LongTermMemory` 接口（record/retrieve），以及 `autocontext` 自动上下文压缩包——可复用框架能力，自建持久化层。

关键约束：短期记忆需解决"装配点"问题——必须明确哪个 Bean/方法把 PersistentConversationMemory 注入 ReActAgent，避免"代码存在但未接入运行时"。

## Goals / Non-Goals

**Goals:**
- 多轮对话上下文连贯（代词指代、追问）
- 对话历史持久化，刷新/换设备可恢复
- 跨会话用户画像记忆（姓名/偏好/项目背景）
- 上下文窗口自动压缩，防止 Token 超限
- 前端会话管理（列表/切换/新建/删除）
- 复用 AgentScope Memory 接口，零新增第三方依赖

**Non-Goals:**
- 不做 Agent 自主规划的"主动记忆"（如 Agent 决定记什么）——V1 用 LLM 后处理提取
- 不做记忆图谱/实体关系（GraphRAG 级别）——V1 仅文本事实 + 向量检索
- 不支持跨用户共享记忆
- 不重构现有 SSE 流式协议（保持 event: sources/reasoning/done 不变）

## Decisions

### 决策 0：Agent 实例生命周期——请求级而非全局单例（多租户隔离基础）

> **核心问题**：当前 `AgentScopeConfig.jarvisAgent` 是 `@Bean` 单例，所有用户共享同一个 ReActAgent 对象。即使 Memory 数据按 user_id 隔离了，Agent 对象本身可能在请求间持有状态（内部消息缓存、工具调用轨迹），导致跨用户状态泄露。

**选择**：新增 `AgentFactory` 工厂组件，**每次对话请求创建独立的 ReActAgent 实例**，绑定该用户专属的 Memory（短期+长期）。移除 `AgentScopeConfig` 中的 `jarvisAgent` 单例 Bean。LLM client（`OpenAIChatModel`）、`Toolkit` 等重型无状态组件仍为单例 Bean 共享。

```java
// AgentFactory（请求级创建）
@Component
public class AgentFactory {
    private final OpenAIChatModel sharedModel;      // 单例，无状态，共享
    private final Toolkit sharedToolkit;             // 单例，无状态，共享
    private final MessageMapper messageMapper;
    private final UserMemoryMapper userMemoryMapper;
    private final EmbeddingClient embeddingClient;

    public ReActAgent create(Long userId, Long conversationId, String systemPrompt) {
        PersistentConversationMemory shortMem =
            new PersistentConversationMemory(conversationId, userId, messageMapper);
        VectorLongTermMemory longMem =
            new VectorLongTermMemory(userId, userMemoryMapper, embeddingClient);
        return ReActAgent.builder()
            .name("jarvis-" + userId + "-" + conversationId)
            .systemPrompt(systemPrompt)
            .model(sharedModel)        // 共享
            .toolkit(sharedToolkit)    // 共享
            .memory(shortMem)          // 用户专属
            .longTermMemory(longMem)   // 用户专属
            .maxIters(10)
            .build();
    }
}
```

**理由**：
- **ReActAgent 是轻量包装对象**，创建成本 ms 级（只是字段赋值 + builder 构建），真正重型的 LLM client/embedding client 共享单例
- **请求级生命周期天然隔离**：请求结束 Agent 可被 GC 回收，不持有跨请求状态，并发安全
- **记忆随 Agent 绑定**：短期/长期记忆在创建时注入，Agent 无法访问其他用户的记忆，从根本上杜绝串扰

**备选**：
- a) 用 ThreadLocal 缓存 Agent → 否决：线程池复用导致状态污染，生命周期管理复杂
- b) 用 ConcurrentMap<userId, Agent> 缓存 Agent → 否决：Agent 持有的 Memory 会无限增长（消息累积），需额外清理；且并发同一用户多会话仍需每会话独立
- c) prototype Bean（`@Scope("prototype")`）→ 部分可行，但 Spring prototype 仍可能在单例注入时只创建一次；显式工厂更可控

### 决策 1：短期记忆复用 AgentScope Memory 接口，自建 DB 持久化

> **重要澄清**：AgentScope 的 `Memory` 只是一个接口契约（addMessage/getMessages/deleteMessage/clear），**不规定数据存哪**。框架自带的 `InMemoryMemory` 实现存在 JVM 堆内存，服务重启即丢失。本方案**明确不使用** `InMemoryMemory`，而是自建 `PersistentConversationMemory` 实现，数据落盘在 MySQL。

**选择**：实现 `PersistentConversationMemory implements Memory`，内部用 `ConversationMapper`/`MessageMapper` 读写 MySQL `message` 表。每次 Agent 调用前从 DB 加载历史注入，调用后保存新消息。**重启恢复**：服务重启后，`AgentController` 从同一个 MySQL `message` 表重新加载历史注入 memory，对话完全恢复，不丢失。

**持久化数据流**（必须闭环）：
```
用户发消息 → AgentController 从 MySQL message 表加载历史
           → 注入 PersistentConversationMemory（持有当前会话消息列表）
           → ReActAgent 推理（读 memory 中的历史）
           → 回复后 MessageMapper 写入 MySQL message 表  ← 数据落盘在这
           → 下次/重启后从 MySQL 重新加载            ← 恢复路径在这
```

**装配点**（关键）：`AgentController.chatStream()` 中，在 `jarvisAgent.stream()` 调用前：
```java
// 装配链路（必须闭环）
PersistentConversationMemory memory =
    new PersistentConversationMemory(conversationId, userId, messageMapper);
memory.loadFromDb();  // ← 从 MySQL 加载历史注入 memory
jarvisAgent.builder().memory(memory).build();  // ← ReActAgent 需支持 .memory()
// 或：agent.stream(msg, options) 内部已绑定 memory
```
> 需先验证 ReActAgent.builder() 是否有 `.memory()` 方法；若无，则在 stream 调用前手动 `memory.addMessage()` 历史消息，stream 后 `memory.addMessage()` 新消息（同时写入 DB）。

**理由**：
- AgentScope `Memory` 接口抽象了消息列表操作，复用可保持与框架 formatter（OpenAI/Anthropic 消息格式化）兼容
- **数据存储位置由我们决定**：MySQL `message` 表，不依赖框架默认实现，重启可恢复
- 接口与实现解耦：未来换 Redis/PG 存储只需改 `PersistentConversationMemory` 内部，Agent 侧无感

**备选**：纯内存 `InMemoryMemory` → **明确否决**，服务重启即丢失对话历史，违背持久化目标

### 决策 2：长期记忆复用现有 bge-m3 + user_memory 表

**选择**：新建 `user_memory` 表（id, user_id, content, type, embedding JSON, dim, created_at, last_accessed_at），复用 `OllamaEmbeddingClient` 向量化。每轮对话后异步 LLM 提取事实 → embed → 入库；新对话前检索 Top-K 注入系统提示。

**理由**：已有 bge-m3 + Ollama 链路成熟，零新增依赖；与 RAG 知识库隔离（知识库是文档，memory 是用户事实），不混用。

**备选**：用 AgentScope 框架的 LongTermMemory 默认实现 → 否决，默认实现可能不支持 MySQL 持久化与用户隔离

### 决策 3：上下文压缩用 LLM 摘要，不做简单截断

**选择**：当历史 Token 超阈值时，调用 DeepSeek 生成摘要替换最早 N 条消息，保留最近 M 条原始消息。压缩在 `PersistentConversationMemory.loadFromDb()` 中触发。

**理由**：简单截断会丢失关键事实（决策、数字）；LLM 摘要能保留要点。AgentScope autocontext 包可能提供压缩能力，优先评估复用。

### 决策 4：会话标题首条消息后自动生成

**选择**：首条用户消息发送后，取前 20 字作为默认标题（V1 不调 LLM，降低延迟）；V2 可升级为 LLM 摘要生成。

**理由**：首条消息即可反映会话主题，前 20 字够用；LLM 摘要增加延迟与成本，非必要。

### 决策 5：前端改造最小化

**选择**：Chat.jsx 新增侧边栏组件，复用现有消息渲染逻辑；新增 conversationId state 贯穿发送/加载；侧边栏用 antd Layout.Sider。

**理由**：现有 Chat.jsx 已有消息渲染与 SSE 处理，只需加会话管理，不重写核心逻辑。

## 数据库设计

```sql
-- V3__conversation_memory.sql

CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '新对话',
    last_active_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_last_active (user_id, last_active_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,        -- user/assistant/system/tool
    content MEDIUMTEXT NOT NULL,
    token_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conversation_created (conversation_id, created_at ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'fact',  -- fact/preference/goal
    embedding JSON,
    dim INT NOT NULL DEFAULT 1024,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_active TINYINT NOT NULL DEFAULT 1,      -- 软删除/过时标记
    KEY idx_user_active (user_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 装配链路图（关键，防"未接入"）

```
前端 Chat.jsx
  │ POST /api/agent/chat/stream { message, conversationId? }
  ▼
AgentController.chatStream()
  ├─ 1. 若无 conversationId → ConversationService.create(userId)
  ├─ 2. ShortMemory.load(conversationId)        ← 从 DB 加载历史消息
  ├─ 3. LongMemory.retrieve(query, userId)      ← 检索相关长期记忆
  ├─ 4. 组装 system prompt = 基础提示 + 长期记忆 + 源码工具引导
  ├─ 5. AgentFactory.create(userId, conversationId, systemPrompt)  ← 请求级创建独立 Agent
  │     ├─ new PersistentConversationMemory(conversationId, userId)  ← 用户专属短期记忆
  │     ├─ new VectorLongTermMemory(userId)                          ← 用户专属长期记忆
  │     ├─ ReActAgent.builder().memory(shortMem).longTermMemory(longMem).build()
  │     │   ├─ LLM client 共享（单例，无状态）
  │     │   ├─ Toolkit 共享（单例，无状态）
  │     │   └─ Agent 实例独立（请求级，持有该用户上下文）
  │     └─ 同一时刻每个用户/会话有独立 Agent 对象，互不干扰
  ├─ 6. agent.stream(history + current, options)  ← Memory 注入点
  │     ├─ AgentScope Memory 持有该会话完整消息列表
  │     ├─ [可选] autocontext 压缩超窗消息
  │     └─ 工具调用（含源码分析、知识库）
  ├─ 7. SSE 流式返回 reasoning/text/done
  ├─ 8. ShortMemory.save(conversationId, userMsg + assistantMsg)  ← 持久化
  ├─ 9. 异步: LongMemory.record(userMsg + assistantMsg, userId)   ← 提取+存
  └─10. 请求结束，Agent 实例可被 GC 回收（不持有跨请求状态）
```

## Risks / Trade-offs

- **[Token 成本上升]** 每轮都注入历史 + 长期记忆，Token 消耗增加 → 缓解：窗口限制（最近 20 条）+ 摘要压缩 + 长期记忆 Top-3 限制
- **[LLM 摘要延迟]** 上下文压缩需额外 LLM 调用 → 缓解：异步压缩（下一轮加载时用已压缩结果），不阻塞当前响应
- **[记忆提取幻觉]** LLM 可能提取错误事实 → 缓解：type 分类（fact/preference/goal）+ 用户可查看/删除记忆 + 去重
- **[并发写入]** 同一 conversation 并发消息可能导致历史错乱 → 缓解：conversationId + user_id 联合校验，message 按时间戳排序
- **[AgentScope Memory 接入方式]** 若 ReActAgent.builder() 无 .memory() 方法 → 缓解：备选方案为手动在 stream 前 addMessage 历史，stream 后 addMessage 新消息，保持 Memory 接口不变

## Migration Plan

1. Flyway V3 迁移创建三张表（幂等 CREATE TABLE IF NOT EXISTS）
2. 新增 model/mapper/service 层
3. 修改 AgentController 注入记忆链路
4. 前端新增会话管理 UI
5. 零停机部署：无历史数据时新表为空，不影响现有单轮对话（conversationId 可选，缺失时自动创建）

回滚：移除 AgentController 中的记忆注入代码，三张表保留不影响现有功能。
