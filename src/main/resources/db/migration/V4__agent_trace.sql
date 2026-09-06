-- ============================================================
-- Flyway Migration V4 · Agent 推理轨迹
--
-- 每轮 ReAct 迭代记录一行，通过 message_id 关联到 message 表。
-- 支持事后诊断：幻觉、权限错误、回答错误等问题。
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_trace (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    message_id      BIGINT       NOT NULL
                        COMMENT '关联 message.id（assistant 回复消息）',
    conversation_id BIGINT       NOT NULL
                        COMMENT '关联 conversation.id（冗余，便于查询）',
    user_id         BIGINT       NOT NULL
                        COMMENT '所属用户ID（隔离用）',
    step_index     INT          NOT NULL
                        COMMENT '步骤序号（从 1 开始）',
    step_type      VARCHAR(20)  NOT NULL
                        COMMENT '步骤类型：reasoning/tool_call/tool_result/summary',
    content        MEDIUMTEXT
                        COMMENT '思考内容（step_type=reasoning 时）',
    tool_name      VARCHAR(100)
                        COMMENT '工具名（step_type=tool_call/tool_result 时）',
    tool_args      TEXT
                        COMMENT '工具参数 JSON',
    tool_result    TEXT
                        COMMENT '工具结果摘要',
    duration_ms    INT
                        COMMENT '工具执行耗时毫秒（step_type=tool_result 时）',
    is_truncated   TINYINT      NOT NULL DEFAULT 0
                        COMMENT '工具结果是否截断：0=否, 1=是',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                        COMMENT '创建时间',
    KEY idx_trace_message_step (message_id, step_index),
    KEY idx_trace_conv (conversation_id),
    KEY idx_trace_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 推理轨迹表 · 每轮 ReAct 迭代一行，诊断幻觉/权限/回答错误';
