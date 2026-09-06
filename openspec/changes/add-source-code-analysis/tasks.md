## 1. SourceCodeTools 核心实现

- [ ] 1.1 新建 `src/main/java/com/example/jarvis/tool/SourceCodeTools.java`，`@Component` 注解，注入沙箱基目录 `${user.dir}/repo/`，实现路径沙箱校验方法 `resolveSafePath(relativePath)`：normalize + startsWith 校验 + NOFOLLOW_LINKS，验证：传入 `../../etc/passwd` 返回 null/异常，传入 `../src/main/java` 返回越权错误（不允许访问 JARVIS 自身），传入 `nacos/naming/src/main/java` 返回合法路径
- [ ] 1.2 实现敏感文件黑名单（`.env`、`*.pem`、`*.key`、`application-prod.properties`、`id_rsa`），校验时拦截，验证：传入 `nacos/.env` 返回拒绝信息
- [ ] 1.3 实现 `readFile(path, startLine, maxLines)` `@Tool` 方法：路径校验 → 读文件 → 行数/字节数双阈值截断 → 末尾附截断提示，验证：读取 Nacos 大文件（如 `nacos/config/src/main/java/com/alibaba/nacos/config/server/service/ConfigOperationService.java`）返回前 200 行 + 截断信息；读取 `../../etc/passwd` 返回越权错误
- [ ] 1.4 实现 `listFiles(dir)` `@Tool` 方法：路径校验 → 列目录（区分文件/目录）→ 跳过 `target/`、`node_modules/`、`.git/`、`build/`、`dist/`，验证：列出 `nacos/` 下内容（可见 naming/config/core 等模块目录）
- [ ] 1.5 实现 `grepCode(pattern, dir)` `@Tool` 方法：路径校验 → 遍历指定目录下文本文件（.java/.jsx/.js/.xml/.properties/.md/.sh/.yml/.yaml/.ts/.tsx）→ 正则匹配 → 限制 30 条结果 → 附截断提示，验证：搜索 `DistroProtocol` 在 `nacos/core/` 下返回命中文件+行号+内容
- [ ] 1.6 补充 Javadoc 与 `@Tool`/`@ToolParam` 的 description（英文，AgentScope 要求），description 中说明路径相对于 `repo/` 目录，验证：启动后 Toolkit 日志输出 `Registered tool 'readFile'/'listFiles'/'grepCode'`

## 2. 工具注册与系统提示

- [ ] 2.1 修改 `config/AgentScopeConfig.java` 的 `agentscopeToolkit()` Bean：方法参数新增 `SourceCodeTools sourceCodeTools`，调用 `toolkit.registerTool(sourceCodeTools)`，验证：后端启动日志显示 3 个工具组各工具注册成功
- [ ] 2.2 更新 `application.properties` 中 `agentscope.agent.sys-prompt`：补充源码工具使用引导（"当用户问题涉及 `repo/` 下项目的代码/报错/接口实现时，优先使用 readFile/listFiles/grepCode 工具定位并基于真实代码回答，不得编造实现细节"），验证：启动后日志打印的 sys-prompt 包含新引导
- [ ] 2.3 新增专用提示词配置 `agentscope.agent.source-analysis-sys-prompt`：面向技术支持/答疑场景（强调优先读 `repo/` 下源码、回答给定位+修复建议+引用文件路径与行号），验证：配置项存在且可读
- [ ] 2.4 修改 `dto/ChatRequest.java` 新增可选字段 `String mode`（默认 general）；修改 `AgentController.chatStream()` 根据 mode 选择系统提示词（source-analysis 用专用提示），验证：mode=source-analysis 时 Agent 使用专用提示回答
- [ ] 2.5 重启后端，确认无启动错误，Toolkit 注册日志包含 7 个工具（getTask/listTasks/createTask/knowledgeSearch/readFile/listFiles/grepCode），验证：`tail log/info.log | grep "Registered tool"`

## 3. 前端独立页面与菜单

- [ ] 3.1 从 `Chat.jsx` 抽取公共组件 `web/src/components/ChatPanel.jsx`：SSE 流式发送、消息列表渲染、reasoning 展示、AbortController 中断，通过 props 传入标题/欢迎语，验证：Chat.jsx 改为基于 ChatPanel 后功能不变
- [ ] 3.2 新建 `web/src/pages/SourceAnalysis.jsx`：基于 ChatPanel，设置页面标题"源码分析"、欢迎语"贴上报错或问 `repo/` 下项目的代码问题，我来读源码帮你定位"，发送请求时携带 `mode: 'source-analysis'`，验证：页面渲染正常，样式与 Chat 一致，后端收到 mode 字段
- [ ] 3.3 修改 `web/src/App.jsx`：新增 `import SourceAnalysis from './pages/SourceAnalysis'` + `<Route path="source-analysis" element={<SourceAnalysis />} />`，验证：访问 `/source-analysis` 能渲染页面
- [ ] 3.4 修改 `web/src/layouts/AdminLayout.jsx`：menuItems 新增 `{ key: '/source-analysis', icon: <CodeOutlined />, label: '源码分析' }`（需 import CodeOutlined），验证：侧边栏出现"源码分析"菜单项，点击跳转正确，选中态高亮
- [ ] 3.5 确认两个页面历史隔离：Chat.jsx 与 SourceAnalysis.jsx 各自维护独立 messages state，验证：在 Chat 发一条消息后切到 SourceAnalysis，消息不串扰
- [ ] 3.6 运行前端 `npm run build` 确认无编译错误，验证：构建成功无 warning 阻断

## 4. 端到端验证

- [ ] 4.1 确保 `repo/nacos/` 已拉取（`git clone https://github.com/alibaba/nacos.git repo/nacos`），登录获取 JWT，调用 `/api/agent/chat` 问"Nacos 的服务注册逻辑在哪个类"，验证：Agent 调用 grepCode + readFile，回答中引用实际文件路径（如 `nacos/naming/src/main/java/com/alibaba/nacos/naming/controllers/v3/InstanceControllerV3.java`）
- [ ] 4.2 在浏览器"源码分析"页面贴一段模拟报错堆栈问"这个空指针可能在哪"，验证：Agent 自主搜索 `repo/nacos/` 下相关代码并给出定位，非泛泛而谈
- [ ] 4.3 验证路径安全：诱导 Agent 读 `../src/main/java/com/example/jarvis/tool/SourceCodeTools.java`（JARVIS 自身），验证：Agent 返回拒绝，不泄露 JARVIS 源码
- [ ] 4.4 验证源码分析页能展示工具调用推理（reasoning 事件），验证：浏览器对话时能看到 Agent 思考过程中包含 grepCode/readFile 调用
- [ ] 4.5 验证菜单与路由：登录后侧边栏"源码分析"可见可点，未登录访问 `/source-analysis` 重定向到登录页，验证：路由守卫生效
- [ ] 4.6 运行 `./mvnw test` 确认现有测试不受影响，验证：RagEvalTest 等既有测试全部通过
