# Design: 知识库异步导入

## 1. 数据模型

### 1.1 knowledge_document 表新增字段

```sql
-- V9: 基础状态字段
ALTER TABLE knowledge_document ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ready' COMMENT 'processing/embedding/ready/failed';
ALTER TABLE knowledge_document ADD COLUMN error_message TEXT NULL COMMENT '失败原因';
ALTER TABLE knowledge_document ADD COLUMN chunk_progress INT NOT NULL DEFAULT 0 COMMENT '已完成向量化的分块数';
ALTER TABLE knowledge_document ADD COLUMN chunk_total INT NOT NULL DEFAULT 0 COMMENT '总分块数';

-- V10: embedding 中间态 + 卡死检测
ALTER TABLE knowledge_document ADD COLUMN embedding_started_at DATETIME NULL COMMENT '开始向量化时间，用于检测卡死任务';
```

### 1.2 状态机（四态）

```
             提交                    CAS 认领                    成功
processing ───────► embedding ──────────────────► ready
         （入库）   （claimForEmbedding）     （updateStatus）
                             │
                             │ 失败（catch 到）
                             ▼
                           failed
                             ▲
                             │  超时重置（30分钟）
                 embedding 卡死（进程崩溃等）
```

状态说明：

| 状态 | 含义 | 进入条件 |
|------|------|----------|
| `processing` | 待处理，排队中 | 用户提交 / 重试 / 卡死重置 |
| `embedding` | 正在向量化 | CAS 认领成功（UPDATE 影响 1 行） |
| `ready` | 导入完成，可检索 | 全部 chunk 向量化成功 |
| `failed` | 导入失败 | 执行过程中抛出异常 |

### 1.3 幂等保证

每次执行向量化前，先删除该文档的所有已有 chunk（`DELETE FROM knowledge_chunk WHERE document_id = ?`），再重新分块 + 向量化 + 插入。这比逐块判断"是否已有向量"更简单可靠，且 embedding 结果是确定性的，重新生成无副作用。

## 2. 双入口 + CAS 核心机制

系统有两个执行入口，共享同一套 CAS 认领逻辑，保证任务不会被重复执行。

### 2.1 入口 A：@Async 立即执行

- **触发时机**：用户提交导入后立即触发
- **执行线程**：`async-kg-N`（自定义线程池 `knowledgeExecutor`）
- **作用**：低延迟，提交即开始向量化

### 2.2 入口 B：定时任务兜底

- **触发时机**：每 60 秒（`@Scheduled(fixedDelay = 60000)`）
- **执行线程**：`scheduling-1`
- **作用**：
  1. 重置卡死的 embedding 任务（超过 30 分钟）
  2. 串行处理 processing 状态的文档
  3. 应用重启后的任务恢复

### 2.3 CAS 认领 SQL（核心）

```sql
UPDATE knowledge_document
SET status = 'embedding',
    embedding_started_at = NOW()
WHERE id = #{id}
  AND status = 'processing'
-- 返回影响行数：1 = 抢到了，0 = 被别人抢了
```

原理：MySQL 单条 UPDATE 是原子操作，InnoDB 行锁保证只有一个线程能成功把 status 从 'processing' 改成 'embedding'。不需要分布式锁，不需要 Redis。

### 2.4 卡死重置 SQL

```sql
UPDATE knowledge_document
SET status = 'processing',
    embedding_started_at = NULL,
    error_message = CONCAT(IFNULL(error_message, ''), '; 执行超时已重置')
WHERE status = 'embedding'
  AND (embedding_started_at IS NULL
       OR embedding_started_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE))
```

## 3. 异步执行流程

```
[前端 POST /documents]
       |
       v
[KnowledgeController.importDocument()]
       |
       v
[KnowledgeService.submitImport()]  ← 同步
  1. 清洗 + 分块（CPU，毫秒级）
  2. INSERT document (status=processing, chunk_total=N)
  3. INSERT N 个 chunk（content 已有，embedding=NULL）
  4. self.processEmbedding(docId)  ← 通过 @Lazy self 注入，走 Spring 代理
  5. 返回 documentId
       |
       v
[KnowledgeService.processEmbedding()]  ← @Async("knowledgeExecutor")
  1. claimForEmbedding(docId)  → CAS 认领
     ├─ 返回 0：已被其他线程认领 → 直接 return
     └─ 返回 1：抢到了 → 继续执行
  2. 删除旧 chunk（幂等清理）
  3. 重新分块
  4. 逐块调用 Ollama bge-m3
  5. UPDATE chunk SET embedding=? WHERE document_id=? AND seq=?
  6. UPDATE document SET chunk_progress=? WHERE id=?
  7. 全部完成：UPDATE document SET status='ready'
  8. reloadIndex()
  9. 失败：UPDATE document SET status='failed', error_message=?
       |
       v
[前端 GET /documents/{id}/status]  ← 轮询
  返回 {status, chunkProgress, chunkTotal, errorMessage}
```

## 4. 定时任务兜底流程

```
@scheduled(fixedDelay = 60000)
scheduledEmbeddingRecovery()
  │
  ├─ 1. resetStuckEmbedding(30)
  │     把卡死超过 30 分钟的 embedding 任务重置回 processing
  │
  └─ 2. findByStatus("processing")
        逐个串行调用 processEmbedding(doc.getId())
        （processEmbedding 内部走同样的 CAS 认领逻辑）
```

**为什么用定时任务而不是 @PostConstruct？**
- 不阻塞应用启动
- 自动处理重启恢复（最多延迟 60 秒）
- 同时负责卡死任务的检测和重置
- 实现更简单，没有启动顺序依赖

## 5. @Async 自调用问题与解决方案

**问题**：同类内 `this.method()` 调用 `@Async` 方法时，Spring AOP 代理被绕过，实际同步执行。

**解决方案**：注入 `@Lazy KnowledgeService self`，用 `self.processEmbedding()` 调用，强制走 Spring 代理。

```java
@Service
public class KnowledgeService {
    @Lazy
    @Autowired
    private KnowledgeService self;

    public void submitImport(...) {
        // ... 同步分块落库
        self.processEmbedding(docId); // 走代理，真正异步
    }

    @Async("knowledgeExecutor")
    public void processEmbedding(Long docId) {
        // ... 异步执行
    }
}
```

## 6. 线程池配置

自定义 `ThreadPoolTaskExecutor` Bean（`knowledgeExecutor`）：

- 核心线程数：2
- 最大线程数：4
- 队列容量：10
- 线程名前缀：`async-kg-`
- 拒绝策略：`CallerRunsPolicy`（队列满时由调用方线程执行，不丢任务）

同时配置 `AsyncUncaughtExceptionHandler`，统一捕获 void 异步方法的未检查异常，打 ERROR 日志 + 错误栈。

## 7. 前端轮询策略

```javascript
// 提交后立即返回 documentId，前端每 2 秒轮询状态
const doc = await knowledgeApi.create(data); // 立即返回 {id, status: 'processing'}
const poll = setInterval(async () => {
  const status = await knowledgeApi.getStatus(doc.id);
  if (status.status === 'ready' || status.status === 'failed') {
    clearInterval(poll);
    // 更新 UI
  }
}, 2000);
```

- 轮询间隔：2 秒
- 超时保护：最多轮询 300 次（10 分钟）
- 页面加载时：自动对 processing 状态的文档恢复轮询
- 进度条：chunkProgress / chunkTotal
- failed 状态：显示错误信息 + 重试按钮
