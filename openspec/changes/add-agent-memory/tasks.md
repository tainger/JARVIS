# Tasks: Agent 记忆与个性化引擎

## 1. 数据库 — user_profile 表

- [ ] 1.1 创建 `V7__user_profile.sql`：建 user_profile 表
- [ ] 1.2 验证 Flyway 执行迁移

## 2. 后端 — Model + Mapper

- [ ] 2.1 创建 `UserProfile.java` 模型类
- [ ] 2.2 创建 `UserProfileMapper.java` + XML：findByUserId / upsert / update / incrementConversationCount

## 3. 后端 — Service

- [ ] 3.1 创建 `UserProfileService.java`：getProfile / saveProfile / incrementAndMaybeExtract
- [ ] 3.2 创建 `ProfileExtractionService.java`：调用 LLM 提取画像，解析 JSON，更新画像

## 4. 后端 — Controller + AgentFactory 集成

- [ ] 4.1 创建 `UserProfileController.java`：GET/PUT /api/profile + POST /api/profile/extract
- [ ] 4.2 修改 `AgentController.buildSystemPrompt()`：接收 userId，查询画像并注入
- [ ] 4.3 修改 `AgentController` onComplete 回调：递增 conversation_count，触发异步提取
- [ ] 4.4 修改 `AgentFactory.create()`：传递 userId 给 buildSystemPrompt

## 5. 前端

- [ ] 5.1 创建 `UserProfile.jsx` 页面
- [ ] 5.2 修改 `api/client.js`：新增 profileApi
- [ ] 5.3 修改 `App.jsx`：路由 /profile
- [ ] 5.4 修改 `AdminLayout.jsx`：菜单新增"个人画像"

## 6. 集成验证

- [ ] 6.1 编译通过：mvn compile
- [ ] 6.2 前端构建通过
- [ ] 6.3 端到端测试：对话 5 次后检查画像是否生成
- [ ] 6.4 验证 system prompt 注入：对话时 Agent 回答风格是否适配
