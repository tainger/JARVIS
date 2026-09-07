## Context

### 现状

参见 `proposal.md - Why`。当前 Skill 选择完全依赖前端手动传入 `mode` 或 `skill` 参数，后端 `AgentController.chatStream()` 第 128 行直接调用 `buildSystemPrompt(request.mode(), userId)` 构建 prompt，无任何基于消息内容的自动意图识别。

### 关键代码位置

| 文件 | 行号 | 说明 |
|------|------|------|
| `AgentController.java` | 97-128 | `chatStream()` — 当前 Skill 路由入口 |
| `AgentController.java` | 247-268 | `buildSystemPrompt()` — 硬编码 switch(mode) |
| `ChatRequest.java` | 10 | `record(message, conversationId, mode)` |
| `AgentScopeConfig.java` | 35-45 | `agentscopeToolkit()` — 共享 Toolkit Bean |
| `Chat.jsx` | 238-240 | `streamChat()` — 不传 mode/skill |
| `SourceAnalysis.jsx` | 72-74 | `streamChat()` — 固定 `mode: 'source-analysis'` |

### 现有 LLM 调用方式

系统通过 `AgentScopeConfig` 构建共享 `Model` 实例（DeepSeek），`AgentFactory.create()` 注入到 ReActAgent。意图识别的 LLM 分类层可复用同一 Model 实例，但需用独立 prompt 做单轮调用（不走 ReAct 循环）。

## Goals / Non-Goals

**Goals:**

- 自动意图识别：用户无需手动选技能，系统根据 query 内容推断
- 分层设计：规则快路径覆盖明确意图，LLM 慢路径处理模糊意图
- 可观测：每次识别记录日志，支持统计分析和规则调优
- 可开关：用户可关闭自动识别回退到手动选择
- 数据驱动：规则存储在 DB 中，不硬编码

**Non-Goals:**

- 不做多意图识别（V1 每条消息只识别一个 Skill）
- 不做意图识别模型微调（V1 用 prompt 工程实现，不训练专用模型）
- 不做规则管理 UI（V1 只读 API，管理 UI 随 V2 设计）
- 不做实时规则热更新（V1 缓存 TTL 60 秒，不监听变更事件）
- 不做意图识别的 A/B 测试框架（V1 只记录日志，不做对比实验）
- 不替换 SkillResolver（V1 在 SkillResolver 之前插入分类层，SkillResolver 逻辑不变）

## Decisions

### 决策 1：IntentClassifier 接口设计（策略模式）

**选择**：定义 `IntentClassifier` 接口，`RuleBasedClassifier` 和 `LLMClassifier` 分别实现规则层和 LLM 层，`IntentClassifierChain` 串联两层。

```
IntentClassifier (interface)
├── RuleBasedClassifier   (规则快路径)
├── LLMClassifier          (LLM 慢路径)
└── IntentClassifierChain (串联两层 + 兜底)
```

```java
public interface IntentClassifier {
    IntentResult classify(String userMessage);
}

public record IntentResult(
    String skillName,      // 推断的 Skill 名
    ClassificationSource source, // RULE / LLM / FALLBACK
    double confidence,     // 置信度 [0, 1]
    int llmTokensUsed,     // LLM token 消耗
    long latencyMs         // 总耗时
) {}
```

`IntentClassifierChain.classify()` 逻辑：

```
1. 调用 RuleBasedClassifier.classify()
   → 命中：返回 (skillName, RULE, 1.0, 0, latency)
   → 未命中：继续
2. 调用 LLMClassifier.classify()
   → confidence >= 阈值：返回 (skillName, LLM, confidence, tokens, latency)
   → confidence < 阈值 或异常：继续
3. 兜底：返回 (general-chat, FALLBACK, 0, 0, latency)
```

**理由**：
- 策略模式让每层独立可测，单测时可直接测 `RuleBasedClassifier` 不依赖 LLM
- Chain 模式清晰串联两层，未来加第三层（如 embedding 分类）不破坏现有结构
- `IntentResult` 值对象统一输出格式，日志和统计 API 可直接消费

**备选**：单一 `IntentClassifier` 实现内部 if-else 分层 → 否决，不可独立测试，扩展时修改面大

### 决策 2：规则匹配实现

