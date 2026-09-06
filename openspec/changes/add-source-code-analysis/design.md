## Context

现有 AgentScope Toolkit 已注册 TaskTools（任务 CRUD）与 KnowledgeSearchTools（知识库检索），注册点在 [AgentScopeConfig.java](file:///Users/rocky/study/profile/Markdown-Resume/repo/JARVIS/src/main/java/com/example/jarvis/config/AgentScopeConfig.java#L282-L290) 的 `agentscopeToolkit` Bean。新工具需在此注册，模式与现有两个工具一致（`@Component` + `@Tool` 注解）。

源码分析与 RAG 知识库是两条独立链路：知识库面向运营文档（导入→向量化→检索→注入），源码面向技术答疑（Agent 实时读取）。后者不需要索引、不需要 embedding、不需要入库——文件是活的，读当前磁盘状态即可。

## Goals / Non-Goals

**Goals:**
- Agent 能通过 3 个工具（readFile / listFiles / grepCode）自主探索项目源码
- 路径安全沙箱确保 Agent 无法读取项目外文件
- 读取大小限制防止大文件撑爆 LLM 上下文窗口
- 零新增第三方依赖，纯 Java NIO 实现

**Non-Goals:**
- 不构建源码向量索引（源码不进 RAG 知识库）
- 不做 AST 解析/语义分析（V1 纯文本读取 + 正则搜索，后续可加）
- 不支持多项目/外部仓库（V1 仅限当前 JARVIS 项目根目录）
- V1 不接入会话持久化（消息历史为本地 state，刷新即清空；记忆系统由 add-agent-memory change 独立实现）

## Decisions

### 决策 1：新增 `SourceCodeTools` 而非扩展现有 RAG

**选择**：新建 `tool/SourceCodeTools.java`，模式同 `TaskTools`。

**理由**：
- 源码不是文档，强行进 RAG 会破坏语法结构、索引随代码变动失效
- Agent 自主读文件天然支持"先搜再读、多跳探索"，比固定检索更灵活
- 工具职责单一，符合现有 Toolkit 注册模式

**备选**：扩展 KnowledgeService 增加"源码索引"能力 → 否决，污染 RAG 语义且维护成本高

### 决策 2：路径沙箱用规范化路径前缀校验

**选择**：所有路径先 `Paths.get(baseDir, relativePath).normalize()`，再 `startsWith(baseDir)` 校验，拒绝 `../` 穿越与符号链接逃逸。

**理由**：
- Java NIO `normalize()` 会解析 `..`，配合 `startsWith` 可有效防穿越
- 不跟随符号链接（`NOFOLLOW_LINKS`），避免软链逃逸
- 纯 JDK 实现，零依赖

**备选**：用第三方安全库（如 Apache Commons IO）→ 否决，过度依赖且核心逻辑简单

### 决策 3：读取限制用行数 + 字节数双阈值

**选择**：默认 200 行 / 50KB，截断时提示总行数，Agent 可指定 startLine 分页。

**理由**：
- 行数对人类友好，Agent 能理解"前 200 行"
- 字节数兜底防止超长单行（minified JS、大 JSON）撑爆上下文
- 分页机制让 Agent 能读完大文件，不会因截断丢失关键信息

### 决策 4：grepCode 用 Java 内置正则，不引入 ripgrep

**选择**：遍历项目内文本文件，用 `java.util.regex` 匹配，限制结果数。

**理由**：
- 项目代码量适中（Java + JSX + 配置），纯 Java 遍历性能可接受
- 不依赖外部二进制（ripgrep），保持容器化部署简洁
- 后续如需提速可换 ripgrep subprocess，工具接口不变

### 决策 5：系统提示词补充引导 + 前端独立页面

**选择**：后端在 `AgentScopeConfig` 的系统提示词中补充源码工具使用引导；前端新增独立"源码分析"页面（路由 `/source-analysis`），复用 Chat.jsx 的 SSE 流式逻辑但使用专用技术支持提示。

**理由**：
- 技术支持/答疑是独立场景，与通用闲聊分离有助于用户专注，也便于后续接入不同提示词/模型
- 现有 Chat.jsx 的 SSE 流式与消息渲染逻辑成熟，抽出可复用 `ChatPanel` 组件后两个页面各传不同 systemPrompt 即可，改动可控
- 提示词引导比前端约束更灵活，Agent 自主判断是否需要读源码

### 决策 6：前端页面复用策略——抽 ChatPanel 公共组件

**选择**：从 Chat.jsx 中抽取 SSE 流式发送、消息列表渲染、reasoning 展示为公共组件 `components/ChatPanel.jsx`，通过 props 传入 `apiEndpoint`（默认 `/api/agent/chat/stream`）与场景标识。Chat.jsx 与新建 SourceAnalysis.jsx 都基于 ChatPanel 构建。

**理由**：
- 避免复制粘贴 SSE 处理逻辑（streamChat reader 解析、AbortController、reasoning 事件展示）
- 两个页面唯一差异是页面标题、欢迎语与后续可能的会话管理接入
- 为 add-agent-memory change 的会话侧边栏接入预留统一入口

**备选**：直接复制 Chat.jsx 改个标题 → 否决，SSE 逻辑重复维护成本高

### 决策 7：后端按 mode 字段选择系统提示词

**选择**：`ChatRequest` 新增可选字段 `String mode`（值：`general` 默认 / `source-analysis`）。`AgentController` 接收后根据 mode 选择系统提示词：`source-analysis` 模式下使用面向技术支持的专用提示（强调优先读源码、给定位+修复建议+引用代码），`general` 模式保持现有提示不变。两套提示词都配置在 `application.properties` 中。

**理由**：
- 复用现有 `/api/agent/chat/stream` 端点，不新增路由，后端改动最小
- mode 字段可扩展：未来加"代码评审""文档生成"等模式只需加枚举值
- 提示词在配置文件中，无需改代码即可调优

**备选**：a) 新增独立端点 `/api/agent/source-analysis/stream` → 否决，重复 SSE 处理逻辑；c) 前端传 systemPrompt 覆盖 → 否决，提示词应服务端控制，防止注入

