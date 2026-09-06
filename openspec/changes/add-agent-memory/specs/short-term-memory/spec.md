## Purpose

会话级短期记忆：让 Agent 在单次会话内保持多轮对话上下文，理解代词指代与追问；对话历史持久化到数据库，刷新/换设备后可恢复；超上下文窗口时自动压缩旧消息防止撑爆 Token。

## ADDED Requirements

### Requirement: 对话历史持久化

系统 SHALL 在每次对话时持久化用户消息与 Agent 回复到数据库。每条消息 MUST 关联到一个 conversation 与一个 user，记录角色（user/assistant/system/tool）、内容、时间戳。对话历史 MUST 跨会话持久化，服务重启后可恢复。

#### Scenario: 多轮对话持久化

- **WHEN** 用户在同一 conversationId 下连续对话 3 轮
- **THEN** 数据库 message 表新增至少 6 条记录（3 用户 + 3 助手）
- **AND** 每条记录关联正确的 conversation_id 与 user_id

#### Scenario: 服务重启后恢复历史

- **WHEN** 服务重启后用户带着同一 conversationId 继续对话
- **THEN** Agent 能基于前序 3 轮历史理解"它"的指代
- **AND** 回答与上下文连贯

### Requirement: 多轮上下文注入

系统 SHALL 在 Agent 调用前加载当前 conversation 的历史消息并注入 Agent 上下文。历史加载 MUST 按时间正序，最近 N 轮优先（N 由配置控制，默认 20 条）。注入 SHALL 复用 AgentScope `Memory` 接口，保持消息角色与顺序。

#### Scenario: Agent 理解代词指代

- **WHEN** 用户第一轮问"bge-m3 是什么"，第二轮问"它多大"
- **THEN** Agent 加载第一轮历史，理解"它"指代 bge-m3
- **AND** 回答模型大小（1.2GB）而非反问"它指什么"

#### Scenario: 历史超过窗口限制

- **WHEN** conversation 历史消息数超过配置的最大窗口（如 50 条）
- **THEN** 系统只加载最近 N 条（如 20 条）注入 Agent 上下文
- **AND** 更早的消息仍保留在数据库供历史回看，但不注入 LLM

### Requirement: 上下文自动压缩

当注入的历史消息总 Token 数超过配置阈值（如 8000 tokens）时，系统 SHALL 自动将最早的消息摘要压缩为一条系统消息（如"[前序对话摘要：讨论了 bge-m3 的选型理由与性能数据]"），用摘要替换被压缩的原始消息，保留最近的 N 条原始消息。压缩 MUST 不丢失关键事实（产品名、数字、决策）。

#### Scenario: 长对话自动压缩

- **WHEN** 一次连续对话超过 30 轮，累计 Token 超阈值
- **THEN** 系统将前 20 轮压缩为 1 条摘要消息
- **AND** 后续 Agent 仍能基于摘要 + 最近 10 轮继续对话
- **AND** 用户在前端仍可查看完整原始历史

#### Scenario: 压缩不影响关键事实

- **WHEN** 被压缩的前序对话包含关键决策（如"我们决定用 bge-m3 而非 e5"）
- **THEN** 摘要中 MUST 保留该决策事实
- **AND** 后续追问"为什么不用 e5"时 Agent 能回答

### Requirement: 消息按用户隔离

所有对话消息 MUST 按 user_id 隔离。用户 MUST NOT 访问他人的对话历史。conversationId 的获取 MUST 经过 JWT 鉴权与归属校验。

#### Scenario: 跨用户隔离

- **WHEN** 用户 A 尝试用用户 B 的 conversationId 发送消息
- **THEN** 系统拒绝该请求，返回 403
- **AND** 不泄露用户 B 的任何消息内容

### Requirement: 多租户 Agent 实例隔离

系统 SHALL 为每个用户的每次对话请求创建**独立的 ReActAgent 实例**，不得跨用户/跨会话复用同一个 Agent 对象。每个 Agent 实例 MUST 绑定当前用户专属的短期记忆（PersistentConversationMemory，按 conversationId + userId 隔离）与长期记忆（VectorLongTermMemory，按 userId 隔离）。LLM client、Toolkit 等重型组件 MAY 全局共享，但 Agent 运行时状态（消息上下文、工具调用轨迹） MUST 请求级隔离。

#### Scenario: 并发对话互不干扰

- **WHEN** 用户 A 与用户 B 同时发起对话
- **THEN** 系统为两人各创建一个独立的 ReActAgent 实例
- **AND** A 的 Agent 不会读到 B 的历史消息
- **AND** B 的 Agent 不会读到 A 的历史消息
- **AND** 两人的回答各自独立，不串扰

#### Scenario: 同一用户不同会话隔离

- **WHEN** 用户 A 同时开启会话 1 与会话 2
- **THEN** 系统为两个会话各创建一个独立 Agent 实例（或同一工厂按 conversationId 注入不同 Memory）
- **AND** 会话 1 的上下文不注入会话 2 的 Agent

#### Scenario: Agent 不持有跨请求状态

- **WHEN** 两次连续请求分别来自用户 A 和用户 B
- **THEN** 第二次请求的 Agent 是全新实例或已完全重置上下文
- **AND** B 的 Agent 输出不受 A 上一轮对话的任何影响
