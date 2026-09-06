-- ============================================================
-- Flyway Migration V3 · 会话与记忆系统
--
-- 设计原则：
--   * 全部 CREATE TABLE IF NOT EXISTS → 幂等，可安全重跑
--   * 引擎=InnoDB，utf8mb4（支持 emoji）
--   * 按 user_id 严格隔离，支撑多租户
--
-- 三张表：
--   conversation  - 会话主表（一个用户多个会话）
--   message       - 会话消息表（短期记忆，一对多）
--   user_memory   - 用户长期记忆表（跨会话事实/偏好，向量检索）
-- ============================================================

-- ============================================================
-- 表 1：会话表 conversation
-- ============================================================
CREATE TABLE IF NOT EXISTS conversation (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL
                        COMMENT '所属用户ID（sys_user.id），多租户隔离键',
    title           VARCHAR(200) NOT NULL DEFAULT '新对话'
                        COMMENT '会话标题，首条消息后自动截取前20字',
    last_active_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                        COMMENT '最近活跃时间（排序用）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                        COMMENT '创建时间',
    KEY idx_conversation_user_active (user_id, last_active_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '会话表 · 多轮对话主表，按用户隔离';


-- ============================================================
-- 表 2：消息表 message（短期记忆持久化）
-- ============================================================
CREATE TABLE IF NOT EXISTS message (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT       NOT NULL
                        COMMENT '所属会话ID',
    user_id         BIGINT       NOT NULL
                        COMMENT '所属用户ID（冗余，便于隔离校验）',
    role            VARCHAR(20)  NOT NULL
                        COMMENT '角色：user/assistant/system/tool',
    content         MEDIUMTEXT   NOT NULL
                        COMMENT '消息内容（文本）',
    token_count     INT          NOT NULL DEFAULT 0
                        COMMENT '消息 token 数（估算，用于窗口压缩）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                        COMMENT '创建时间',
    KEY idx_message_conversation_created (conversation_id, created_at ASC),
    KEY idx_message_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '消息表 · 短期记忆持久化，会话内按时间排序';


-- ============================================================
-- 表 3：用户长期记忆表 user_memory
-- ============================================================
CREATE TABLE IF NOT EXISTS user_memory (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL
                        COMMENT '所属用户ID，多租户隔离键',
    content         TEXT         NOT NULL
                        COMMENT '记忆内容（事实/偏好/目标的文本描述）',
    type            VARCHAR(50)  NOT NULL DEFAULT 'fact'
                        COMMENT '类型：fact=事实, preference=偏好, goal=目标',
    embedding       LONGTEXT
                        COMMENT '向量 JSON 数组（bge-m3，1024维），null=未向量化',
    dim             INT          NOT NULL DEFAULT 1024
                        COMMENT '向量维度（bge-m3=1024）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                        COMMENT '创建时间',
    last_accessed_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                        COMMENT '最近检索/更新时间',
    is_active       TINYINT      NOT NULL DEFAULT 1
                        COMMENT '软删除/过时标记：1=有效, 0=已删除/过时',
    KEY idx_user_memory_user_active (user_id, is_active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户长期记忆表 · 跨会话用户画像，向量检索';
