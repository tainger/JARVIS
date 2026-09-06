## Why

当前 JARVIS 的 AI 对话是**完全无状态**的：后端 `ChatRequest` 只含单条 message，`AgentController` 不保存也不传递历史消息，前端虽有本地 `messages` state 但刷新即丢失，且从不随请求发送。这导致三个核心问题：

1. **多轮对话断裂**：用户问"它一天多少钱"，Agent 无法理解"它"指代上一轮的产品
2. **会话不可恢复**：刷新页面或换设备后，历史对话全部丢失，用户无法接续之前的讨论
3. **无跨会话记忆**：用户反复告知偏好（如"我叫张三""请用中文回答"），Agent 每次都像第一次见面

这三个问题直接制约技术支持/答疑场景的可用性——技术问题往往需要多轮澄清，且用户希望 Agent 记住自己的身份与偏好。

## What Changes

- **短期记忆（会话上下文）**：新增 `conversation` + `message` 两张表持久化对话；`ChatRequest` 增加 `conversationId` 字段；AgentController 加载历史消息注入 Agent 上下文（复用 AgentScope `Memory` 接口）；自动管理上下文窗口（超阈值时摘要压缩旧消息）
- **长期记忆（跨会话用户画像）**：新增 `user_memory` 表存储用户事实与偏好；每轮对话后由 LLM 异步提取关键事实（姓名/偏好/项目背景等）并向量化；新对话开始时检索相关长期记忆注入系统提示
- **会话管理 API**：新增 ConversationController（CRUD 会话、列出历史、删除会话、重命名）；前端新增会话列表侧边栏与切换能力
- **前端 Chat 改造**：Chat.jsx 维护 `conversationId`，发送时携带；新增会话列表（新建/切换/删除）；从后端加载历史消息而非纯本地 state
- **AgentScope 集成**：复用框架原生 `Memory`（短期）与 `LongTermMemory`（长期）接口，避免重复造轮子；`ReActAgent` 配置 memory 组件
- **多租户 Agent 隔离**：将 `AgentScopeConfig.jarvisAgent` 从全局单例 Bean 重构为 `AgentFactory` 工厂，**每次请求创建独立的 ReActAgent 实例**，每个用户的 Agent 绑定自己的短期记忆（PersistentConversationMemory）与长期记忆（VectorLongTermMemory）。LLM client、Toolkit 等重型组件共享，Agent 实例请求级生命周期。确保跨用户/跨会话无状态泄露

## Capabilities

### New Capabilities

- `short-term-memory`: 会话级短期记忆——对话历史持久化、多轮上下文注入、上下文窗口自动压缩
- `long-term-memory`: 跨会话长期记忆——用户事实提取、向量化存储、新对话相关记忆检索注入
- `conversation-management`: 会话生命周期管理——创建/列出/切换/删除/重命名会话，前端侧边栏集成

### Modified Capabilities

<!-- 无：现有 RAG / 任务 / 源码分析 / 聊天 SSE 行为不变，本变更是外挂记忆层 -->

## Impact

- **代码**：
  - 新增：`model/Conversation.java`、`model/Message.java`、`model/UserMemory.java`、`mapper/ConversationMapper.java`、`mapper/MessageMapper.java`、`mapper/UserMemoryMapper.java`、`service/ConversationService.java`、`service/UserMemoryService.java`、`controller/ConversationController.java`、`memory/PersistentConversationMemory.java`、`memory/VectorLongTermMemory.java`
  - 修改：`dto/ChatRequest.java`（加 conversationId）、`controller/AgentController.java`（加载历史 + 保存消息 + 检索长期记忆 + 调用 AgentFactory 创建请求级 Agent）、`config/AgentScopeConfig.java`（移除 jarvisAgent 单例 Bean，新增 AgentFactory 工厂方法）、`application.properties`（记忆相关配置）
  - 新增：`config/AgentFactory.java`（按 userId/conversationId 创建独立 ReActAgent，注入该用户的 Memory + LongTermMemory）
  - 前端：`Chat.jsx`（会话列表 + 历史加载）、`api/client.js`（conversation API）
- **数据库**：新增 Flyway 迁移 `V3__conversation_memory.sql`（conversation、message、user_memory 三张表）
- **依赖**：零新增第三方依赖（复用 AgentScope Memory 接口 + 现有 bge-m3 embedding + MyBatis）
- **安全**：conversation/message/user_memory 均按 user_id 隔离，JWT 鉴权复用现有 SecurityConfig
- **不受影响**：RAG 检索链路、源码分析工具、任务管理、评测系统均不变
