## Purpose

在用户发送消息时自动识别意图并路由到对应的 Agent Skill，无需用户手动选择技能。采用"规则快路径 + LLM 慢路径"分层设计，兼顾响应速度与分类准确率，同时记录识别日志用于持续调优。

## ADDED Requirements

### Requirement: 分层意图识别引擎

系统 SHALL 提供 `IntentClassifier` 意图识别引擎，在 `SkillResolver.resolve()` 之前执行，根据用户消息内容自动推断目标 Skill。引擎 MUST 采用分层设计：

1. **规则匹配层（快路径）**：基于关键词/正则匹配，零 LLM token 消耗，亚毫秒延迟
2. **LLM 分类层（慢路径）**：规则未命中时调用轻量 LLM 做四分类，带置信度返回
3. **兜底策略**：LLM 置信度低于阈值或调用失败时回退到 `general-chat`

#### Scenario: 规则命中直接返回

- **WHEN** 用户输入"帮我创建一个任务：准备周报"
- **THEN** 规则匹配层命中 `task-management` 的关键词模式
- **AND** 返回结果中 `source` 为 `RULE`
- **AND** 不调用 LLM，`llmTokensUsed` 为 0
- **AND** `latencyMs` < 5ms

#### Scenario: 规则未命中走 LLM 分类

- **WHEN** 用户输入"这个项目用了哪些设计模式"
- **AND** 规则匹配层未命中任何规则
- **THEN** 进入 LLM 分类层，调用轻量模型做四分类
- **AND** 返回结果中 `source` 为 `LLM`
- **AND** `confidence` > 置信度阈值（默认 0.7）
- **AND** `latencyMs` < 2000ms

#### Scenario: LLM 置信度低时兜底

- **WHEN** LLM 分类返回 `confidence` = 0.5（低于阈值 0.7）
- **THEN** 意图识别引擎回退到 `general-chat`
- **AND** 返回结果中 `source` 为 `FALLBACK`
- **AND** 日志记录原始 LLM 分类结果和置信度

#### Scenario: LLM 调用超时

- **WHEN** LLM 分类层在 3 秒内未返回结果（超时）
- **THEN** 意图识别引擎回退到 `general-chat`
- **AND** 返回结果中 `source` 为 `FALLBACK`
- **AND** 日志记录超时事件

#### Scenario: 用户关闭自动识别

- **WHEN** 请求中 `autoSkill` 为 `false`
- **THEN** 意图识别引擎不执行
- **AND** 使用前端传入的 `skill` 参数进行 SkillResolver 解析
- **AND** 不记录意图日志

### Requirement: 规则匹配层

系统 SHALL 维护意图规则表 `intent_rule`，每条规则 MUST 包含：Skill 名称、匹配模式（正则或关键词列表）、优先级、启用状态。规则匹配 MUST 按优先级从高到低执行，首个命中即返回。

#### Scenario: 关键词精确匹配

- **WHEN** 用户输入"列出所有任务"
- **AND** `intent_rule` 表中存在规则：skill=`task-management`，pattern=`任务`
- **THEN** 规则匹配层命中该规则
- **AND** 返回 `task-management`

#### Scenario: 多规则按优先级匹配

- **WHEN** 用户输入"分析代码中的任务分配"
- **AND** 规则 A：skill=`source-code-analysis`，pattern=`分析.*代码`，priority=10
- **AND** 规则 B：skill=`task-management`，pattern=`任务`，priority=5
- **THEN** 规则 A 优先级更高，优先匹配
- **AND** 但规则 A 的正则 `分析.*代码` 命中"分析代码"
- **AND** 返回 `source-code-analysis`

#### Scenario: 规则全部未命中

- **WHEN** 用户输入"今天天气怎么样"
- **AND** 所有启用规则均未匹配
- **THEN** 规则匹配层返回 `null`
- **AND** 进入 LLM 分类层

#### Scenario: 禁用的规则不参与匹配

- **WHEN** 规则 `enabled = 0`
- **AND** 用户输入匹配该规则的模式
- **THEN** 该规则不参与匹配
- **AND** 等同于该规则不存在

### Requirement: LLM 分类层

系统 SHALL 在规则未命中时调用 LLM 做意图四分类。分类 prompt MUST 包含 4 个 Skill 的名称和描述，要求 LLM 返回 JSON 格式 `{ "skill": "<name>", "confidence": <0-1> }`。分类层 MUST 设置 3 秒超时，超时后回退到 `general-chat`。

#### Scenario: LLM 正确分类

