# Proposal: Add Skill Integration

## Why

当前 JARVIS 的 Agent 工具调用通过硬编码方式管理：

1. **工具无差别暴露**：Toolkit 注册了全部 7 个 `@Tool`（TaskTools 3 + SourceCodeTools 3 + KnowledgeSearchTools 1），每次对话 LLM 都需从全部工具中选择，决策空间大、容易选错工具。
2. **模式切换硬编码**：`AgentController.buildSystemPrompt()` 用 `switch(mode)` 硬编码了 2 种模式（默认/source-analysis），新增模式需要改代码+改配置+改前端路由。
3. **无法动态扩展**：用户无法根据业务场景自定义"技能"（如"仅任务管理""仅知识问答"），每次新场景都要开发一个新页面+新路由+新配置。
4. **工具与场景耦合不足**：源码分析场景下 LLM 仍能看到 `listTasks` 等无关工具，干扰决策；任务管理场景下 LLM 仍能看到 `grepCode`，可能误调用。

需要一套**技能抽象层**：将"系统提示词 + 工具白名单 + 场景描述"打包为可管理的技能实体，数据驱动而非硬编码，让对话按场景自动收敛工具集和提示词，减少 LLM 决策空间、降低误调用率、提升回答质量。技能管理菜单将在后续变更中设计。

## What Changes

- **技能数据模型**：新增 `agent_skill` 表（Flyway V5），存储技能元数据——名称、显示名、描述、系统提示词、工具白名单（逗号分隔的工具类名或 `all`）、图标、启用状态、是否内置。Flyway 种子数据预置 4 个内置技能。
- **技能解析服务**：新增 `SkillResolver` 服务，根据技能名解析出 `(systemPrompt, toolWhitelist)` 二元组，供 AgentController 和 AgentFactory 使用。
- **按技能过滤 Toolkit**：`AgentFactory.create()` 接收工具白名单参数，为 `all` 时使用共享 Toolkit，否则创建按请求 Toolkit 只注册白名单中的 `@Component` 工具（MCP 工具暂不过滤，V1 全部注释禁用）。
- **ChatRequest 增加技能字段**：`ChatRequest` 新增 `skill` 字段（技能名），AgentController 用 `SkillResolver` 解析后传给 AgentFactory。
- **技能 API（只读）**：`GET /api/skills` 列出启用的技能，`GET /api/skills/{id}` 查看详情。V1 只提供只读 API，管理 API（CRUD）随技能管理菜单在后续变更中设计。
- **前端技能选择器**：Chat.jsx 输入区上方新增技能下拉选择器（`Select` 组件），`GET /api/skills` 加载选项列表，选中的技能随消息发送。SourceAnalysis.jsx 内部固定使用 `source-code-analysis` 技能。
- **向后兼容**：`mode` 字段保留，当 `skill` 为空时回退到 `mode` 逻辑，不影响现有功能。

## Capabilities

### New Capabilities

- `skill-integration`: 技能抽象层——将系统提示词+工具白名单+场景描述打包为数据驱动的技能实体，对话时按技能过滤工具集和注入提示词，减少 LLM 决策空间

### Modified Capabilities

- `source-code-analysis`: 从硬编码 `mode` 迁移到技能体系，SourceAnalysis.jsx 内部使用 `source-code-analysis` 技能
- `agent-observability`: 推理轨迹中记录当前使用的技能名，便于诊断"为什么选了这个工具"

## Impact

- **代码**：
  - 新增 `model/AgentSkill.java` 实体类 + `mapper/AgentSkillMapper.java` + MyBatis XML
  - 新增 `service/SkillResolver.java` 技能解析服务
  - 新增 `controller/SkillController.java` 只读 API
  - 修改 `config/AgentFactory.java`：注入 3 个工具 Bean（TaskTools/SourceCodeTools/KnowledgeSearchTools），`create()` 方法增加 `toolWhitelist` 参数，按白名单创建 per-request Toolkit
  - 修改 `dto/ChatRequest.java`：新增 `skill` 字段
  - 修改 `controller/AgentController.java`：`chatStream()` 中调用 `SkillResolver` 解析技能，传 prompt + whitelist 给 AgentFactory
  - 修改 `web/src/api/client.js`：新增 `skillApi.list()` API；`streamChat()` 参数增加 `skill`
  - 修改 `web/src/pages/Chat.jsx`：新增技能选择器（Select 组件），发送时携带 skill
  - 修改 `web/src/pages/SourceAnalysis.jsx`：内部固定使用 `skill: "source-code-analysis"`
- **数据库**：Flyway V5 迁移，新增 `agent_skill` 表 + 4 条种子数据
- **依赖**：零新增第三方依赖
- **安全**：技能 API 沿用 `/api/**` JWT 鉴权；内置技能不可删除（V1 只读 API，无删除入口）
- **性能**：per-request Toolkit 创建只注册方法引用（非重建工具实例），开销 < 1ms；技能查询走 DB 索引，可后续加缓存
- **不受影响**：RAG 检索链路、知识库导入、任务管理、评测系统、记忆系统、推理轨迹均不变
