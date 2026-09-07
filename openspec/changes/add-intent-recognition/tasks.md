## 1. 数据库迁移

- [ ] 1.1 创建 Flyway 迁移文件 `V{N}__intent_rule_and_log.sql`，建 `intent_rule` 表（id, skill_name, pattern, match_type, priority, enabled, created_at, updated_at）和 `intent_log` 表（id, user_id, conversation_id, message_summary, source, skill_name, confidence, llm_tokens, latency_ms, created_at），验证表结构通过 `DESCRIBE` 确认
- [ ] 1.2 在迁移文件中插入 8 条种子规则数据（task-management ×2, source-code-analysis ×2, knowledge-qa ×2, general-chat ×2），验证 `SELECT * FROM intent_rule` 返回 8 条记录且 priority 正确

## 2. 意图识别核心模型

- [ ] 2.1 创建 `IntentResult` record（skillName, source, confidence, llmTokensUsed, latencyMs）和 `ClassificationSource` 枚举（RULE, LLM, FALLBACK），验证编译通过
- [ ] 2.2 创建 `IntentClassifier` 接口，定义 `IntentResult classify(String userMessage)` 方法，验证编译通过
- [ ] 2.3 创建 `IntentRule` 实体类（对应 intent_rule 表字段），验证 Lombok 注解生成的 getter/setter 正确

## 3. 规则匹配层

- [ ] 3.1 创建 `IntentRuleMapper` 接口 + XML，提供 `findAllEnabled()` 查询所有启用规则（按 priority 降序），验证 `SELECT` SQL 正确返回排序结果
- [ ] 3.2 创建 `CompiledRule` 内部 record（skillName, regex Pattern, keywords List），提供 `matches(String text)` 方法：REGEX 类型用 `regex.matcher(text).find()`，KEYWORD 类型用 `keywords.stream().anyMatch(text::contains)`，验证两种匹配模式单测通过
- [ ] 3.3 实现 `RuleBasedClassifier`，注入 `IntentRuleMapper`，使用 volatile List + 60 秒 TTL 缓存规则，命中返回 IntentResult(source=RULE, confidence=1.0)，未命中返回 null，验证缓存逻辑单测通过（首次查 DB、60 秒内不查）

## 4. LLM 分类层

- [ ] 4.1 创建 LLM 分类 prompt 模板（4 个 Skill 名称 + 描述 + JSON 输出要求），验证 prompt 包含所有 4 个 Skill 且要求返回 `{"skill": "...", "confidence": ...}` 格式
- [ ] 4.2 实现 `LLMClassifier`，注入 `AgentScopeConfig.sharedModel`，调用 `model.chat()` 单轮推理设置 3 秒超时，验证超时后返回 null 不抛异常
- [ ] 4.3 实现 JSON 解析逻辑：正则提取 `{"skill": "...", "confidence": ...}`，校验 skill 名在 4 个有效值内，confidence 缺失默认 0.5，非 JSON 返回 null，验证 3 种异常输入（合法 JSON / 非法 JSON / 空 skill 名）的单测通过

## 5. 意图识别链

- [ ] 5.1 实现 `IntentClassifierChain`，注入 `RuleBasedClassifier` 和 `LLMClassifier`，串联执行：规则命中→直接返回；规则未命中→LLM 分类→置信度 >= 阈值返回；否则兜底 general-chat(FALLBACK)，验证三层链路单测通过（mock 两个子 classifier）
- [ ] 5.2 读取 `application.properties` 配置项（`intent.llm.timeout-seconds`、`intent.llm.confidence-threshold`、`intent.rule.cache-ttl-seconds`），注入到对应组件，验证配置缺失时使用默认值（3 / 0.7 / 60）

## 6. 异步日志