**装配点**：`AgentController.chatStream()` 中：
```java
String systemPrompt = "source-analysis".equals(request.mode())
    ? props.getSourceAnalysisSysPrompt()
    : props.getSysPrompt();
// 构建 ReActAgent 时传入对应 systemPrompt
```

## Risks / Trade-offs

- **[上下文窗口压力]** Agent 多次读文件可能撑爆 LLM 上下文 → 缓解：单次读取 200 行上限 + grep 结果 30 条上限 + keep_alive 不影响；DeepSeek 上下文 64K 足够
- **[安全风险]** Agent 被诱导读取敏感文件（如 `.env`、密钥） → 缓解：路径沙箱限制在项目根目录内，但 `.env` 也在项目内；需额外配置敏感文件黑名单（`.env`、`*.pem`、`application-prod.properties` 等），V1 实现时加入
- **[性能]** grepCode 遍历大目录可能慢 → 缓解：跳过 `target/`、`node_modules/`、`.git/` 等目录；限制搜索文件类型（.java/.jsx/.js/.xml/.properties/.md/.sh/.yml/.yaml）
- **[Agent 误用]** Agent 过度读文件导致响应慢/Token 消耗高 → 缓解：系统提示词引导"优先 grep 定位再精读"；maxIters 已限制（当前 10 轮）

## Migration Plan

无数据迁移、无接口变更。部署步骤：
1. 新增 `SourceCodeTools.java`
2. 修改 `AgentScopeConfig.agentscopeToolkit()` 注册新 Bean
3. 更新 `application.properties` 中 `agentscope.agent.sys-prompt` 补充源码工具引导
4. 前端抽取 `ChatPanel.jsx` 公共组件，新建 `SourceAnalysis.jsx` 页面
5. 修改 `App.jsx` 加路由 `/source-analysis`，修改 `AdminLayout.jsx` 加菜单项
6. 重启后端 + 前端热更新，侧边栏出现"源码分析"菜单

回滚：移除 `SourceCodeTools` Bean 注册 + 前端路由/菜单项即可，现有功能不受影响。

## 与 add-agent-memory 的关系

本变更与 `add-agent-memory` 独立，但前端 `ChatPanel` 组件抽取后，记忆系统的会话侧边栏可统一接入两个页面。若两个 change 都要实现，建议先完成 `ChatPanel` 抽取，记忆系统直接复用。
