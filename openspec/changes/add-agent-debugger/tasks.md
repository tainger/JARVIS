# Tasks: Agent 推理调试器

## 1. 数据库 — debug_breakpoint 表

- [ ] 1.1 创建 `V8__debug_breakpoint.sql`
- [ ] 1.2 验证 Flyway 执行迁移

## 2. 后端 — Model + Mapper

- [ ] 2.1 创建 `DebugBreakpoint.java` 模型类
- [ ] 2.2 创建 `DebugBreakpointMapper.java` + XML：findByUserAndConversation / insert / delete / exists

## 3. 后端 — Service

- [ ] 3.1 创建 `DebugService.java`：
  - getSteps(conversationId) — 步骤列表
  - buildContext(conversationId, messageId, stepIndex) — 上下文重建
  - addBreakpoint / removeBreakpoint — 断点管理
  - diffConversations(convA, convB) — 路径对比

## 4. 后端 — Controller

- [ ] 4.1 创建 `DebugController.java`：
  - GET /api/debug/{conversationId}/steps
  - GET /api/debug/{conversationId}/context/{messageId}/{stepIndex}
  - POST /api/debug/breakpoints
  - GET /api/debug/diff?convA=&convB=

## 5. 前端

- [ ] 5.1 创建 `AgentDebugger.jsx` 页面
- [ ] 5.2 修改 `api/client.js`：新增 debugApi
- [ ] 5.3 修改 `App.jsx`：路由 /debugger
- [ ] 5.4 修改 `AdminLayout.jsx`：菜单新增"推理调试"

## 6. 集成验证

- [ ] 6.1 编译通过
- [ ] 6.2 前端构建通过
- [ ] 6.3 端到端测试：选择对话 → 查看步骤 → 点击步骤看上下文 → 设断点 → 两对话 diff
