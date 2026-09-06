## 1. 数据库迁移与实体

- [ ] 1.1 新建 `src/main/resources/db/migration/V5__agent_skill.sql`，创建 `agent_skill` 表（id/name/display_name/description/system_prompt/tool_whitelist/icon/enabled/is_built_in/sort_order/created_at/updated_at），含 `uk_skill_name (name)` 唯一索引，验证：`./mvnw spring-boot:run` 启动后 Flyway 日志显示 V5 迁移成功
- [ ] 1.2 在 V5 迁移文件中插入 4 条种子数据：`general-chat`（tool=all, icon=RobotOutlined）、`source-code-analysis`（tool=SourceCodeTools,KnowledgeSearchTools, icon=CodeOutlined）、`task-management`（tool=TaskTools, icon=UnorderedListOutlined）、`knowledge-qa`（tool=KnowledgeSearchTools, icon=BookOutlined），验证：`SELECT name, display_name, tool_whitelist FROM agent_skill` 返回 4 行
- [ ] 1.3 新建 `src/main/java/com/example/jarvis/model/AgentSkill.java` 实体类，字段与表对应，验证：编译通过
- [ ] 1.4 新建 `src/main/java/com/example/jarvis/mapper/AgentSkillMapper.java` 接口 + `src/main/resources/mapper/AgentSkillMapper.xml`，提供 `findByName(name)`、`findAllEnabled()`、`findById(id)` 方法，验证：MyBatis 启动时无绑定错误

## 2. 技能解析服务

- [ ] 2.1 新建 `src/main/java/com/example/jarvis/service/SkillResolver.java`（`@Component`），提供 `resolve(skillName, mode)` 方法，返回 `ResolvedSkill(name, systemPrompt, toolWhitelist)`，验证：单元测试覆盖 3 级回退（skill → mode → 默认）
- [ ] 2.2 在 `SkillResolver` 中实现 `mapModeToSkill(mode)` 映射：`source-analysis` → `source-code-analysis`，其他 → `general-chat`，验证：`resolve(null, "source-analysis")` 返回 source-code-analysis 技能
- [ ] 2.3 在 `SkillResolver` 中处理禁用技能：`enabled=0` 的技能跳过，回退到默认，验证：手动 `UPDATE agent_skill SET enabled=0 WHERE name='task-management'` 后 `resolve("task-management", null)` 返回 general-chat

## 3. per-request Toolkit 过滤

- [ ] 3.1 修改 `src/main/java/com/example/jarvis/config/AgentFactory.java`：构造函数额外注入 `TaskTools`、`SourceCodeTools`、`KnowledgeSearchTools` 三个 `@Component` Bean，验证：编译通过、Spring 启动注入成功
- [ ] 3.2 在 `AgentFactory` 中新增 `resolveToolkit(toolWhitelist)` 私有方法：`all` 或空时返回 `sharedToolkit`，否则创建新 Toolkit 只注册白名单中的工具，验证：`resolveToolkit("TaskTools")` 返回只含 3 个 TaskTools 方法的 Toolkit
- [ ] 3.3 修改 `AgentFactory.create()` 方法签名：增加 `String toolWhitelist` 参数，调用 `resolveToolkit()` 传给 ReActAgent，验证：编译通过

## 4. ChatRequest 与 AgentController 集成

- [ ] 4.1 修改 `src/main/java/com/example/jarvis/dto/ChatRequest.java`：新增 `skill` 字段（String），验证：编译通过
- [ ] 4.2 修改 `src/main/java/com/example/jarvis/controller/AgentController.java`：在 `chatStream()` 中注入 `SkillResolver`，调用 `resolve(request.skill(), request.mode())` 获取 `ResolvedSkill`，将 `systemPrompt` 和 `toolWhitelist` 传给 `agentFactory.create()`，验证：编译通过
- [ ] 4.3 移除或保留 `AgentController.buildSystemPrompt()`：如果 `SkillResolver` 已完全替代该方法的逻辑，保留方法但内部委托给 `SkillResolver`，验证：现有 `mode` 参数行为不变

## 5. 只读 API

- [ ] 5.1 新建 `src/main/java/com/example/jarvis/controller/SkillController.java`，`GET /api/skills`：调用 `agentSkillMapper.findAllEnabled()` 返回技能列表（不含 system_prompt 和 tool_whitelist），验证：curl 返回 JSON 数组
- [ ] 5.2 在 `SkillController` 中实现 `GET /api/skills/{id}`：返回完整技能详情（含 system_prompt），验证：`curl /api/skills/2` 返回 source-code-analysis 完整信息
- [ ] 5.3 确认 `SecurityConfig` 中 `/api/skills/**` 被 `/api/**` JWT 鉴权覆盖，验证：无 token 请求返回 401

## 6. 前端集成

- [ ] 6.1 修改 `web/src/api/client.js`：新增 `skillApi.list()` 函数调用 `GET /api/skills`；修改 `streamChat()` 参数增加 `skill` 字段，验证：`skillApi.list()` 返回技能数组
- [ ] 6.2 修改 `web/src/pages/Chat.jsx`：在对话区域顶部新增技能 `Select` 选择器，`useEffect` 中调用 `skillApi.list()` 加载选项列表，选项格式为图标 + displayName + description，默认选中 `general-chat`，验证：页面加载后选择器显示 4 个选项
- [ ] 6.3 修改 `Chat.jsx` 的 `send()` 函数：将选中的技能名通过 `streamChat()` 的 `skill` 参数发送，验证：Network 面板中请求体包含 `skill` 字段
- [ ] 6.4 修改 `web/src/pages/SourceAnalysis.jsx`：将 `mode: 'source-analysis'` 改为 `skill: 'source-code-analysis'`（或两者都传），验证：源码分析页面行为不变
- [ ] 6.5 降级处理：`GET /api/skills` 失败时技能选择器显示默认选项"通用对话"，验证：断网后页面不崩溃

## 7. 端到端验证

- [ ] 7.1 登录后在 Chat 页面选择"任务管理"技能，问"列出所有任务"，验证：Agent 调用 `listTasks` 工具，推理轨迹中只有 TaskTools 相关工具调用
- [ ] 7.2 在 Chat 页面选择"源码分析"技能，问"Nacos 的服务注册逻辑在哪"，验证：Agent 调用 `grepCode`/`readFile`，不调用 `listTasks`
- [ ] 7.3 在 Chat 页面选择"知识问答"技能，问"简历该怎么写"，验证：Agent 调用 `knowledgeSearch`，不调用源码工具
- [ ] 7.4 在 Chat 页面选择"通用对话"技能，问"列出所有任务"，验证：Agent 能调用 `listTasks`（全量工具可用）
- [ ] 7.5 通过 SourceAnalysis 页面（旧入口）进入，问"Nacos 有哪些模块"，验证：行为与变更前一致（skill 体系向后兼容 mode）
- [ ] 7.6 调用 `GET /api/skills` 验证返回 4 条启用技能，按 sort_order 排序
- [ ] 7.7 运行 `./mvnw compile` 确认编译通过，运行 `cd web && npx vite build` 确认前端构建通过
