-- ============================================================
-- Flyway Migration V1 · 初始化所有业务表
--
-- 设计原则：
--   * 全部 CREATE TABLE IF NOT EXISTS → 幂等，脚本可在新库 & 已有库上安全重跑
--     （这也是 baseline-on-migrate=true 接入已有库的前提）
--   * 引擎=InnoDB，utf8mb4（支持 emoji）
--   * 不包含任何 DROP 语句，不破坏已有数据
--
-- 命名规范：V{版本号}__{描述}.sql    版本号用下划线替代小数点
--   例：V2__add_sys_user_avatar.sql  V3__create_xx_table.sql
-- ============================================================


-- ============================================================
-- 表 1：系统用户表 sys_user  （登录 / 注册 / 权限）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY
                    COMMENT '主键，自增用户ID',

    username      VARCHAR(64)  NOT NULL
                    COMMENT '登录用户名，唯一；限 3-32 字符，字母/数字/_/.',
    password_hash VARCHAR(255) NOT NULL
                    COMMENT 'BCrypt strength=10 加密后的密码哈希，绝不存明文',
    nickname      VARCHAR(128)
                    COMMENT '显示昵称（不唯一）',
    email         VARCHAR(128)
                    COMMENT '邮箱，用于找回密码/通知',
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER'
                    COMMENT '角色：ADMIN=管理员，USER=普通用户',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                    COMMENT '状态：ACTIVE=正常，DISABLED=已禁用',
    avatar_url    VARCHAR(512)
                    COMMENT '头像图片URL，空则前端渲染首字/字母头像',
    last_login_at TIMESTAMP   NULL
                    COMMENT '最近一次成功登录时间',

    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                    COMMENT '创建时间',
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ON UPDATE CURRENT_TIMESTAMP
                    COMMENT '更新时间（MySQL 自动维护）',

    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_email (email),
    KEY idx_sys_user_role  (role)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '系统用户表 · JARVIS 登录/鉴权主表';


-- ============================================================
-- 表 2：任务表 task  （待办管理）
-- ============================================================
CREATE TABLE IF NOT EXISTS task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    completed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_task_completed (completed)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '任务表 · 待办事项管理';


-- ============================================================
-- 表 3：知识库文档表 knowledge_document  （RAG 上传文档元数据）
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    file_name   VARCHAR(255),
    content     MEDIUMTEXT,
    chunk_count INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_knowledge_doc_title (title)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '知识库文档表 · 文档主记录，一个文档对应多个 chunk';


-- ============================================================
-- 表 4：知识库切片表 knowledge_chunk  （RAG 向量检索基本单元）
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT       NOT NULL,
    seq         INT          NOT NULL
                    COMMENT '在原文档中的块序号（从1开始）',
    content     TEXT         NOT NULL,
    embedding   LONGTEXT
                    COMMENT '向量 JSON 数组（bge-m3）；空=未完成向量化',
    dim         INT
                    COMMENT '向量维度（bge-m3=1024）',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_knowledge_chunk_doc_seq (document_id, seq),
    CONSTRAINT fk_chunk_document
        FOREIGN KEY (document_id)
            REFERENCES knowledge_document (id)
            ON DELETE CASCADE
            ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '知识库切片表 · RAG 检索最小单元';
