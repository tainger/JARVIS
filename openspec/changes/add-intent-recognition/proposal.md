## Why

当前系统的 Skill 选择完全依赖前端手动传入：`Chat.jsx` 不传 skill 参数走默认，`SourceAnalysis.jsx` 硬编码 `mode: 'source-analysis'`。用户在通用对话中输入"帮我创建一个任务"时，Agent 仍使用 `general-chat` 技能的全量工具集，导致工具决策空间过大、Prompt 不聚焦。需要一层自动意图识别，根据用户 query 内容自动路由到正确的 Skill，在 SkillResolver 之前插入分类层，实现"用户说什么 → 系统自动选什么技能"。

## What Changes

- 新增**分层意图识别引擎** `IntentClassifier`，采用"快路径 + 慢路径"双层设计：
  - **快路径**：基于关键词/正则的规则匹配，零 token、亚毫秒延迟，覆盖 80%+ 明确意图的 query
  - **慢路径**：规则未命中时调用轻量 LLM 做意图分类（4 选 1），~200ms 响应
  - 兜底：LLM 置信度低于阈值时回退到 `general-chat`
- 新增**意图规则配置表** `intent_rule`，支持 DB 驱动的关键词规则管理（非硬编码）
- 新增**意图识别日志表** `intent_log`，记录每次分类的输入摘要、命中路径、置信度、耗时，用于调优规则和评估准确率
- 修改 `AgentController.chatStream()`：在 `SkillResolver.resolve()` 之前插入 `IntentClassifier.classify()`，自动推断 skill 名
- 修改 `ChatRequest`：新增 `autoSkill` 布尔字段（默认 `true`），为 `false` 时跳过自动识别、使用前端传入的 `skill`
- 修改前端 `Chat.jsx`：新增"自动识别"开关，默认开启；关闭时回退到手动技能选择器
- 新增 `GET /api/intent/rules` 只读 API，查询当前生效的意图规则
- 新增 `GET /api/intent/stats` 只读 API，查询意图识别统计（命中率、分布、平均耗时）

## Capabilities

### New Capabilities

- `intent-recognition`: 分层意图识别引擎，包含规则匹配层、LLM 分类层、日志记录、统计 API，在 SkillResolver 之前自动推断用户意图对应的 Skill

### Modified Capabilities

- `skill-integration`: SkillResolver 新增 `resolveByIntent(String query, String fallbackSkill)` 方法，当意图识别引擎返回结果时使用推断的 Skill；`ChatRequest` 新增 `autoSkill` 字段控制是否自动识别

## Impact

- **后端新增**：`IntentClassifier` 接口 + `RuleBasedClassifier` + `LLMClassifier` 实现、`IntentRule` 实体 + Mapper、`IntentLog` 实体 + Mapper、`IntentController` 统计 API
- **后端修改**：`AgentController.chatStream()` 插入分类调用、`ChatRequest` 新增 `autoSkill` 字段、`SkillResolver` 新增意图路由方法
- **数据库新增**：Flyway 迁移创建 `intent_rule` 表（规则种子数据）+ `intent_log` 表
- **前端修改**：`Chat.jsx` 新增"自动识别"开关、`client.js` 新增 `intentApi` 模块
- **配置新增**：`application.properties` 新增意图识别相关配置（LLM 分类超时、置信度阈值、规则缓存 TTL）
- **依赖**：慢路径复用现有 LLM（DeepSeek），不引入新依赖；规则匹配纯 Java 实现，无额外依赖
