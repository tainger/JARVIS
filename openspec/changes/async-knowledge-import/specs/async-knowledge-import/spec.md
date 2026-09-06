# Spec: 知识库异步导入

## REQ-1: 异步提交

文档导入请求 `POST /api/knowledge/documents` 必须在 2 秒内返回，不等待 embedding 完成。

- 请求体不变：`{title?, fileName?, content}`
- 响应体：`{id, status: "processing", chunkTotal: N}`
- 后端同步完成：HTML 清洗 → Markdown 分块 → 文档落库（status=processing）→ chunk 落库（embedding=NULL）→ 触发异步 embedding
- 如果清洗或分块失败，立即返回 400 错误，不落库

## REQ-2: 异步执行

embedding 在独立线程中执行，不阻塞 HTTP 请求。

- 使用自定义线程池 `knowledgeExecutor`，线程名前缀 `async-kg-`
- 入口通过 CAS 认领任务：`claimForEmbedding(id)` 返回 1 才继续执行，返回 0 说明已被其他线程认领，直接跳过
- 逐块调用 Ollama bge-m3 生成向量
- 每完成一块：UPDATE knowledge_chunk SET embedding=? WHERE document_id=? AND seq=?
- 每完成一块：UPDATE knowledge_document SET chunk_progress=? WHERE id=?
- 全部完成：UPDATE knowledge_document SET status='ready' WHERE id=?
- 完成后调用 reloadIndex() 刷新内存索引
- 失败时：UPDATE knowledge_document SET status='failed', error_message=? WHERE id=?

## REQ-3: 状态查询

新增端点 `GET /api/knowledge/documents/{id}/status`。

- 响应体：`{id, status, chunkProgress, chunkTotal, errorMessage}`
- status 取值：processing / embedding / ready / failed
- 该端点需 JWT 认证（知识库是全局共享的，无需用户隔离）

## REQ-4: 定时任务兜底与重启恢复

应用通过定时任务兜底处理未完成的导入任务，替代 @PostConstruct 方案。

- `@Scheduled(fixedDelay = 60000)` 每 60 秒执行一次
- 执行顺序：
  1. 先调用 `resetStuckEmbedding(30)` 重置卡死超过 30 分钟的 embedding 任务
  2. 再查询所有 status='processing' 的文档
  3. 串行逐个调用 `processEmbedding()` 执行向量化
- `processEmbedding()` 内部走 CAS 认领逻辑，与 @Async 入口共享同一套机制
- 串行执行避免多个文档同时 embedding 导致 Ollama 过载
- 应用重启后，最多延迟 60 秒自动恢复未完成的任务

## REQ-5: CAS 不重复执行

@Async 立即执行与定时任务兜底两个入口，必须保证同一篇文档不会被并发执行。

- 新增 `embedding` 中间状态
- `claimForEmbedding(id)` 执行原子 SQL：`UPDATE ... SET status='embedding' WHERE id=? AND status='processing'`
- 返回影响行数 1 表示认领成功，0 表示已被其他线程认领
- 利用 MySQL InnoDB 行锁保证原子性，无需额外分布式锁组件

## REQ-6: 卡死任务自动重置

执行过程中如果进程崩溃（OOM、kill -9 等），任务会死在 `embedding` 状态，必须有机制自动恢复。

- 新增 `embedding_started_at` 字段记录开始向量化的时间
- 定时任务每次执行前，先把"status='embedding' 且 embedding_started_at 超过 30 分钟"的任务重置回 `processing`
- 重置时保留并追加错误信息：`'; 执行超时已重置'`
- 重置后的任务在下一轮定时扫描中会被重新认领执行

## REQ-7: 重试

新增端点 `POST /api/knowledge/documents/{id}/retry`。

- 仅允许 status='failed' 的文档重试
- 重试时：status 改为 processing → 删除旧 chunk → 重新分块 → 触发异步 embedding
- 响应体：`{id, status: "processing"}`

## REQ-8: 前端轮询

前端提交导入后改为轮询模式。

- 提交后立即关闭 Modal，在文档列表中显示该文档（status=processing）
- 每 2 秒轮询 `GET /documents/{id}/status`
- 当 status 变为 ready 或 failed 时停止轮询
- 最多轮询 300 次（10 分钟），超时后停止并提示用户
- 文档列表中 processing/embedding 状态显示进度条（chunkProgress / chunkTotal）
- failed 状态显示错误信息和重试按钮
- 页面加载时，自动对 processing 状态的文档恢复轮询

## REQ-9: 异步异常可观测

@Async void 方法的异常不能静默丢失，必须有统一的异常处理机制。

- 自定义 `AsyncUncaughtExceptionHandler`
- 异常发生时打 ERROR 级别日志，包含方法名、异常消息和完整栈信息
- 日志线程名前缀 `async-kg-`，便于在日志中识别异步线程

## REQ-10: 向后兼容

- 已有文档的 status 默认为 'ready'（V9 迁移 SQL 中 DEFAULT 'ready'）
- 已有的 `importDocument()` 方法改为 `submitImport()`，但保持 Controller 端点路径不变
- 前端 `knowledgeApi.create()` 的调用方式不变，只是返回值多了 status 和 chunkTotal 字段
- 知识库检索接口完全不受影响
