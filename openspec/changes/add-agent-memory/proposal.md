# Proposal: Agent 记忆与个性化引擎

## 动机

JARVIS 当前所有用户共享相同的 system prompt，Agent 无法区分"Java 后端工程师"和"前端开发者"——每次对话都要重新说明背景。已有 `VectorLongTermMemory` 提取对话级事实，但缺少**结构化的用户画像**（技术栈、常问主题、回答风格偏好）。

## 变更范围

### 新增
- **数据库**：`user_profile` 表（Flyway V7）— 存储结构化用户画像
- **后端**：
  - `UserProfileMapper` — CRUD 操作
  - `UserProfileService` — 读取/更新画像，注入 system prompt
  - `ProfileExtractionService` — 调用 LLM 从最近对话中提取/更新用户画像
- **前端**：`UserProfile.jsx` — 查看和编辑个人画像页面

### 修改
- `AgentController` — 对话完成后异步触发画像提取（每 5 次对话提取一次）
- `AgentFactory` — `create()` 方法查询用户画像，追加到 system prompt
- `AgentScopeConfig` — system prompt 模板增加 `{userProfile}` 占位符
- `AdminLayout.jsx` — 菜单新增"个人画像"项
- `App.jsx` — 路由新增 `/profile`

### 不修改
- `VectorLongTermMemory` — 保持现有对话级事实提取逻辑不变
- `KnowledgeSearchTools` / `SourceCodeTools` — 工具层不变

## 影响分析

| 维度 | 影响 |
|------|------|
| 数据库 | 新增 user_profile 表（V7 迁移） |
| 后端 | 新增 3 个类，修改 2 个类 |
| 前端 | 新增 1 个页面，修改 2 个文件 |
| 性能 | 画像提取异步执行，不阻塞对话响应；AgentFactory 增加一次 DB 查询（< 1ms） |
| LLM 成本 | 每 5 次对话触发 1 次画像提取，单次约 500 token 输入 + 300 token 输出 |
