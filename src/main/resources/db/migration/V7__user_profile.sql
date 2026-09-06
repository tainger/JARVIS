-- 用户画像表：存储结构化用户画像，用于 Agent 个性化
CREATE TABLE IF NOT EXISTS user_profile (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL UNIQUE COMMENT '用户ID',
    tech_stack        VARCHAR(500)  COMMENT '技术栈偏好，如 Java/Spring/React',
    frequent_topics   VARCHAR(1000) COMMENT '常问主题',
    answer_style      VARCHAR(500)  COMMENT '回答风格偏好',
    project_context   VARCHAR(1000) COMMENT '项目上下文',
    raw_summary       TEXT          COMMENT 'LLM 生成的完整画像摘要',
    conversation_count INT          NOT NULL DEFAULT 0 COMMENT '对话次数计数',
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像';
