-- 知识库文档异步导入：增加状态追踪字段
-- 已有文档默认 status='ready'，不影响现有功能

ALTER TABLE knowledge_document ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ready' COMMENT 'processing/ready/failed';
ALTER TABLE knowledge_document ADD COLUMN error_message TEXT NULL COMMENT '导入失败原因';
ALTER TABLE knowledge_document ADD COLUMN chunk_progress INT NOT NULL DEFAULT 0 COMMENT '已完成向量化的分块数';
ALTER TABLE knowledge_document ADD COLUMN chunk_total INT NOT NULL DEFAULT 0 COMMENT '总分块数';