**选择**：`RuleBasedClassifier` 从 `intent_rule` 表加载规则，按 `priority` 降序遍历。支持两种匹配模式：

- `KEYWORD`：将 pattern 按逗号拆分为关键词列表，任一关键词出现即命中
- `REGEX`：将 pattern 编译为 `Pattern`，`find()` 判断命中

```java
public class RuleBasedClassifier implements IntentClassifier {
    private volatile List<CompiledRule> rules;  // 缓存
    private final long cacheTtlMs = 60_000;       // 60 秒刷新

    public IntentResult classify(String userMessage) {
        for (CompiledRule rule : getRules()) {
            if (rule.matches(userMessage)) {
                return new IntentResult(
                    rule.skillName, ClassificationSource.RULE,
                    1.0, 0, /* latency */);
            }
        }
        return null;  // 未命中，交给下一层
    }
}

record CompiledRule(String skillName, Pattern regex, List<String> keywords) {
    boolean matches(String text) {
        if (regex != null) return regex.matcher(text).find();
        return keywords.stream().anyMatch(text::contains);
    }
}
```

**规则缓存**：使用 `volatile` 引用 + 时间戳双重检查，60 秒刷新一次，避免每次请求查 DB。

**理由**：
- 正则编译有成本，缓存编译后的 `Pattern` 对象
- `volatile` + 双重检查比 `synchronized` 轻量，适合读多写少场景
- 60 秒 TTL 平衡实时性与性能

**备选**：每次请求实时查 DB → 否决，规则变更频率低，缓存 60 秒可接受；用 Caffeine 缓存 → 否决，引入新依赖不必要

### 决策 3：LLM 分类 prompt 设计

**选择**：单轮调用，prompt 包含 4 个 Skill 的名称和描述，要求返回 JSON。

```
你是一个意图分类器。请将以下用户输入分类到其中一个意图类别，只返回 JSON 格式。

意图类别：
1. task-management — 任务创建、查询、管理、待办
2. source-code-analysis — 源码阅读、代码分析、文件查看
3. knowledge-qa — 知识库检索、文档搜索
4. general-chat — 闲聊、通用问题、不属于以上任何一类

用户输入: {userMessage}

请返回 JSON: {"skill": "<skill-name>", "confidence": <0.0-1.0>}
只返回 JSON，不要有其他文字。
```

**LLM 调用方式**：复用 `AgentScopeConfig` 中的 `sharedModel`，调用 `model.chat()` 做单轮推理（不走 ReActAgent），设置 3 秒超时。

```java
public class LLMClassifier implements IntentClassifier {
    private final Model sharedModel;
    private final double confidenceThreshold;

    public IntentResult classify(String userMessage) {
        Msg msg = Msg.builder()
            .role(Role.SYSTEM)
            .textContent(buildPrompt(userMessage))
            .build();
        try {
            Msg resp = sharedModel.chat(List.of(msg)).block(Duration.ofSeconds(3));
            return parseJsonResponse(resp.getTextContent());
        } catch (Exception e) {
            return null;  // 超时或异常，交给兜底
        }
    }
}
```

**JSON 解析**：正则提取 `{"skill": "...", "confidence": ...}`，容错处理：
- 返回非 JSON → 返回 `null`（触发兜底）
- `skill` 不在 4 个有效名称中 → 返回 `null`
- `confidence` 缺失 → 默认 0.5

**理由**：
- 复用现有 DeepSeek Model，不引入新模型或新依赖
- 单轮调用不走 ReAct 循环，无工具决策开销，延迟 ~200-500ms
- JSON 格式便于解析，prompt 强约束"只返回 JSON"

**备选**：用 embedding 相似度匹配 → 否决，需要预计算每个 Skill 的 embedding，实现复杂度高且准确率不一定优于 prompt 分类；用本地小模型 → 否决，Ollama 模型已用于 embedding，加载分类模型增加资源消耗

### 决策 4：intent_rule 种子规则设计

**选择**：Flyway 迁移插入 8 条种子规则：

