-- 知识搜索日志表：记录每次 knowledgeSearch 工具调用的查询词和引用文档
-- 用于知识库健康度监控（僵尸文档 / 盲区关键词 / 引用热度）
CREATE TABLE IF NOT EXISTS knowledge_search_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    query        VARCHAR(500)  NOT NULL COMMENT '搜索查询词',
    result_count INT            NOT NULL DEFAULT 0 COMMENT '命中结果数（0=盲区）',
    doc_ids      VARCHAR(500)   COMMENT '引用的文档ID列表，逗号分隔',
    doc_titles   VARCHAR(2000)  COMMENT '引用的文档标题列表，分号分隔',
    created_at   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at),
    INDEX idx_result_count (result_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识搜索日志';
