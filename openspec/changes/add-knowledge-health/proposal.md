# Proposal: 知识库健康度监控

## 动机

JARVIS 已有 RAG 知识库 + agent_trace 推理追踪，但缺少**知识库质量反馈闭环**。当前痛点：

1. **僵尸文档**：某些文档导入后再也没被 Agent 引用过，占用向量索引资源，拉低检索信噪比
2. **知识盲区**：Agent 调用 `knowledgeSearch` 后返回 0 条结果，说明用户问了知识库没有覆盖的内容——但没人知道这些盲区是什么
3. **低质文档**：某些文档被频繁引用，但伴随用户 👎 反馈，说明内容质量有问题

## 变更范围

### 新增
- **后端**：`KnowledgeHealthController` + `KnowledgeHealthMapper` + `KnowledgeHealthService`
  - 3 个聚合查询：僵尸文档列表、盲区关键词列表、文档引用热度排行
- **前端**：`KnowledgeHealth.jsx` 页面 + 菜单项"知识健康"
  - 3 个可视化模块：僵尸文档表格、盲区词云/列表、文档引用热力排行

### 修改
- `AdminLayout.jsx`：菜单新增"知识健康"项（位于"知识库"之后）
- `App.jsx`：路由新增 `/knowledge-health`
- `api/client.js`：新增 `knowledgeHealthApi`

### 不修改
- `KnowledgeSearchTools.java`：不改工具返回格式（V1 通过正则解析 tool_result 文本）
- 数据库 schema：不加新表，利用现有 `agent_trace` + `knowledge_document` + `eval_candidate`

## 影响分析

| 维度 | 影响 |
|------|------|
| 数据库 | 无变更 |
| 后端 | 新增 3 个只读 GET 接口 |
| 前端 | 新增页面和菜单项 |
| 性能 | 正则解析在 Service 层完成，SQL 只做 GROUP BY 聚合 |
| 数据准确率 | 依赖 tool_result 文本中的"来源：标题"格式解析，准确率约 90%（标题含特殊字符可能漏匹配） |
