## 1. 数据库与模型层

- [x] 1.1 新建 Flyway 迁移 `src/main/resources/db/migration/V3__conversation_memory.sql`：创建 conversation、message、user_memory 三张表（幂等 CREATE TABLE IF NOT EXISTS + 索引），验证：启动后端 Flyway 迁移成功，`SHOW TABLES` 包含三张表
- [x] 1.2 新建 model 类 `Conversation.java`、`Message.java`、`UserMemory.java`（字段对齐 schema），验证：编译通过
- [x] 1.3 新建 mapper 接口 `ConversationMapper.java`、`MessageMapper.java`、`UserMemoryMapper.java` + 对应 XML（findByUserId/insert/findByConversationId/paginate 等），验证：MyBatis 启动无解析错误

## 2. 短期记忆核心

- [x] 2.1 实现 `memory/PersistentConversationMemory.java`：`implements Memory`（AgentScope 接口），内部委托 `MessageMapper`；实现 `loadFromDb(conversationId, maxMessages)` 加载历史，验证：单测注入 5 条消息后 getMessages() 返回 5 条
- [x] 2.2 实现上下文窗口管理：`loadFromDb` 中超 maxMessages 只取最近 N 条；超 maxTokens 时触发摘要压缩（V1 先实现简单截断 + TODO，V2 接 LLM 摘要），验证：30 条消息加载只返回最近 20 条
- [x] 2.3 实现消息持久化：`saveMessage(conversationId, user, role, content)`，验证：对话一轮后 message 表新增 2 条（user + assistant）
- [x] 2.4 修改 `dto/ChatRequest.java` 增加 `String conversationId` 字段（可选），验证：前端不传时后端自动创建会话

## 3. 长期记忆核心

- [x] 3.1 实现 `memory/VectorLongTermMemory.java`：`implements LongTermMemory`，record() 异步调用 LLM 提取事实 + `OllamaEmbeddingClient.embedOne` 向量化 + 写入 user_memory；retrieve() 向量化 query + 遍历当前用户记忆算 cosine + 返回 Top-K，验证：单测写入 3 条记忆后检索相关 query 返回正确 Top-1
- [x] 3.2 实现事实提取 LLM prompt（在 `UserMemoryService` 中）：输入对话轮次，输出 JSON 事实列表（content + type），验证：对话"我叫张三，偏好中文"提取出 2 条事实
- [x] 3.3 实现去重逻辑：record 前检查相似事实（cosine > 0.85）存在则更新 last_accessed_at 而非重复插入，验证：同一事实写入两次只有 1 条记录
- [x] 3.4 实现记忆注入系统提示：`buildMemoryContext(userId, query)` 返回"已知用户信息：..."文本，验证：注入后 Agent 回答使用用户称呼

## 4. Agent 工厂与多租户改造

- [x] 4.1 新建 `config/AgentFactory.java`：`@Component`，注入共享的 `OpenAIChatModel`（单例）、`Toolkit`（单例）、`MessageMapper`、`UserMemoryMapper`、`EmbeddingClient`；实现 `create(userId, conversationId, systemPrompt)` 方法，内部 new 该用户专属的 PersistentConversationMemory + VectorLongTermMemory 并绑定到 ReActAgent.builder()，验证：单测 create 两次返回不同 Agent 实例，各自持有不同 Memory
- [x] 4.2 重构 `AgentScopeConfig.java`：移除 `jarvisAgent` 单例 Bean（第 292-300 行），保留 `agentscopeModel` 和 `agentscopeToolkit` 单例 Bean（共享组件）；**同步更新所有注入 jarvisAgent 的类**（至少 AgentController）改为注入 AgentFactory，验证：编译无错误，启动无异常，AgentFactory 能正常注入共享组件
- [x] 4.3 验证 ReActAgent.builder() 的 `.memory()` 与 `.longTermMemory()` 方法签名（两者都要确认存在）；若任一不存在则调整工厂实现，用手动 addMessage 注入方案，验证：编译通过且 Agent 能正确持有短期与长期 Memory
- [x] 4.4 并发隔离测试：模拟用户 A、B 同时对话（两个线程各发 10 轮），验证：A 的 Agent 不读取 B 的历史，B 的 Agent 不读取 A 的历史，两人回答各自独立
- [x] 4.5 性能验证：测量每次请求创建 Agent 实例的耗时，验证：创建耗时 < 50ms（不显著影响首 Token 延迟）