- **WHEN** 用户输入"Spring Boot 的自动装配原理是什么"
- **AND** 规则未命中
- **THEN** LLM 分类返回 `skill: "knowledge-qa"`，`confidence: 0.9`
- **AND** 意图识别引擎使用 `knowledge-qa`

#### Scenario: LLM 返回未知 Skill 名

- **WHEN** LLM 返回 `skill: "unknown-skill"`
- **THEN** 意图识别引擎回退到 `general-chat`
- **AND** 日志记录 LLM 返回的原始值

#### Scenario: LLM 返回非法 JSON

- **WHEN** LLM 返回非 JSON 格式的文本
- **THEN** 意图识别引擎回退到 `general-chat`
- **AND** 日志记录原始返回文本

### Requirement: 意图规则配置表

系统 SHALL 通过 Flyway 迁移创建 `intent_rule` 表并插入种子规则数据。表结构 MUST 包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| skill_name | VARCHAR(50) | 目标 Skill 名称 |
| pattern | VARCHAR(500) | 匹配模式（正则或关键词） |
| match_type | VARCHAR(20) | 匹配类型：`REGEX` 或 `KEYWORD` |
| priority | INT DEFAULT 0 | 优先级（越大越优先） |
| enabled | TINYINT(1) DEFAULT 1 | 是否启用 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

#### Scenario: 启动后有种子规则

- **WHEN** 后端首次启动（Flyway 迁移执行）
- **THEN** `intent_rule` 表中有种子规则覆盖 4 个 Skill
- **AND** 每个规则 `pattern`、`match_type`、`skill_name` 非空
- **AND** `enabled` 全部为 1

#### Scenario: 种子规则覆盖核心意图

- **WHEN** 查询 `intent_rule` 种子数据
- **THEN** `task-management` 规则 pattern 包含"任务"关键词
- **AND** `source-code-analysis` 规则 pattern 包含"源码|代码分析|读.*文件"正则
- **AND** `knowledge-qa` 规则 pattern 包含"知识库|文档"关键词
- **AND** `general-chat` 无显式规则（兜底）

### Requirement: 意图识别日志

系统 SHALL 在每次意图识别后写入 `intent_log` 表，记录：用户 ID、会话 ID、消息摘要（前 200 字）、命中来源（RULE/LLM/FALLBACK）、推断的 Skill 名、LLM 置信度、LLM token 消耗、总耗时。日志写入 MUST 异步执行，不阻塞主请求链路。

#### Scenario: 规则命中日志

- **WHEN** 规则匹配层命中 `task-management`
- **THEN** `intent_log` 表新增一条记录
- **AND** `source` 字段为 `RULE`
- **AND** `skill_name` 为 `task-management`
- **AND** `llm_tokens` 为 0
- **AND** `latency_ms` < 5

#### Scenario: LLM 分类日志

- **WHEN** LLM 分类层返回 `knowledge-qa`，confidence=0.9
- **THEN** `intent_log` 表新增一条记录
- **AND** `source` 字段为 `LLM`
- **AND** `confidence` 为 0.9
- **AND** `llm_tokens` > 0

#### Scenario: 日志异步写入不阻塞

- **WHEN** 用户发送消息触发意图识别
- **THEN** 日志写入通过异步线程执行
- **AND** 主请求链路不等待日志写入完成
- **AND** 日志写入失败不影响对话正常进行

### Requirement: 意图识别统计 API

系统 SHALL 提供只读 API 查询意图识别统计：

- `GET /api/intent/stats`：返回最近 N 天的意图识别统计（总识别次数、各 Skill 分布、平均置信度、平均耗时、规则命中率、LLM 调用次数）
- `GET /api/intent/rules`：返回当前生效的意图规则列表

两个端点 MUST 沿用 `/api/**` JWT 鉴权。

#### Scenario: 查询统计

- **WHEN** 已认证用户调用 `GET /api/intent/stats?days=7`
- **THEN** 返回最近 7 天的统计汇总
- **AND** 包含各 Skill 的识别次数和占比
- **AND** 包含规则命中率（RULE / 总数）
- **AND** 包含 LLM 平均耗时和 token 消耗

#### Scenario: 查询规则列表

- **WHEN** 已认证用户调用 `GET /api/intent/rules`
- **THEN** 返回所有 `enabled=1` 的规则
- **AND** 按 `priority` 降序排列

#### Scenario: 未认证请求被拒绝

- **WHEN** 未携带 JWT token 调用 `GET /api/intent/stats`
- **THEN** 返回 401 Unauthorized
