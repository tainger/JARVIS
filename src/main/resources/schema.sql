-- MySQL 8+ 方言；幂等建表：每次启动都会执行，绝不能用 DROP；
-- 已存在的表/索引原样保留，数据跨重启持久化。
-- 注意：MySQL 不支持 CREATE INDEX IF NOT EXISTS，索引放在建表语句内（KEY ...）保证幂等。

CREATE TABLE IF NOT EXISTS task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    completed   BOOLEAN      NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- RAG 知识库 ----------

CREATE TABLE IF NOT EXISTS knowledge_document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    file_name   VARCHAR(255),
    content     MEDIUMTEXT,
    chunk_count INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT       NOT NULL,
    seq         INT          NOT NULL,
    content     TEXT         NOT NULL,
    embedding   LONGTEXT,    -- JSON float 数组；为 NULL 表示未成功向量化
    dim         INT,
    KEY idx_knowledge_chunk_doc (document_id, seq),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
