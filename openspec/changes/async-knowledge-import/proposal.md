# Proposal: 知识库异步导入

## 背景

当前知识库导入是同步操作：前端 POST 请求 → 后端分块 → 逐块调用 Ollama bge-m3 生成 embedding → 落库 → 返回结果。一篇 20 个分块的文档需要约 5 分钟完成，而前端 axios 超时仅 30 秒，导致客户端断开连接后后端虽然成功写入数据，但响应无法返回（Broken pipe）。

## 问题

1. 前端 30 秒超时 vs 后端 5 分钟执行，连接必然断开
2. 用户无法看到导入进度
3. 应用重启时正在执行的导入任务会丢失
4. @Async 自调用失效、并发重复执行、进程崩溃卡死等边界情况需要处理

## 方案

将导入改为异步模式，采用"数据库状态机 + 双入口 CAS 认领 + 定时任务兜底"架构：

- **提交阶段**：后端立即将文档元数据落库（status=processing），返回 documentId，前端开始轮询
- **执行阶段**：`@Async` 线程立即触发向量化，通过 CAS（Compare-And-Swap）认领任务，避免与定时任务并发重复
- **兜底阶段**：`@Scheduled(fixedDelay=60000)` 定时任务每 60 秒扫描一次，先重置卡死超过 30 分钟的 embedding 任务，再串行处理 processing 状态的文档，作为 @Async 的兜底和重启恢复机制
- **状态机**：processing → embedding → ready / failed，embedding 为中间态用于 CAS 认领和卡死检测

## 影响

- 新增 `knowledge_document.status / error_message / chunk_progress / chunk_total / embedding_started_at` 字段（Flyway V9 + V10）
- `KnowledgeService.importDocument()` 改为 `submitImport()` 同步分块落库 + `processEmbedding()` @Async 向量化
- 新增 `AsyncConfig` 自定义线程池 + 统一异常处理器
- `KnowledgeController` 新增 `GET /documents/{id}/status` 和 `POST /documents/{id}/retry` 端点
- 前端改为提交后轮询模式，显示进度条和状态
- 新增 `@Scheduled` 定时任务兜底，替代原 @PostConstruct 启动恢复方案
