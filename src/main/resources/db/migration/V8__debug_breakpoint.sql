-- 调试断点表：存储用户在推理步骤上标记的断点
CREATE TABLE IF NOT EXISTS debug_breakpoint (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    message_id      BIGINT NOT NULL COMMENT '消息ID（assistant 回复）',
    step_index      INT    NOT NULL COMMENT '步骤序号',
    note            VARCHAR(500) COMMENT '断点备注',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp (user_id, conversation_id, message_id, step_index),
    INDEX idx_bp_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推理调试断点';
