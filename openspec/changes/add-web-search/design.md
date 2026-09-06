# Design: Add Web Search

## Context

### 现状分析

| 维度 | 现状 | 缺口 |
|------|------|------|
| 信息来源 | 知识库（36 chunk）+ 源码（repo/nacos） | 缺互联网搜索能力 |
| Agent 工具 | 7 个 @Tool（Task 3 + SourceCode 3 + Knowledge 1） | 缺 webSearch 工具 |
| 降级链路 | RAG 无命中 → Agent 承认"不知道" | 缺"不知道时搜网页"的回退 |
| 时效性 | 知识库为静态文档，不更新 | 缺获取最新信息（版本号、定价、新闻）的途径 |
| 前端展示 | 知识库来源面板（SourceList）+ 推理路径面板 | 缺搜索结果面板 |

### 关键代码位置

- `AgentScopeConfig.java` 第 35-45 行：`agentscopeToolkit()` — 注册工具
- `AgentFactory.java` 第 30-50 行：`create()` — 注入工具到 Agent
- `KnowledgeSearchTools.java`：单 `@Tool` 方法，返回格式化文本 — 可参考的模式
- `SourceCodeTools.java` 第 30-35 行：常量限制（MAX_LINES/MAX_BYTES） — 可参考的限流模式
- `Chat.jsx` SSE 事件处理：`tool_call`/`tool_result` 事件已支持

### 搜索 API 对比

| 维度 | DuckDuckGo HTML | DuckDuckGo API | Bing Search API |
|------|----------------|----------------|-----------------|
| 费用 | 免费 | 免费 | 1000 次/月免费，后 $3/1K 次 |
| API Key | 不需要 | 不需要 | 需要 |
| 结果质量 | 中（HTML 解析） | 低（Instant Answer，覆盖面窄） | 高（结构化 JSON） |
| 稳定性 | 中（HTML 结构可能变） | 低（经常无结果） | 高（正式 API） |
| 速率限制 | 宽松 | 宽松 | 1000 次/月 |
| 集成复杂度 | 低（HttpClient + 正则） | 极低（JSON API） | 低（JSON API + Key） |

## Goals / Non-Goals

**Goals:**
- Agent 在知识库无相关结果时，自主调用 `webSearch` 搜索互联网
- 零配置可用（DuckDuckGo HTML 默认，无需 API Key）
- 搜索结果格式化为 Agent 可消费的文本（标题+摘要+URL）
- 前端展示搜索结果面板，用户可见 Agent 搜了什么
- 搜索频率限制，防 Agent 陷入搜索循环

**Non-Goals:**
- 不做网页全文抓取（V1 只用搜索 API 返回的摘要，不爬网页内容）
- 不做搜索结果缓存（V1 每次实时搜索，后续可加）
- 不做多语言搜索翻译（V1 搜索词原样传递）
- 不做搜索结果排序调优（V1 用搜索 API 的默认排序）
- 不做图片/视频/新闻搜索（V1 只做网页搜索）

## Decisions

### 决策 1：默认 DuckDuckGo HTML，可切换 Bing API

**选择**：通过 `websearch.provider` 配置选择搜索后端，默认 `duckduckgo`（零配置可用）。

```properties
# 搜索后端：duckduckgo（默认，免费）或 bing（需 API Key）
websearch.provider=${WEBSEARCH_PROVIDER:duckduckgo}
# DuckDuckGo 搜索 URL（HTML 端点）
websearch.duckduckgo.url=${WEBSEARCH_DD_URL:https://html.duckduckgo.com/html/?q=}
# Bing API Key（provider=bing 时必填）
websearch.bing.api-key=${WEBSEARCH_BING_API_KEY:}
# 搜索结果数（默认 5）
websearch.max-results=${WEBSEARCH_MAX_RESULTS:5}
# 单次对话最大搜索次数（默认 3）
websearch.max-calls-per-conversation=${WEBSEARCH_MAX_CALLS:3}
```

**理由**：
- DuckDuckGo HTML 免费、无需 Key，零配置开箱即用，适合个人项目和开发环境
- Bing API 结果质量更高、结构化 JSON，适合生产环境，只需配置 Key 即可切换
- 配置驱动切换，不修改代码

**备选**：只用 Bing API → 否决，需要用户注册申请 Key 才能用；只用 DuckDuckGo API → 否决，Instant Answer API 覆盖面太窄

### 决策 2：WebSearchClient 抽象层 + 策略模式

**选择**：新建 `WebSearchClient` 抽象类，两个实现：

