## Purpose

将 AI Agent 的工具调用按场景收敛为可管理的"技能"实体——系统提示词 + 工具白名单 + 场景描述三元组，数据驱动而非硬编码，让对话时按技能自动过滤工具集和注入提示词，减少 LLM 决策空间、降低误调用率。

## ADDED Requirements

### Requirement: 技能数据模型与种子数据

系统 SHALL 新增 `agent_skill` 表存储技能元数据，表结构 MUST 包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| name | VARCHAR(50) UNIQUE | 技能标识（英文，如 `source-code-analysis`） |
| display_name | VARCHAR(100) | 前端显示名（中文） |
| description | VARCHAR(500) | 技能描述 |
| system_prompt | TEXT | 系统提示词 |
| tool_whitelist | VARCHAR(500) | 工具白名单（逗号分隔的类名或 `all`） |
| icon | VARCHAR(50) | Ant Design 图标名 |
| enabled | TINYINT(1) DEFAULT 1 | 是否启用 |
| is_built_in | TINYINT(1) DEFAULT 0 | 是否内置 |
| sort_order | INT DEFAULT 0 | 排序序号 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

系统 SHALL 通过 Flyway V5 创建表并插入 4 条内置种子数据：`general-chat`、`source-code-analysis`、`task-management`、`knowledge-qa`。

#### Scenario: 启动后技能表有种子数据

- **WHEN** 后端首次启动（Flyway V5 迁移执行）
- **THEN** `agent_skill` 表中存在 4 条记录
- **AND** 每条记录的 `name`、`display_name`、`system_prompt`、`tool_whitelist`、`icon` 字段非空
- **AND** `is_built_in` 全部为 `1`
- **AND** `enabled` 全部为 `1`
- **AND** `sort_order` 从 0 递增

#### Scenario: 种子技能工具白名单正确

- **WHEN** 查询 `source-code-analysis` 技能
- **THEN** `tool_whitelist` 为 `SourceCodeTools,KnowledgeSearchTools`
- **AND** 查询 `task-management` 技能时 `tool_whitelist` 为 `TaskTools`
- **AND** 查询 `knowledge-qa` 技能时 `tool_whitelist` 为 `KnowledgeSearchTools`
- **AND** 查询 `general-chat` 技能时 `tool_whitelist` 为 `all`

### Requirement: 技能解析服务

系统 SHALL 提供 `SkillResolver` 服务，根据技能名解析出系统提示词和工具白名单。解析顺序 MUST 为：

1. 如果 `skillName` 非空且在 `agent_skill` 表中存在且 `enabled=1`，返回该技能
2. 如果 `mode` 非空，将 `mode` 映射为技能名后查询（`source-analysis` → `source-code-analysis`）
3. 以上都未命中，返回默认技能 `general-chat`

#### Scenario: 按技能名解析

- **WHEN** 请求中 `skill = "source-code-analysis"`
- **THEN** SkillResolver 返回该技能的 `systemPrompt` 和 `toolWhitelist`
- **AND** `toolWhitelist` 为 `SourceCodeTools,KnowledgeSearchTools`

#### Scenario: 未传技能时回退到 mode

- **WHEN** 请求中 `skill` 为空，`mode = "source-analysis"`
- **THEN** SkillResolver 将 `mode` 映射为 `source-code-analysis`
- **AND** 返回该技能的 `systemPrompt` 和 `toolWhitelist`

#### Scenario: 都为空时使用默认技能

- **WHEN** 请求中 `skill` 和 `mode` 都为空
- **THEN** SkillResolver 返回 `general-chat` 技能
- **AND** `toolWhitelist` 为 `all`

#### Scenario: 技能名不存在时回退

- **WHEN** 请求中 `skill = "nonexistent-skill"`（表中不存在）
- **AND** `mode` 也为空
- **THEN** SkillResolver 回退到默认技能 `general-chat`

#### Scenario: 禁用的技能不返回

- **WHEN** 某技能的 `enabled = 0`（被禁用）
- **AND** 请求中 `skill` 指向该技能
- **THEN** SkillResolver 跳过该技能，回退到默认技能 `general-chat`

### Requirement: 按技能过滤 Toolkit

系统 SHALL 在 `AgentFactory.create()` 中根据技能的工具白名单创建 per-request Toolkit。当白名单为 `all` 时 MUST 使用共享 Toolkit（零开销），否则 MUST 创建新 Toolkit 只注册白名单中的工具。