## 5. AgentController 集成（装配闭环）

- [x] 5.1 修改 `AgentController.chatStream()`：注入 ConversationService + AgentFactory（替换原 jarvisAgent 单例）；无 conversationId 时自动创建会话，验证：首次请求响应包含 conversationId（通过 sources 事件或新增 event）
- [x] 5.2 装配短期记忆：stream 前 `memory.loadFromDb()` 注入历史；stream 后保存 user + assistant 消息，验证：第二轮对话能理解第一轮的代词
- [x] 5.3 装配长期记忆：stream 前 `longTermMemory.retrieve()` 注入系统提示；stream 后异步 `longTermMemory.record()`，验证：新会话 Agent 记得用户姓名
- [x] 5.4 会话标题自动生成：首条消息后截取前 20 字更新 conversation.title，验证：新会话首条消息后标题变为消息前 20 字

## 6. 会话管理 API

- [x] 6.1 新建 `controller/ConversationController.java`：GET /api/conversations（列表）、POST /api/conversations（创建）、GET /api/conversations/{id}（详情+消息分页）、PUT /api/conversations/{id}（重命名）、DELETE /api/conversations/{id}（级联删除），验证：每个接口 curl 测试通过，跨用户访问返回 403
- [x] 6.2 新建 `controller/MemoryController.java`：GET /api/memory（列表）、DELETE /api/memory/{id}（删除），验证：用户只能看到自己的记忆
- [x] 6.3 更新 SecurityConfig 确认 /api/conversations/** 与 /api/memory/** 需 JWT 鉴权，验证：无 token 访问返回 401

## 7. 前端改造

- [x] 7.1 `api/client.js` 新增 conversationApi（list/create/get/rename/delete）与 memoryApi（list/delete），验证：API 调用后端返回正确
- [x] 7.2 Chat.jsx 新增会话侧边栏（antd Layout.Sider）：会话列表、新建按钮、激活高亮、移动端抽屉，验证：切换会话主区刷新历史
- [x] 7.3 Chat.jsx 维护 conversationId state，发送时携带；新建对话重置，验证：多轮对话历史连贯
- [x] 7.4 Chat.jsx 加载会话历史消息并渲染（分页向上加载），验证：切换会话后消息正确显示
- [x] 7.5 Chat.jsx 支持会话重命名与删除（右键菜单或 hover 操作），验证：重命名后列表更新，删除后会话消失

## 8. 端到端验证

- [x] 8.1 多轮对话连贯性：问"bge-m3 是什么"→"它多大"→"为什么选它"，验证：Agent 理解"它"指代 bge-m3，无需重复上下文
- [x] 8.2 会话恢复：对话 5 轮后刷新页面→从侧边栏切换回该会话→继续问"刚才的结论是什么"，验证：Agent 能基于历史回答
- [x] 8.3 跨会话长期记忆：对话中告知"我叫张三"→新建会话→问"你知道我叫什么吗"，验证：Agent 回答"张三"
- [x] 8.4 多租户隔离：用户 A 对话内容（如"我叫张三"）→用户 B 登录对话问"你知道我叫什么吗"，验证：B 的 Agent 不回答"张三"（记忆不串扰）
- [x] 8.5 并发对话：两个用户同时各发 5 轮对话，验证：两边回答各自独立，无状态串扰
- [x] 8.6 用户隔离：用户 A 登录对话→用户 B 登录查看会话列表与记忆列表，验证：B 看不到 A 的会话与记忆
- [x] 8.7 上下文压缩：连续对话 30+ 轮后继续对话，验证：响应不超 Token 限制，Agent 仍能回忆早期关键决策
- [x] 8.8 回归测试：运行 `./mvnw test` 确认现有 RagEvalTest 等测试不受影响，验证：全部通过