```
WebSearchClient (interface)
  ├── DuckDuckGoSearchClient   — HttpClient 调 DDG HTML，正则解析结果
  └── BingSearchClient         — HttpClient 调 Bing API，JSON 解析结果
```

统一返回 `List<SearchResult(title, snippet, url)>`：

```java
public record SearchResult(String title, String snippet, String url) {}

public interface WebSearchClient {
    List<SearchResult> search(String query, int maxResults);
}
```

`WebSearchTools` 注入 `WebSearchClient`，不关心具体实现。

**理由**：
- 策略模式让切换搜索后端只需改配置，不改代码
- 统一 `SearchResult` 数据结构，`WebSearchTools` 的格式化逻辑只写一份
- 后续可轻松加新的搜索后端（如 Google Custom Search）

### 决策 3：搜索结果格式化为 Agent 可消费文本

**选择**：`WebSearchTools.webSearch()` 返回格式化文本：

```
网络搜索到 5 条相关结果：

[1] Spring Boot 3.2 Release Notes
    URL: https://spring.io/blog/2023-11-23/spring-boot-3-2
    摘要: Spring Boot 3.2 introduces virtual threads, native compilation improvements...

[2] What's New in Spring Boot 3.2
    URL: https://www.baeldung.com/spring-boot-3-2
    摘要: A quick guide to the new features in Spring Boot 3.2...

[3] ...
```

**限制**：
- 每条摘要截断 300 字符
- 最多返回 5 条结果
- 总返回文本 < 2000 字符（防撑爆 Agent 上下文）

**理由**：
- 编号 + 标题 + URL + 摘要的格式与 `KnowledgeSearchTools` 的返回格式一致，LLM 已熟悉此模式
- 摘要截断保证上下文 token 不膨胀
- URL 保留让 Agent 可以在回答中引用来源链接

### 决策 4：单次对话搜索频率限制

**选择**：`WebSearchTools` 实例维护一个 `AtomicInteger callCount`，每次 `webSearch()` 调用时递增并检查上限。

```java
@Component
@Scope("prototype")  // 每次对话请求创建新实例
public class WebSearchTools {
    private final AtomicInteger callCount = new AtomicInteger(0);
    private int maxCalls = 3;

    @Tool(description = "Search the web for current information...")
    public String webSearch(@ToolParam(name = "query", ...) String query) {
        if (callCount.incrementAndGet() > maxCalls) {
            return "已达到本次对话的最大搜索次数限制（" + maxCalls + " 次）。"
                 + "请基于已有信息回答，或建议用户提供更具体的信息。";
        }
        // ... 执行搜索
    }
}
```

**问题与解决**：
- `WebSearchTools` 当前是 `@Component`（单例），`callCount` 会跨对话累积
- **解决方案**：改为 `@Scope("prototype")` 原型作用域，每次注入时创建新实例
- 但 AgentScopeConfig 的 `agentscopeToolkit()` Bean 是单例的，注入的是同一个 `WebSearchTools` 实例

**替代方案（更可靠）**：不依赖 Spring 作用域，而是在 `AgentFactory.create()` 中为每次对话创建新的 `WebSearchTools` 实例并注册到 per-request Toolkit：

```java
// AgentFactory.create() 中
WebSearchTools webSearchTools = new WebSearchTools(webSearchClient, maxCalls);
toolkit.registerTool(webSearchTools);
```

这样每次对话的 `callCount` 独立计数，不受 Spring 作用域影响。

**理由**：
- 防止 Agent 反复搜索同一个问题浪费 API 调用
- 3 次上限足够覆盖"搜索 → 看结果 → 换关键词再搜"的合理链路
- 限制在 AgentFactory 层面注入，不依赖 Spring 作用域

### 决策 5：HTML 清洗与 URL 安全

**选择**：DuckDuckGo HTML 返回结果中的 HTML 标签和潜在恶意内容需要清洗。

```java
private String cleanHtml(String raw) {
    // 1. 去 HTML 标签（保留纯文本）
    String text = raw.replaceAll("<[^>]+>", "");
    // 2. HTML 实体解码
    text = text.replace("&amp;", "&").replace("&lt;", "<")
              .replace("&gt;", ">").replace("&quot;", "\"")
              .replace("&#39;", "'");
    // 3. 控制字符过滤
    text = text.replaceAll("[\\x00-\\x1f\\x7f]", "");
    return text.trim();
}

private boolean isSafeUrl(String url) {
    return url != null
        && (url.startsWith("http://") || url.startsWith("https://"))
        && !url.contains("javascript:")
        && !url.contains("data:");
}
```