#### Scenario: 全量白名单使用共享 Toolkit

- **WHEN** 技能的 `toolWhitelist` 为 `all`
- **THEN** AgentFactory 使用共享 `sharedToolkit`（包含全部 7 个 @Tool）
- **AND** 不创建新 Toolkit 对象

#### Scenario: 源码分析技能只注册源码工具

- **WHEN** 技能的 `toolWhitelist` 为 `SourceCodeTools,KnowledgeSearchTools`
- **THEN** AgentFactory 创建新 Toolkit
- **AND** 只注册 `SourceCodeTools`（3 个 @Tool）和 `KnowledgeSearchTools`（1 个 @Tool）
- **AND** 不注册 `TaskTools`

#### Scenario: 任务管理技能不注册源码工具

- **WHEN** 技能的 `toolWhitelist` 为 `TaskTools`
- **THEN** AgentFactory 创建新 Toolkit
- **AND** 只注册 `TaskTools`（3 个 @Tool）
- **AND** ReActAgent 在推理时看不到 `readFile`、`grepCode`、`knowledgeSearch`

### Requirement: 技能只读 API

系统 SHALL 提供两个只读 API 端点：

- `GET /api/skills`：返回所有 `enabled=1` 的技能列表，按 `sort_order` 排序，不含 `system_prompt` 和 `tool_whitelist`
- `GET /api/skills/{id}`：返回指定技能的完整详情（含 `system_prompt` 和 `tool_whitelist`）

两个端点 MUST 沿用 `/api/**` JWT 鉴权。

#### Scenario: 列出启用技能

- **WHEN** 已认证用户调用 `GET /api/skills`
- **THEN** 返回 4 条启用技能
- **AND** 按 `sort_order` 排序
- **AND** 响应不含 `system_prompt` 和 `tool_whitelist` 字段

#### Scenario: 未认证请求被拒绝

- **WHEN** 未携带 JWT token 调用 `GET /api/skills`
- **THEN** 返回 401 Unauthorized

#### Scenario: 查看技能详情

- **WHEN** 已认证用户调用 `GET /api/skills/2`
- **THEN** 返回 `source-code-analysis` 技能的完整详情
- **AND** 响应包含 `system_prompt` 和 `tool_whitelist` 字段

### Requirement: 前端技能选择器

前端 SHALL 在 Chat.jsx 输入区上方新增技能下拉选择器（Ant Design `Select` 组件）。选择器 MUST：

- 通过 `GET /api/skills` 加载选项列表
- 显示图标 + `displayName` + `description`
- 默认选中 `general-chat`
- 选中的技能名通过 `streamChat()` 的 `skill` 参数发送

#### Scenario: 技能选择器加载

- **WHEN** 用户打开 Chat 页面
- **THEN** 技能选择器显示 4 个选项
- **AND** 默认选中"通用对话"
- **AND** 每个选项显示对应的图标和描述

#### Scenario: 切换技能后发送消息

- **WHEN** 用户选择"源码分析"技能后发送消息
- **THEN** 请求体中 `skill` 字段为 `source-code-analysis`
- **AND** 后端 SkillResolver 解析到源码分析技能
- **AND** Agent 只注册 SourceCodeTools + KnowledgeSearchTools

#### Scenario: 源码分析页面固定技能

- **WHEN** 用户通过侧边栏进入源码分析页面
- **THEN** SourceAnalysis.jsx 内部固定使用 `skill: "source-code-analysis"`
- **AND** 不显示技能选择器

### Requirement: 向后兼容

系统 SHALL 保留 `ChatRequest.mode` 字段，当 `skill` 为空时回退到 `mode` 逻辑。现有 SourceAnalysis.jsx（使用 `mode: 'source-analysis'`）MUST 无需修改即可正常工作。

#### Scenario: 旧前端 mode 参数仍生效

- **WHEN** 请求中 `skill` 为空，`mode = "source-analysis"`
- **THEN** SkillResolver 将 `mode` 映射为 `source-code-analysis` 技能
- **AND** 行为与变更前一致（使用源码分析系统提示词）

#### Scenario: 新前端 skill 参数优先

- **WHEN** 请求中 `skill = "task-management"` 且 `mode = "source-analysis"`
- **THEN** `skill` 优先，使用 `task-management` 技能
- **AND** `mode` 被忽略
