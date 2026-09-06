-- 知识库异步导入：增加 embedding 中间态 + embedding_started_at 时间戳
-- 用于 CAS 认领任务和检测卡死的中间态任务

ALTER TABLE knowledge_document MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ready' COMMENT 'processing/embedding/ready/failed';
ALTER TABLE knowledge_document ADD COLUMN embedding_started_at DATETIME NULL COMMENT '开始向量化时间，用于检测卡死任务';