**理由**：
- DuckDuckGo HTML 端点返回 `<a class="result__a" href="...">标题</a>` 等标签，需要解析提取
- HTML 实体解码防止 `&lt;script&gt;` 等内容注入到 Agent 上下文
- URL 安全校验防止 `javascript:` 等非 HTTP 协议的链接

**备选**：用 Jsoup 解析 HTML → 可选，但 Jsoup 已在依赖树中（Spring Boot starter-web 传递依赖），如果可用则更可靠

### 决策 6：SSE 搜索结果事件

**选择**：搜索结果通过现有 `tool_call` + `tool_result` SSE 事件传输，不新增事件类型。

- `tool_call` 事件：`{"step": 2, "tool": "webSearch", "args": "{\"query\":\"Spring Boot 3.2 features\"}"}`
- `tool_result` 事件：`{"step": 2, "tool": "webSearch", "summary": "5 results found", "truncated": false}`

**前端展示**：在推理路径面板中，`webSearch` 工具的步骤展示为搜索图标 + 查询词 + 结果数。额外在 assistant 消息中新增独立的"网络搜索"面板（类似知识库来源面板），展示完整结果列表。

**理由**：
- 复用已有 SSE 事件通道（tool_call/tool_result），不增加前端事件处理逻辑
- "网络搜索"面板独立于推理路径面板，提供更友好的结果展示（标题可点击跳转）
- 与知识库来源面板（SourceList）形成"本地来源 + 网络来源"的完整来源视图

### 决策 7：系统提示词增强

**选择**：在 `general-chat` 技能的系统提示词中增加搜索引导：

```
When the knowledge base search returns no relevant results, use the webSearch tool 
to find current information on the web. Always cite the source URL in your answer 
when using web search results.
```

**其他技能提示词**：
- `knowledge-qa`：增加"知识库无结果时使用 webSearch 搜索"
- `source-code-analysis`：增加"源码分析时可使用 webSearch 搜索相关文档和 Issue 讨论"
- `task-management`：不加入搜索引导（任务管理场景不需要）

**理由**：
- 不强制 Agent 每次都搜索，只在知识库/源码无法回答时才搜
- "cite the source URL"要求 Agent 在回答中引用来源链接，增强可信度
- 按技能差异化引导，任务管理场景不需要搜索

## Risk Mitigation

- **[搜索 API 不可用]** DuckDuckGo HTML 端点偶尔超时或改版 → 缓解：HTTP 请求设 10s 超时；失败时返回错误信息让 Agent 基于已有信息回答；可切换到 Bing API
- **[HTML 解析失效]** DuckDuckGo HTML 结构变更导致解析失败 → 缓解：解析逻辑用正则容错设计（多个备选 pattern）；解析为空时返回"搜索失败"而非报错
- **[搜索结果质量差]** 搜索词不精确导致无关结果 → 缓解：Agent 可通过 ReAct 推理换关键词再搜（最多 3 次）
- **[Agent 搜索循环]** Agent 反复搜索同一问题 → 缓解：单次对话搜索频率限制 3 次（决策 4）
- **[上下文 token 膨胀]** 搜索结果文本过长 → 缓解：每条摘要 300 字符截断，总文本 < 2000 字符
- **[HTML 注入]** 搜索结果含恶意 HTML → 缰解：HTML 清洗 + URL 安全校验（决策 5）
- **[SSRF]** Agent 传入非搜索 API 的 URL → 缓解：搜索只调固定 API 域名，不接受用户控制的 URL 参数
- **[延迟]** 搜索增加对话延迟 → 缓解：10s 超时 + 频率限制 3 次，最坏增加 30s 延迟（可接受）

## Migration Plan

1. 新增 `application.properties` 搜索相关配置项
2. 新建 `WebSearchClient` 接口 + `SearchResult` 记录类
3. 新建 `DuckDuckGoSearchClient` 实现（HttpClient + HTML 解析）
4. 新建 `BingSearchClient` 实现（HttpClient + JSON 解析）
5. 新建 `WebSearchTools` 工具类（`@Tool webSearch` + 频率限制）
6. 修改 `AgentScopeConfig`：注入 `WebSearchClient`，Toolkit 注册 `WebSearchTools`
7. 修改 `AgentFactory`：per-request 创建 `WebSearchTools` 实例（频率限制独立）
8. 修改 `agent_skill` 种子数据：3 个技能的 `tool_whitelist` 加入 `WebSearchTools`（Flyway V5.1 补丁迁移或直接修改 V5 种子数据，取决于 V5 是否已执行）
9. 修改前端 `Chat.jsx` + `SourceAnalysis.jsx`：新增网络搜索面板
10. 重启后端 + 前端，验证搜索功能可用
