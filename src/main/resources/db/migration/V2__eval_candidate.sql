-- ============================================================
-- Flyway Migration V2 · RAG 评测候选池表
--
-- 用途：Chat 页 👎 / 手动提交的"坏 case"先入池（pending），
--       triage（转正 promote / 丢弃 discard）后追加进标注集。
-- 幂等：CREATE TABLE IF NOT EXISTS，不包含 DROP。
-- ============================================================

CREATE TABLE IF NOT EXISTS eval_candidate (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY
                    COMMENT '主键，自增候选ID',

    question      VARCHAR(512) NOT NULL
                    COMMENT '原始问题（Chat 👎 或手动提交）',
    question_norm VARCHAR(512) NOT NULL
                    COMMENT '规范化问题（去空白/标点、统一小写），pending 查重用',
    note          VARCHAR(512)
                    COMMENT '用户备注（哪里答得不好）',
    expected_doc  VARCHAR(255)
                    COMMENT '预期命中文档标题（转正时补全）',
    source        VARCHAR(32)  NOT NULL DEFAULT 'chat'
                    COMMENT '来源：chat=聊天页👎，manual=手动提交',
    chat_ref      VARCHAR(512)
                    COMMENT '聊天上下文引用（当时的回答摘要）',

    status        VARCHAR(16)  NOT NULL DEFAULT 'pending'
                    COMMENT '状态：pending=待处理，promoted=已转正，discarded=已丢弃',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                    COMMENT '入池时间',
    triaged_at    TIMESTAMP   NULL
                    COMMENT '转正/丢弃时间',

    KEY idx_eval_candidate_status (status, created_at),
    KEY idx_eval_candidate_norm (question_norm)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'RAG 评测候选池 · 坏 case 先入池，triage 后进标注集';
