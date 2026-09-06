# Tasks: 知识库健康度监控

## 1. 后端 — Mapper + SQL + 正则解析

- [ ] 1.1 新建 `KnowledgeHealthMapper.java`（@Mapper），定义 3 个查询方法：
  - `selectAllDocuments()` — 查询所有文档（id, title, chunk_count, created_at）
  - `selectKnowledgeSearchTraces()` — 查询所有 knowledgeSearch 的 tool_result 文本
  - `selectKnowledgeSearchToolCalls()` — 查询所有 knowledgeSearch 的 tool_call（tool_args + message_id）
  - 验证：`mvn compile` 通过

- [ ] 1.2 新建 `src/main/resources/mapper/KnowledgeHealthMapper.xml`：
  - selectAllDocuments：`SELECT id, title, chunk_count, created_at FROM knowledge_document ORDER BY id`
  - selectKnowledgeSearchTraces：`SELECT DISTINCT message_id, tool_result FROM agent_trace WHERE tool_name='knowledgeSearch' AND step_type='tool_result' AND tool_result IS NOT NULL`
  - selectKnowledgeSearchToolCalls：`SELECT message_id, tool_args, created_at FROM agent_trace WHERE tool_name='knowledgeSearch' AND step_type='tool_call'`
  - selectDislikeConversations：`SELECT DISTINCT conversation_id FROM eval_candidate WHERE source='chat'`
  - 验证：MySQL 执行 SQL 返回正确结果

## 2. 后端 — Service

- [ ] 2.1 新建 `KnowledgeHealthService.java`，封装 3 个方法：
  - `getZombieDocs()` — 全量文档 LEFT JOIN 引用统计 → 引用次数=0 的为僵尸文档
  - `getBlindSpots()` — 遍历 tool_call 的 tool_args + 关联同 message_id 的 tool_result → tool_result 含"0 条"的为盲区
  - `getDocHeat()` — 正则提取 tool_result 中的文档标题 → 与 knowledge_document 匹配 → 统计引用次数 + 关联 eval_candidate
  - 内部方法：`extractTitles(toolResult)` 正则解析、`extractQuery(toolArgs)` 查询词提取
  - 验证：curl 调用返回正确 JSON

## 3. 后端 — Controller

- [ ] 3.1 新建 `KnowledgeHealthController.java`，3 个 GET 端点：
  - `GET /api/knowledge/health/zombie-docs`
  - `GET /api/knowledge/health/blind-spots`
  - `GET /api/knowledge/health/doc-heat`
  - 验证：`curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/knowledge/health/zombie-docs` 返回 JSON

## 4. 前端 — API + 页面

- [ ] 4.1 `api/client.js` 新增 `knowledgeHealthApi`：zombieDocs、blindSpots、docHeat
- [ ] 4.2 新建 `pages/KnowledgeHealth.jsx`：
  - 3 个统计卡片（总文档/僵尸/盲区）
  - 文档引用热度柱状图（@ant-design/charts Bar，有 dislike 用珊瑚色）
  - 僵尸文档表格（可勾选 + 批量删除）
  - 知识盲区表格（每行有"添加文档"按钮）
  - 验证：页面渲染无报错

- [ ] 4.3 `App.jsx` 新增路由 `/knowledge-health`
- [ ] 4.4 `AdminLayout.jsx` 菜单新增 `{ key: '/knowledge-health', icon: <HeartOutlined />, label: '知识健康' }`，位于"知识库"之后

## 5. 集成验证

- [ ] 5.1 编译通过：`mvn compile`
- [ ] 5.2 前端构建通过：`npx vite build`
- [ ] 5.3 端到端测试：启动后端 → 登录 → 打开知识健康页 → 验证 3 个模块显示数据
- [ ] 5.4 僵尸文档删除测试：勾选僵尸文档 → 批量删除 → 确认列表刷新
- [ ] 5.5 空数据降级：清空 agent_trace 后访问页面不报错
