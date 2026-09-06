## Purpose

让 ReAct Agent 在技术支持/答疑场景下能自主读取项目源码文件，基于真实代码回答报错定位、接口实现、逻辑梳理等问题，而不是泛泛而谈或编造答案。

## ADDED Requirements

### Requirement: 提供源码读取工具集

系统 SHALL 向 AgentScope Toolkit 注册一组源码分析工具，Agent 在 ReAct 推理过程中可自主调用。工具 MUST 包含：

- `readFile(path)`：读取项目内指定文件内容，支持指定起始行与最大行数
- `listFiles(dir)`：列出项目内指定目录下的文件与子目录
- `grepCode(pattern, dir)`：在项目指定目录下按正则模式搜索代码，返回匹配的文件路径、行号与行内容

每个工具的返回结果 MUST 为纯文本，适合直接拼入 LLM 上下文。

#### Scenario: Agent 定位报错相关代码

- **WHEN** 用户在对话中贴出一段 `NullPointerException` 堆栈并问"这个空指针在哪"
- **THEN** Agent 自主调用 `grepCode` 搜索堆栈中的类名或方法名
- **AND** 调用 `readFile` 读取命中文件的相关行
- **AND** 基于读到的真实代码给出问题定位与修复建议

#### Scenario: Agent 回答接口实现问题

- **WHEN** 用户问"知识库检索接口的鉴权逻辑在哪"
- **THEN** Agent 调用 `grepCode` 搜索 `/api/knowledge` 或 `SecurityConfig`
- **AND** 读取相关文件后回答

### Requirement: 文件路径安全沙箱

所有源码工具的文件路径 MUST 约束在项目根目录（`user.dir`）下。系统 SHALL 拒绝以下访问：

- 包含路径穿越（`../`）的路径
- 解析后逃逸出项目根目录的路径（含符号链接逃逸）
- 绝对路径（仅允许相对路径输入）

被拒绝的访问 MUST 返回明确的错误说明，不泄露项目外目录结构。

#### Scenario: 拒绝路径穿越

- **WHEN** Agent 调用 `readFile("../../etc/passwd")`
- **THEN** 工具返回错误：路径越权，仅允许访问项目根目录下的文件
- **AND** 不读取任何项目外文件

#### Scenario: 允许项目内路径

- **WHEN** Agent 调用 `readFile("src/main/java/com/example/jarvis/controller/AgentController.java")`
- **THEN** 工具正常返回该文件内容

### Requirement: 读取大小限制与截断

单次文件读取 MUST 限制最大返回行数（默认 200 行）与最大字节数（默认 50KB）。超出限制时 SHALL 截断内容并在返回文本末尾注明"内容已截断，共 N 行，已返回前 M 行"。Agent 可通过指定 `startLine` 读取后续部分。

`grepCode` 搜索结果 MUST 限制最大命中条数（默认 30 条），超出时注明"搜索结果已截断，共 N 条命中，已返回前 30 条"。

#### Scenario: 读取大文件被截断

- **WHEN** Agent 调用 `readFile` 读取一个 500 行的文件且未指定行数限制
- **THEN** 工具返回前 200 行内容
- **AND** 末尾注明截断信息与总行数

#### Scenario: Agent 分页读取剩余内容

- **WHEN** Agent 收到截断提示后调用 `readFile(path, startLine=201)`
- **THEN** 工具返回第 201 行起的后续内容（同样受 200 行上限约束）

### Requirement: 工具注册与系统提示

系统 SHALL 将源码分析工具注册进 AgentScope Toolkit，与现有任务工具、知识库工具并列。Agent 的系统提示词 MUST 补充引导：当用户问题涉及本项目代码、报错、接口实现时，优先使用源码工具定位并基于真实代码回答，不得在未读取代码的情况下编造实现细节。

#### Scenario: Agent 知道何时使用源码工具

- **WHEN** 用户问"这个项目的 JWT 是怎么校验的"
- **THEN** Agent 先调用 `grepCode` 搜索 JWT 相关类
- **AND** 读取实现文件后再组织回答
- **AND** 回答中引用实际读取到的代码（可含文件路径与行号）

### Requirement: 源码分析独立页面与菜单

系统 SHALL 在管理后台侧边栏提供独立的"源码分析"菜单入口，路由为 `/source-analysis`，与通用 AI 对话页（`/chat`）分离。该页面 SHALL 复用通用对话页的 SSE 流式输出与消息渲染逻辑，但使用面向技术支持/答疑的专用系统提示，引导 Agent 优先使用源码分析工具。页面 MUST 对已登录用户可见，未登录用户重定向到登录页。

#### Scenario: 菜单入口可见

- **WHEN** 用户登录后查看侧边栏
- **THEN** 菜单中显示"源码分析"项（代码图标），位于"AI 对话"之后
- **AND** 点击后跳转到 `/source-analysis` 页面

#### Scenario: 专用技术支持提示

- **WHEN** 用户在源码分析页面问"NPE 报错"
- **THEN** Agent 使用专用系统提示，主动调用 grepCode/readFile 定位代码
- **AND** 回答风格偏技术支持（给定位 + 修复建议 + 引用代码），而非通用闲聊

#### Scenario: 与通用对话页隔离

- **WHEN** 用户在 `/chat` 与 `/source-analysis` 之间切换
- **THEN** 两个页面的消息历史相互独立（V1 均为本地 state，刷新即清空）
- **AND** 不共享对话上下文
