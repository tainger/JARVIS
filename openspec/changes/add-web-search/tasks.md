## 1. 配置与抽象层

- [x] 1.1 在 `src/main/resources/application.properties` 新增配置项：`websearch.provider`（默认 bing-html）、`websearch.duckduckgo.url`、`websearch.bing.api-key`、`websearch.max-results`（默认 5）、`websearch.max-calls-per-conversation`（默认 3），验证：`mvn spring-boot:run` 启动无报错
- [x] 1.2 新建 `src/main/java/com/example/jarvis/http/SearchResult.java` 记录类（`record SearchResult(String title, String snippet, String url)`），验证：编译通过
- [x] 1.3 新建 `src/main/java/com/example/jarvis/http/WebSearchClient.java` 接口，提供 `List<SearchResult> search(String query, int maxResults)` 方法签名，验证：编译通过
- [x] 1.4 新建 `src/main/java/com/example/jarvis/http/WebSearchConfig.java` 配置绑定类（`@ConfigurationProperties(prefix = "websearch")`），验证：Spring 启动时配置绑定成功

## 2. 搜索后端实现

- [x] 2.1 新建 `DuckDuckGoSearchClient`（实现 `WebSearchClient`），支持 Lite/HTML/API 三端点自动回退；DuckDuckGo 已启用 CAPTCHA 反爬，当前作为 fallback
- [x] 2.2 新建 `BingHtmlSearchClient`（实现 `WebSearchClient`），通过解析 Bing 搜索结果 HTML 提取标题+摘要+URL，免费无需 API Key，验证：`search("Java 21 virtual threads", 5)` 返回 5 条结果
- [x] 2.3 HTML 清洗：去 HTML 标签、HTML 实体解码、控制字符过滤，验证：返回文本无 HTML 标签残留
- [x] 2.4 URL 安全校验：只保留 `http://` 或 `https://` 开头的 URL，拒绝 `javascript:` 和 `data:`
- [x] 2.5 新建 `BingSearchClient`（调用 Bing Search API，需 Key），作为 provider=bing 的高级选项
- [x] 2.6 新建 `WebSearchClientFactory`，根据 `websearch.provider` 选择搜索后端：`bing-html`（默认）/`duckduckgo`/`bing`（需 Key）

## 3. WebSearchTools 工具

- [x] 3.1 新建 `WebSearchTools.java`，`@Tool webSearch(query)` 方法调用 `client.search()` 格式化返回
- [x] 3.2 频率限制：`AtomicInteger callCount` 每次递增，超过 `maxCalls`（默认 3）时返回限制提示，验证：第 4 次调用返回"已达到搜索次数限制"
- [x] 3.3 结果格式化：`搜索 "query" 找到 N 条结果：` + `[编号] 标题\n URL: xxx\n 摘要: xxx`，每条摘要截断 300 字符，总文本截断 2000 字符
- [x] 3.4 错误处理：HTTP 超时/异常返回友好提示，Agent 不中断推理

## 4. AgentFactory 集成

- [x] 4.1 修改 `AgentFactory.java`：构造函数注入 `WebSearchTools`，`create()` 中调用 `resetCallCount()` 重置搜索计数
- [x] 4.2 修改 `AgentScopeConfig.java`：`agentscopeToolkit()` 中注册 `WebSearchTools`
- [x] 4.3 `webSearch` 工具通过共享 Toolkit 注册到所有使用 `all` 白名单的技能

## 5. 技能种子数据更新

- [x] 5.1 新建 Flyway V5 迁移，创建 `agent_skill` 表 + 4 个内置技能种子数据，`source-code-analysis` 和 `knowledge-qa` 的 `tool_whitelist` 已含 `WebSearchTools`
- [x] 5.2 更新 `application.properties` 中 `sys-prompt` 和 `source-analysis-sys-prompt`，增加 `webSearch` 搜索引导文本
- [x] 5.3 `general-chat` 技能的 `tool_whitelist=all`（已含 WebSearchTools），`system_prompt` 已含搜索引导

## 6. 前端展示

- [x] 6.1 修改 `Chat.jsx`：新增可折叠"网络搜索"面板，展示搜索查询词和结果列表
- [x] 6.2 `tool_call` 显示"🔍 搜索中..."，`tool_result` 显示搜索结果摘要
- [x] 6.3 修改 `SourceAnalysis.jsx`：同步新增"网络搜索"面板
- [x] 6.4 降级处理：无 webSearch trace 步骤时不显示面板

## 7. 端到端验证

- [x] 7.1 启动后端（默认 Bing HTML），登录后在 Chat 页面问"React 19 的新特性"，验证：Agent 调用 webSearch，返回 5 条结果/查询，Agent 基于搜索结果回答
- [x] 7.2 连续搜索达到 3 次后，第 4 次返回"已达到搜索次数限制"
- [x] 7.3 开始新对话后搜索计数已重置
- [x] 7.4 `agent_trace` 表中有 `tool_name=webSearch` 的记录
- [x] 7.5 编译通过（`mvn compile`）
- [x] 7.6 前端构建通过（`cd web && npx vite build`）