| skill_name | pattern | match_type | priority |
|------------|---------|------------|----------|
| task-management | `任务,待办,清单,todo` | KEYWORD | 10 |
| task-management | `创建.*任务,新建.*任务,添加.*任务` | REGEX | 15 |
| source-code-analysis | `源码,代码分析,分析.*代码,读.*文件,grep,函数.*实现` | REGEX | 15 |
| source-code-analysis | `项目结构,代码库,源代码` | KEYWORD | 10 |
| knowledge-qa | `知识库,文档.*搜索,搜索.*文档` | KEYWORD | 10 |
| knowledge-qa | `查找.*资料,检索.*知识` | REGEX | 8 |
| general-chat | `你好,今天,天气,讲个笑话` | KEYWORD | 5 |
| general-chat | `帮我写,翻译,总结` | KEYWORD | 3 |

**理由**：
- 高优先级规则（15）覆盖强意图信号（如"创建任务""分析代码"），减少 LLM 调用
- 低优先级规则（3-5）覆盖弱信号（如"帮我写"），避免误匹配
- `general-chat` 也设规则，让闲聊类 query 在规则层直接命中，不浪费 LLM token
- 规则可通过 DB 修改，无需改代码

**备选**：不设 `general-chat` 规则 → 否决，闲聊 query 也要走 LLM 分类，浪费 token

### 决策 5：异步日志写入

**选择**：使用 `@Async` 异步写入 `intent_log` 表。

```java
@Async("intentLogExecutor")
public void logAsync(IntentResult result, String messageSummary,
                     Long userId, Long conversationId) {
    IntentLog log = new IntentLog();
    log.setUserId(userId);
    log.setConversationId(conversationId);
    log.setMessageSummary(messageSummary);
    log.setSource(result.source());
    log.setSkillName(result.skillName());
    log.setConfidence(result.confidence());
    log.setLlmTokens(result.llmTokensUsed());
    log.setLatencyMs(result.latencyMs());
    intentLogMapper.insert(log);
}
```

**线程池配置**：独立线程池 `intentLogExecutor`，核心线程 2，最大 4，队列 100，拒绝策略 `DiscardOldest`。

**理由**：
- 日志写入不阻塞主请求链路（SSE 流），不影响用户体验
- 独立线程池避免与其他 @Async 任务（如知识库导入）争抢线程
- `DiscardOldest` 策略：高负载时丢弃旧日志，不阻塞主流程
- 日志写入失败静默吞掉（仅记录 error 级日志），不影响对话

**备选**：同步写入 → 否决，增加请求延迟；用消息队列 → 否决，过度设计

### 决策 6：与 AgentController 的集成

**选择**：在 `AgentController.chatStream()` 第 128 行之前插入意图识别调用。

```java
// 现有代码 (第 128 行):
ReActAgent agent = agentFactory.create(userId, conversation.getId(),
    buildSystemPrompt(request.mode(), userId));

// 改造后:
String skillName = resolveSkillName(request, userId, conversation.getId());
SkillResolver.ResolvedSkill skill = skillResolver.resolve(skillName, request.mode());
ReActAgent agent = agentFactory.create(userId, conversation.getId(),
    skill.systemPrompt(), skill.toolWhitelist());

private String resolveSkillName(ChatRequest request, Long userId, Long convId) {
    // autoSkill=false 或前端显式传了 skill → 用前端值
    if (Boolean.FALSE.equals(request.autoSkill()) ||
        StringUtils.hasText(request.skill())) {
        return request.skill();
    }
    // autoSkill=true → 调用意图识别引擎
    IntentResult result = intentClassifier.classify(request.message());
    // 异步记录日志
    intentLogService.logAsync(result, truncate(request.message(), 200),
                              userId, convId);
    return result.skillName();
}
```

**SSE 事件**：新增 `intent` 事件，在发送 `conversation` 事件后发送，让前端显示识别结果：

```json
event: intent
data: {"skill": "task-management", "source": "RULE", "latencyMs": 2}
```

**理由**：
- 意图识别在 SSE 流开始前执行，不影响后续 token 流式传输
- `intent` 事件让前端可显示"已识别意图：任务管理"，增强用户感知
- `autoSkill` 默认 `true`，旧前端不传该字段时自动启用

**备选**：将意图识别嵌入 SkillResolver 内部 → 否决，职责混淆，SkillResolver 应只做解析不做分类

### 决策 7：前端自动识别开关

**选择**：`Chat.jsx` 技能选择器旁边新增"自动识别"开关（Ant Design `Switch`）。