- [ ] 6.1 创建 `IntentLog` 实体类（对应 intent_log 表字段），验证字段类型与表结构一致
- [ ] 6.2 创建 `IntentLogMapper` 接口 + XML，提供 `insert()` 方法，验证插入 SQL 正确
- [ ] 6.3 创建 `IntentLogService`，`@Async("intentLogExecutor")` 注解的 `logAsync()` 方法，验证日志写入不阻塞调用方（主线程立即返回）
- [ ] 6.4 配置 `intentLogExecutor` 线程池（core=2, max=4, queue=100, DiscardOldest），验证线程池名称在日志中可见

## 7. AgentController 集成

- [ ] 7.1 修改 `ChatRequest` record，新增 `Boolean autoSkill` 字段，提供 `autoSkill()` 方法返回 `autoSkill == null || autoSkill`，验证旧请求（不含 autoSkill）默认为 true
- [ ] 7.2 在 `AgentController` 注入 `IntentClassifierChain` 和 `IntentLogService`，新增 `resolveSkillName()` 私有方法：autoSkill=false 或 skill 非空时用前端值，否则调用意图识别，验证逻辑分支正确
- [ ] 7.3 修改 `AgentController.chatStream()`，在创建 Agent 前调用 `resolveSkillName()`，将结果传给 SkillResolver，验证 Agent 使用的是意图识别推断的 Skill
- [ ] 7.4 新增 `intent` SSE 事件，在 `conversation` 事件之后发送 `{"skill": "...", "source": "...", "latencyMs": N}`，验证前端可收到该事件

## 8. 统计 API

- [ ] 8.1 创建 `IntentController`，`GET /api/intent/stats?days=N` 端点，查询 `intent_log` 表按 skill_name 分组统计（总数、分布、平均置信度、平均耗时、规则命中率、LLM token 总量），验证返回 JSON 格式正确
- [ ] 8.2 `GET /api/intent/rules` 端点，查询 `intent_rule` 表所有启用规则按 priority 降序，验证返回 JSON 格式正确
- [ ] 8.3 验证两个端点未携带 JWT 时返回 401 Unauthorized

## 9. 前端改造

- [ ] 9.1 修改 `client.js`，`streamChat()` 支持 `autoSkill` 参数传入请求体，新增 `intentApi.stats()` 和 `intentApi.rules()` 方法，验证请求体包含 autoSkill 字段
- [ ] 9.2 修改 `streamChat()` 事件处理，新增 `intent` 事件回调，将识别结果传给上层组件，验证 intent 事件可被捕获
- [ ] 9.3 修改 `Chat.jsx`，新增"自动识别" Switch 开关（默认 ON），开关 ON 时技能选择器变灰显示"自动"，OFF 时可选，验证开关切换状态正确
- [ ] 9.4 修改 `Chat.jsx`，SSE 收到 `intent` 事件时在 AI 回复上方显示意图标签（Tag 组件，显示 skill displayName + source 图标），验证标签显示位置和内容正确

## 10. 端到端验证

- [ ] 10.1 重启后端，发送"帮我创建一个任务：准备周报"，验证规则层命中 task-management（intent 事件 source=RULE），Agent 只注册 TaskTools
- [ ] 10.2 发送"Spring Boot 自动装配原理是什么"，验证 LLM 分类命中 knowledge-qa 或 general-chat（source=LLM，confidence > 0.7）
- [ ] 10.3 发送"你好，今天天气怎么样"，验证规则层命中 general-chat（source=RULE），不调用 LLM
- [ ] 10.4 关闭自动识别开关，手动选择"任务管理"后发消息，验证跳过意图识别、使用手动选择的 skill
- [ ] 10.5 调用 `GET /api/intent/stats?days=1`，验证返回统计数据（总识别次数、各 Skill 分布、命中率）
- [ ] 10.6 调用 `GET /api/intent/rules`，验证返回 8 条规则按 priority 降序
- [ ] 10.7 检查 `intent_log` 表，验证每次识别都有日志记录，source/latency_ms 字段正确
