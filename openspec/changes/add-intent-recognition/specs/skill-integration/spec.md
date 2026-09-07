## MODIFIED Requirements

### Requirement: 技能解析服务

系统 SHALL 提供 `SkillResolver` 服务，根据技能名解析出系统提示词和工具白名单。解析顺序 MUST 为：

1. 如果 `autoSkill` 为 `true` 且意图识别引擎返回了非空 Skill 名，使用意图识别结果
2. 如果 `skillName` 非空且在 `agent_skill` 表中存在且 `enabled=1`，返回该技能
3. 如果 `mode` 非空，将 `mode` 映射为技能名后查询（`source-analysis` → `source-code-analysis`）
4. 以上都未命中，返回默认技能 `general-chat`

当 `autoSkill` 为 `false` 时，MUST 跳过意图识别，直接从第 2 步开始解析。

#### Scenario: 意图识别结果优先

- **WHEN** 请求中 `autoSkill = true`
- **AND** 意图识别引擎返回 `skill = "task-management"`
- **THEN** SkillResolver 使用 `task-management`
- **AND** 不查询 `agent_skill` 表验证（意图识别引擎已保证返回有效 Skill）

#### Scenario: 手动 skill 参数优先于 mode

- **WHEN** 请求中 `autoSkill = false`
- **AND** `skill = "source-code-analysis"`
- **AND** `mode = "source-analysis"`
- **THEN** 使用 `skill` 参数，`mode` 被忽略
- **AND** 行为与变更前一致

#### Scenario: 未传技能时回退到 mode

- **WHEN** 请求中 `autoSkill = false`
- **AND** `skill` 为空，`mode = "source-analysis"`
- **THEN** SkillResolver 将 `mode` 映射为 `source-code-analysis`
- **AND** 返回该技能的 `systemPrompt` 和 `toolWhitelist`

#### Scenario: 都为空时使用默认技能

- **WHEN** 请求中 `autoSkill = false`，`skill` 和 `mode` 都为空
- **THEN** SkillResolver 返回 `general-chat` 技能
- **AND** `toolWhitelist` 为 `all`

#### Scenario: 禁用的技能不返回

- **WHEN** 某技能的 `enabled = 0`（被禁用）
- **AND** 请求中 `skill` 指向该技能
- **THEN** SkillResolver 跳过该技能，回退到默认技能 `general-chat`

### Requirement: 向后兼容

系统 SHALL 保留 `ChatRequest.mode` 字段，当 `skill` 为空时回退到 `mode` 逻辑。`ChatRequest` MUST 新增 `autoSkill` 布尔字段（默认 `true`），控制是否启用自动意图识别。现有 SourceAnalysis.jsx（使用 `mode: 'source-analysis'`）MUST 无需修改即可正常工作。

#### Scenario: 旧前端 mode 参数仍生效

- **WHEN** 请求中 `autoSkill` 未传（默认 `true`）
- **AND** `skill` 为空，`mode = "source-analysis"`
- **AND** 意图识别引擎推断出 `source-code-analysis`
- **THEN** 使用意图识别结果
- **AND** 行为与变更前一致（使用源码分析系统提示词）

#### Scenario: 新前端 autoSkill 开启时不传 skill

- **WHEN** 请求中 `autoSkill = true`，`skill` 为空
- **THEN** 意图识别引擎根据消息内容推断 Skill
- **AND** SkillResolver 使用推断结果

#### Scenario: 用户关闭自动识别后手动选择

- **WHEN** 请求中 `autoSkill = false`，`skill = "task-management"`
- **THEN** 跳过意图识别引擎
- **AND** 使用用户手动选择的 `task-management`