```
┌──────────────────────────────────────────┐
│ 🤖 JARVIS   [自动识别 ●ON] [技能: ▼]    │
├──────────────────────────────────────────┤
│ (消息列表)                                │
│  🤖 AI: ...                              │
│  [已识别意图: 任务管理]                  │
├──────────────────────────────────────────┤
│ [TextArea 输入消息...]            [发送] │
└──────────────────────────────────────────┘
```

- 开关默认 ON，对应 `autoSkill: true`
- 开关 OFF 时，下拉选择器变为可选状态（灰色 → 正常）
- 开关 ON 时，下拉选择器变灰且显示"自动"
- SSE 收到 `intent` 事件时，在 AI 回复上方显示意图标签

**理由**：
- 默认 ON 让新用户体验到自动识别的便利
- 提供 OFF 选项给需要精确控制的高级用户
- `SourceAnalysis.jsx` 固定 `autoSkill: false` + `skill: "source-code-analysis"`，不受影响

### 决策 8：ChatRequest 字段扩展

**选择**：

```java
public record ChatRequest(
    String message,
    String conversationId,
    String mode,       // 保留，向后兼容
    String skill,      // 优先于 mode
    Boolean autoSkill  // 新增，默认 true
) {
    public boolean autoSkill() {
        return autoSkill == null || autoSkill;  // null 视为 true
    }
}
```

**理由**：
- `Boolean` 而非 `boolean`，允许 null = 默认 true（旧前端不传该字段时自动启用）
- `autoSkill()` 方法封装 null 处理逻辑，调用方不需关心

## Risks / Trade-offs

- **[LLM 分类延迟]** 慢路径 ~200-500ms 增加首 token 延迟 → 缓解：规则层覆盖 80%+ query，慢路径仅在模糊意图触发；SSE 流先发 `conversation` 和 `intent` 事件，用户在等待时能看到识别结果
- **[LLM 分类准确率]** DeepSeek 可能误分类 → 缓解：置信度阈值 0.7 过滤低质量结果；日志记录便于分析误分类 case；用户可随时关闭自动识别
- **[规则维护成本]** 关键词/正则需持续迭代 → 缓解：种子规则覆盖核心场景；后续可通过日志统计命中率，淘汰低效规则、补充新规则
- **[规则缓存延迟]** 60 秒 TTL 导致规则更新后最多延迟 60 秒生效 → 缓解：管理操作（V2）后可手动刷新缓存接口；60 秒延迟对规则调优场景可接受
- **[异步日志丢失]** 高负载时 `DiscardOldest` 丢日志 → 缓解：日志仅用于统计分析，丢失少量不影响功能；核心对话流程不受影响
- **[LLM token 消耗]** 每次慢路径调用消耗 ~100-200 token → 缓解：规则层减少 LLM 调用频率；`general-chat` 规则过滤闲聊 query；统计 API 监控 token 消耗趋势

## Migration Plan

1. **Flyway 迁移**：创建 `intent_rule` 表 + 8 条种子规则、`intent_log` 表
2. **新增后端类**：`IntentClassifier` 接口、`RuleBasedClassifier`、`LLMClassifier`、`IntentClassifierChain`、`IntentResult`、`IntentRule` 实体 + Mapper、`IntentLog` 实体 + Mapper、`IntentLogService`（@Async 日志）、`IntentController`（统计 API）
3. **修改后端类**：`ChatRequest` 新增 `autoSkill` 字段、`AgentController.chatStream()` 插入分类调用 + `intent` SSE 事件
4. **配置新增**：`application.properties` 新增 `intent.llm.timeout-seconds=3`、`intent.llm.confidence-threshold=0.7`、`intent.rule.cache-ttl-seconds=60`
5. **修改前端**：`client.js` 新增 `intentApi` 模块 + `streamChat()` 支持 `autoSkill` 参数 + 处理 `intent` 事件、`Chat.jsx` 新增"自动识别"开关 + 意图标签展示
6. **启动验证**：重启后端，发送不同意图的消息验证路由正确，检查 `intent_log` 表有记录
7. **回滚策略**：删除 Flyway 迁移文件中的种子数据（`DELETE FROM intent_rule`）、将 `autoSkill` 默认改为 `false`、注释 `AgentController` 中的分类调用 → 回退到手动 Skill 选择模式
