## Why

当前 JARVIS 的 AI 对话能力只接入了任务管理（TaskTools）和知识库检索（KnowledgeSearchTools）两类工具。面对技术支持/答疑场景下的真实需求——用户贴一段报错堆栈或问"这个接口的鉴权逻辑在哪"——Agent 没有任何途径接触项目源码，只能泛泛而谈或编造答案。现有 RAG 知识库面向运营文档（手册/简历），源码不是文档：分块会破坏语法结构、文件频繁变动导致索引失效、代码语义检索与文档检索目标不同，强行混入会两边都做不好。需要一条独立的"读源码"工具链，让 ReAct Agent 自主按需读取源码文件来解答技术问题。

## What Changes

- **新增 `SourceCodeTools` AgentScope 工具集**：提供 `readFile(path)` / `listFiles(dir)` / `grepCode(pattern, dir)` 三个 `@Tool` 方法，Agent 在 ReAct 推理中自主决定何时读取哪些源码文件
- **源码读取安全沙箱**：所有路径约束在项目根目录（`user.dir`）下，拒绝路径穿越（`../`）与符号链接逃逸；单次读取限制最大行数/字节数，避免大文件撑爆上下文
- **AgentScope Toolkit 注册**：在 `AgentScopeConfig` 中把 `SourceCodeTools` 注册进现有 Toolkit，与 TaskTools、KnowledgeSearchTools 并列
- **聊天系统提示增强**：Agent 系统提示词补充"当用户问题涉及本项目代码、报错、接口实现时，优先使用源码工具定位并基于真实代码回答"
- **前端新增独立"源码分析"页面**：在侧边栏菜单新增"源码分析"入口，独立路由 `/source-analysis`，复用 Chat.jsx 的 SSE 流式逻辑与消息渲染但使用专用系统提示（面向技术支持/答疑场景），与通用 AI 对话页区分

## Capabilities

### New Capabilities

- `source-code-analysis`: Agent 可自主读取项目源码文件（读文件/列目录/搜索代码）来解答技术问题，含路径安全约束与读取大小限制

### Modified Capabilities

<!-- 无：现有 RAG / 任务 / 聊天行为不变，本变更只外挂源码分析工具 -->

## Impact

- **代码**：新增 `src/main/java/com/example/jarvis/tool/SourceCodeTools.java`；修改 `config/AgentScopeConfig.java`（注册新工具）；可能调整 `AgentScopeConfig` 中的 system prompt
- **依赖**：零新增第三方依赖（纯 Java NIO 文件操作）
- **安全**：新增文件路径校验层，防止 Agent 越权读取项目外文件；需审计 `SecurityConfig` 确认 `/api/agent/**` 已有认证保护（现有 Chat 接口已要求 JWT）
- **前端**：新增 `web/src/pages/SourceAnalysis.jsx`（复用 Chat.jsx 的 SSE 流式与消息渲染逻辑，使用专用技术支持提示词）；修改 `App.jsx` 加路由 `/source-analysis`；修改 `AdminLayout.jsx` 侧边栏 menuItems 加"源码分析"菜单项（图标用 `CodeOutlined`）
- **数据**：无新表、无 DB 迁移
- **不受影响**：RAG 检索链路、知识库导入、任务管理、评测系统均不变
