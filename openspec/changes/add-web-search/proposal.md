# Proposal: Add Web Search

## Why

当前 JARVIS 的 Agent 信息来源仅限于两个闭环：

1. **本地知识库**：RAG 检索覆盖上传的 Markdown/Txt 文档（36 个 chunk），覆盖面有限——用户问"Spring Boot 3.2 有什么新特性"或"DeepSeek 最新定价"时，知识库无相关内容，Agent 只能泛泛而谈或承认不知道。
2. **本地源码**：SourceCodeTools 只能读 `repo/` 下已拉取的项目，无法获取项目文档、Issue 讨论、Stack Overflow 问答等外部信息。

当用户的问题超出本地知识库和源码覆盖范围时，Agent 的回答质量断崖式下降——要么编造（幻觉），要么回答"我不知道"。这严重制约了 JARVIS 作为技术支持助手的实用性。

需要一个 `webSearch` 工具，让 ReAct Agent 在知识库和源码都无法回答时，主动搜索互联网获取最新信息，基于真实搜索结果总结回答。

## What Changes

- **新增 `WebSearchTools` 工具集**：提供 `webSearch(query)` 一个 `@Tool` 方法，Agent 在 ReAct 推理中自主决定何时搜索网页。调用搜索 API 获取结果（标题+摘要+URL），格式化后返回给 Agent 作为上下文。
- **多搜索后端可配置**：通过 `application.properties` 配置搜索后端（`websearch.provider`），V1 支持 DuckDuckGo HTML（免费、无需 API Key）和 Bing Search API（需 Key、结果质量更高）。默认 DuckDuckGo，零配置可用。
- **搜索结果安全处理**：URL 白名单过滤（只返回 http/https）、HTML 标签清洗（防 XSS 注入到 Agent 上下文）、结果数限制（默认 5 条）、摘要长度截断（每条 300 字符）。
- **搜索频率限制**：单次对话最多搜索 3 次（防 Agent 陷入搜索循环），通过 AgentController 传递限制到工具实例。
- **SSE 搜索事件**：新增 `web_search` SSE 事件帧，实时推送搜索查询词和结果摘要到前端，用户能看到 Agent 正在搜什么、搜到了什么。
- **前端搜索结果展示**：Chat.jsx 和 SourceAnalysis.jsx 的 assistant 消息中新增"网络搜索"面板（可折叠），展示搜索查询词 + 结果列表（标题+摘要+外链）。
- **技能体系集成**：在 `agent_skill` 种子数据中，`general-chat` 和 `knowledge-qa` 技能的 `tool_whitelist` 增加 `WebSearchTools`；`source-code-analysis` 技能也加入（源码问题也可能需要搜文档）。
- **RAG 降级链路**：Agent 系统提示词增强——"当知识库检索无相关结果时，使用 webSearch 工具搜索互联网"。

## Capabilities

### New Capabilities

- `web-search`: Agent 可自主搜索互联网获取最新信息，基于搜索结果摘要总结回答，突破本地知识库覆盖面限制

### Modified Capabilities

- `skill-integration`: 内置技能的 `tool_whitelist` 增加 `WebSearchTools`（add-skill-integration 变更的种子数据需同步更新）
- `agent-observability`: 推理轨迹中记录 web_search 步骤，`tool_call` 的 args 含搜索查询词，`tool_result` 的 summary 含结果数

## Impact

- **代码**：
  - 新增 `src/main/java/com/example/jarvis/tool/WebSearchTools.java`（`@Component` + `@Tool`）
  - 新增 `src/main/java/com/example/jarvis/http/WebSearchClient.java`（HTTP 客户端 + HTML 解析）
  - 修改 `src/main/java/com/example/jarvis/config/AgentScopeConfig.java`：Toolkit 注册 `WebSearchTools`
  - 修改 `src/main/java/com/example/jarvis/config/AgentFactory.java`：注入 `WebSearchTools` Bean，加入白名单解析
  - 修改 `src/main/java/com/example/jarvis/controller/AgentController.java`：`sendDelta()` 增加搜索结果 SSE 事件处理（可选，V1 可只走 tool_call/tool_result 通道）
  - 修改 `web/src/pages/Chat.jsx`：新增搜索结果面板渲染
  - 修改 `web/src/pages/SourceAnalysis.jsx`：同上
- **配置**：`application.properties` 新增 `websearch.provider`、`websearch.bing.api-key`、`websearch.max-results`、`websearch.max-calls-per-conversation` 配置项
- **依赖**：零新增第三方依赖（Java HttpClient + Jsoup 已有 / 或正则解析）
- **安全**：搜索 API 调用在服务端执行（非浏览器端），无 SSRF 风险（只调搜索 API 固定域名）；HTML 结果清洗防注入；外链 URL 在前端 `rel="noopener noreferrer"` 打开
- **性能**：DuckDuckGo 搜索延迟 ~1-3s，Agent 可能需 1-2 次搜索，总延迟增加 2-6s；搜索结果作为工具返回值注入 Agent 上下文，增加 ~500-1500 tokens
- **不受影响**：RAG 检索链路、知识库导入、任务管理、评测系统、记忆系统均不变
