## Purpose

让 AI Agent 在本地知识库和源码无法回答时，自主搜索互联网获取最新信息，基于搜索结果摘要总结回答，突破本地知识覆盖面限制，减少幻觉。

## ADDED Requirements

### Requirement: WebSearchTools 工具

系统 SHALL 新增 `WebSearchTools` 工具类，提供 `webSearch(query)` 一个 `@Tool` 方法，Agent 在 ReAct 推理中可自主调用搜索互联网。方法 MUST 返回格式化文本（编号+标题+URL+摘要），最多 5 条结果，每条摘要最多 300 字符，总文本不超过 2000 字符。

#### Scenario: Agent 搜索网页并基于结果回答

- **WHEN** 用户问"Spring Boot 3.2 有什么新特性"且知识库无相关内容
- **THEN** Agent 调用 `webSearch(query="Spring Boot 3.2 new features")`
- **AND** 工具返回最多 5 条搜索结果（标题+摘要+URL）
- **AND** Agent 基于搜索结果摘要总结回答
- **AND** Agent 在回答中引用来源 URL

#### Scenario: 搜索无结果时返回提示

- **WHEN** Agent 调用 `webSearch(query="xyz123nonexistent")` 且搜索 API 返回 0 条结果
- **THEN** 工具返回"网络搜索未找到相关结果。"
- **AND** Agent 基于已有信息回答或建议用户换关键词

#### Scenario: 搜索 API 超时

- **WHEN** 搜索 API 请求超过 10 秒未响应
- **THEN** 工具返回"网络搜索超时，请稍后重试或基于已有信息回答。"
- **AND** Agent 不因搜索超时而中断推理

#### Scenario: 搜索结果摘要截断

- **WHEN** 某条搜索结果的摘要超过 300 字符
- **THEN** 摘要被截断为前 297 字符 + "…"
- **AND** 总返回文本不超过 2000 字符

### Requirement: 多搜索后端可配置

系统 SHALL 通过 `websearch.provider` 配置选择搜索后端。V1 MUST 支持两种后端：

- `duckduckgo`（默认）：DuckDuckGo HTML 端点，免费、无需 API Key
- `bing`：Bing Search API，需要 `websearch.bing.api-key` 配置

系统 SHALL 通过 `WebSearchClient` 接口抽象搜索后端，统一返回 `List<SearchResult(title, snippet, url)>`。

#### Scenario: 默认使用 DuckDuckGo

- **WHEN** `application.properties` 中未配置 `websearch.provider`
- **THEN** 系统使用 DuckDuckGo HTML 后端
- **AND** 无需任何 API Key 配置

#### Scenario: 切换到 Bing API

- **WHEN** 配置 `websearch.provider=bing` 且 `websearch.bing.api-key` 有效
- **THEN** 系统使用 Bing Search API 后端
- **AND** 返回结果为 JSON 格式的结构化数据

#### Scenario: Bing 配置缺少 API Key

- **WHEN** 配置 `websearch.provider=bing` 但 `websearch.bing.api-key` 为空
- **THEN** 系统启动时回退到 DuckDuckGo 后端
- **AND** 日志记录 WARN 级别警告"Bing API Key 未配置，回退到 DuckDuckGo"

### Requirement: 搜索频率限制

系统 SHALL 限制单次对话的搜索调用次数。默认限制为 3 次。限制次数 MUST 可通过 `websearch.max-calls-per-conversation` 配置。每次对话的计数独立（不跨对话累积）。

#### Scenario: 达到搜索频率上限

- **WHEN** Agent 在同一对话中第 4 次调用 `webSearch()`（默认限制 3 次）
- **THEN** 工具返回"已达到本次对话的最大搜索次数限制（3 次）。请基于已有信息回答。"
- **AND** 不执行实际搜索 API 调用

#### Scenario: 新对话重置搜索计数

- **WHEN** 用户开始新对话
- **THEN** 搜索调用计数重置为 0
- **AND** 可再次搜索最多 3 次

### Requirement: 搜索结果安全处理

系统 SHALL 对搜索结果进行安全清洗：

- HTML 标签 MUST 被去除（只保留纯文本）
- HTML 实体 MUST 被解码（`&amp;` → `&` 等）
- URL MUST 只包含 `http://` 或 `https://` 协议，拒绝 `javascript:` 和 `data:` 协议
- 控制字符 MUST 被过滤

#### Scenario: HTML 标签被去除

- **WHEN** DuckDuckGo HTML 返回 `<a class="result__a" href="...">Spring Boot</a>`
- **THEN** 提取后的标题为纯文本"Spring Boot"（无 HTML 标签）

#### Scenario: 恶意 URL 被过滤

- **WHEN** 搜索结果中某个 URL 为 `javascript:alert(1)`
- **THEN** 该结果被过滤掉，不出现在返回文本中

### Requirement: 前端搜索结果面板

前端 SHALL 在 assistant 消息气泡内新增可折叠的"网络搜索"面板，展示搜索查询词和结果列表。面板 MUST 在流式过程中实时更新（通过 `tool_call`/`tool_result` SSE 事件触发）。

#### Scenario: 搜索结果面板展示

- **WHEN** Agent 调用 `webSearch(query="Spring Boot 3.2")` 并收到 5 条结果
- **THEN** assistant 消息中显示"网络搜索"面板
- **AND** 面板展示查询词"Spring Boot 3.2"
- **AND** 面板列出 5 条结果（标题可点击跳转 + 摘要 + URL）
- **AND** 外链以 `rel="noopener noreferrer"` 安全方式打开

#### Scenario: 无搜索结果时面板不显示

- **WHEN** Agent 未调用 `webSearch` 工具
- **THEN** assistant 消息中不显示"网络搜索"面板

### Requirement: 技能体系集成

系统 SHALL 在内置技能种子数据中为 3 个技能的 `tool_whitelist` 添加 `WebSearchTools`：

- `general-chat`：`all`（已包含所有工具）
- `source-code-analysis`：`SourceCodeTools,KnowledgeSearchTools,WebSearchTools`
- `knowledge-qa`：`KnowledgeSearchTools,WebSearchTools`
- `task-management`：不加（任务管理场景不需要搜索）

系统提示词 SHALL 增强：当知识库/源码无结果时引导 Agent 使用 `webSearch`。

#### Scenario: 通用对话技能可搜索

- **WHEN** 用户在"通用对话"技能下问超出知识库的问题
- **THEN** Agent 可调用 `webSearch` 工具

#### Scenario: 任务管理技能不可搜索

- **WHEN** 用户在"任务管理"技能下对话
- **THEN** Agent 的 Toolkit 中不包含 `WebSearchTools`
- **AND** Agent 无法调用 `webSearch`

### Requirement: 向后兼容

系统 SHALL 在未配置任何 `websearch.*` 配置项时正常工作（使用 DuckDuckGo 默认值）。现有对话和技能不受影响。

#### Scenario: 零配置启动

- **WHEN** `application.properties` 中无任何 `websearch.*` 配置
- **THEN** 系统使用 DuckDuckGo 后端，最大 5 条结果，最多 3 次/对话
- **AND** 后端启动无报错
