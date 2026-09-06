# Tasks: 知识库异步导入

## 1. 数据库 — V9 迁移（基础状态字段）

- [x] 1.1 创建 `V9__knowledge_document_status.sql`：ALTER TABLE 增加 status / error_message / chunk_progress / chunk_total
- [x] 1.2 验证 Flyway 执行迁移

## 2. 数据库 — V10 迁移（embedding 中间态 + 卡死检测）

- [x] 2.1 创建 `V10__embedding_status.sql`：新增 embedding_started_at，扩展 status 注释
- [x] 2.2 验证 Flyway 执行迁移

## 3. 后端 — Model + Mapper

- [x] 3.1 `KnowledgeDocument.java` 增加 status / errorMessage / chunkProgress / chunkTotal / embeddingStartedAt 字段
- [x] 3.2 `KnowledgeMapper.java` + XML 增加：findByStatus / updateStatus / updateChunkProgress / updateChunkEmbedding / claimForEmbedding / resetStuckEmbedding

## 4. 后端 — 异步基础设施

- [x] 4.1 新增 `AsyncConfig.java`：自定义 `knowledgeExecutor` 线程池 + `AsyncUncaughtExceptionHandler`
- [x] 4.2 `JarvisApplication.java` 添加 `@EnableAsync` + `@EnableScheduling`

## 5. 后端 — Service

- [x] 5.1 `KnowledgeService.java` 重构 `importDocument()` → `submitImport()`（同步分块 + 落库 + 触发异步）
- [x] 5.2 新增 `processEmbedding(Long docId)` — `@Async("knowledgeExecutor")` 方法：CAS 认领 → 清理旧 chunk → 逐块 embedding → 更新进度 → 完成/失败
- [x] 5.3 新增 `scheduledEmbeddingRecovery()` — `@Scheduled(fixedDelay=60000)` 定时兜底：先重置卡死任务，再串行处理 processing 文档
- [x] 5.4 新增 `getImportStatus(Long docId)` — 供 Controller 查询
- [x] 5.5 新增 `retryImport(Long docId)` — 重试失败文档
- [x] 5.6 `@Lazy KnowledgeService self` 注入，解决 @Async 自调用失效问题

## 6. 后端 — Controller

- [x] 6.1 `KnowledgeController.java` 改 `importDocument()` 返回 `{id, status, chunkTotal}`
- [x] 6.2 新增 `GET /documents/{id}/status` 端点
- [x] 6.3 新增 `POST /documents/{id}/retry` 端点（重试失败文档）

## 7. 前端

- [x] 7.1 `api/client.js` 新增 `knowledgeApi.getStatus(id)` 和 `knowledgeApi.retry(id)`
- [x] 7.2 `Knowledge.jsx` 改为提交后轮询模式，显示导入进度条
- [x] 7.3 文档列表增加状态列（processing/embedding/ready/failed）
- [x] 7.4 页面加载时自动恢复 processing 文档的轮询
- [x] 7.5 failed 状态显示错误信息和重试按钮

## 8. 集成验证

- [x] 8.1 后端编译通过
- [x] 8.2 前端构建通过
- [x] 8.3 端到端测试：导入文档 → 立即返回 → 轮询进度 → 完成 → 列表显示 ready
- [x] 8.4 @Async 线程名验证：确认异步线程为 `async-kg-N`
- [x] 8.5 CAS 防重复验证：确认 @Async 与定时任务不会并发执行同一文档
- [x] 8.6 卡死重置验证：embedding 状态超过 30 分钟自动重置
