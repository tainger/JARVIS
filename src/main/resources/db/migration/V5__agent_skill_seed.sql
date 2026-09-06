-- ============================================================
-- Flyway Migration V5 · Agent 技能表 + 种子数据
--
-- 技能 = 系统提示词 + 工具白名单 + 场景描述
-- 4 个内置技能：
--   general-chat          — 所有工具（含 webSearch）
--   source-code-analysis  — 源码分析 + 知识库 + 网页搜索
--   task-management       — 任务管理
--   knowledge-qa          — 知识库 + 网页搜索
--
-- 设计原则：
--   * CREATE TABLE IF NOT EXISTS → 幂等
--   * 种子数据用 INSERT ... ON DUPLICATE KEY UPDATE → 可安全重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_skill (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL
                        COMMENT '技能唯一标识，如 general-chat / source-code-analysis',
    display_name    VARCHAR(128) NOT NULL
                        COMMENT '显示名称',
    description     VARCHAR(512)
                        COMMENT '技能描述',
    system_prompt   TEXT         NOT NULL
                        COMMENT '系统提示词',
    tool_whitelist  VARCHAR(512) NOT NULL DEFAULT 'all'
                        COMMENT '工具白名单，逗号分隔；all=全部工具',
    is_active       TINYINT      NOT NULL DEFAULT 1
                        COMMENT '1=启用, 0=禁用',
    sort_order      INT          NOT NULL DEFAULT 0
                        COMMENT '排序权重',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_agent_skill_name (name),
    KEY idx_agent_skill_active (is_active, sort_order)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 技能表 · 系统提示词 + 工具白名单 + 场景描述';

-- ============================================================
-- 种子数据：4 个内置技能（逐条 UPSERT，可安全重跑）
-- ============================================================

INSERT INTO agent_skill (name, display_name, description, system_prompt, tool_whitelist, is_active, sort_order)
VALUES (
    'general-chat',
    '通用对话',
    '日常问答与综合助手，可使用所有工具',
    'You are JARVIS, a helpful AI assistant. You have access to the following tools: task management, knowledge base search, source code analysis, and web search.\n\nGuidelines:\n- Use knowledgeSearch to find answers in the local knowledge base first.\n- When the knowledge base returns no relevant results, use the webSearch tool to search for current information on the internet.\n- Always cite the source URL when using web search results.\n- Use task tools (listTasks, getTask, createTask) for task-related requests.\n- Provide clear, structured answers in the user''s language.',
    'all',
    1,
    1
)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description = VALUES(description),
    system_prompt = VALUES(system_prompt),
    tool_whitelist = VALUES(tool_whitelist);

INSERT INTO agent_skill (name, display_name, description, system_prompt, tool_whitelist, is_active, sort_order)
VALUES (
    'source-code-analysis',
    '源码分析',
    '分析 repo/ 目录下的外部项目源码，结合知识库和网页搜索',
    'You are JARVIS, a code analysis assistant. The repo/ directory contains external project source code (e.g. Nacos).\n\nGuidelines:\n- Use readFile/listFiles/grepCode tools to explore the code and answer technical questions based on real code.\n- Always cite file paths and line numbers in your answers.\n- Give specific code locations and fix suggestions for error reports.\n- Use knowledgeSearch to find relevant documentation in the knowledge base.\n- When the knowledge base and source code don''t have relevant answers, use the webSearch tool to search for current information. Always cite the source URL.',
    'SourceCodeTools,KnowledgeSearchTools,WebSearchTools',
    1,
    2
)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description = VALUES(description),
    system_prompt = VALUES(system_prompt),
    tool_whitelist = VALUES(tool_whitelist);

INSERT INTO agent_skill (name, display_name, description, system_prompt, tool_whitelist, is_active, sort_order)
VALUES (
    'task-management',
    '任务管理',
    '专注于任务的创建、查询和管理',
    'You are JARVIS, a task management assistant. Help the user create, query, and manage tasks efficiently.\n\nUse the listTasks, getTask, and createTask tools to handle task-related requests. Provide concise summaries of task status.',
    'TaskTools',
    1,
    3
)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description = VALUES(description),
    system_prompt = VALUES(system_prompt),
    tool_whitelist = VALUES(tool_whitelist);

INSERT INTO agent_skill (name, display_name, description, system_prompt, tool_whitelist, is_active, sort_order)
VALUES (
    'knowledge-qa',
    '知识问答',
    '基于知识库和网页搜索回答问题',
    'You are JARVIS, a knowledge Q&A assistant. Answer questions based on the local knowledge base.\n\nGuidelines:\n- Use knowledgeSearch to find relevant information in the knowledge base.\n- When the knowledge base returns no relevant results, use the webSearch tool to search for current information on the internet.\n- Always cite the source URL when using web search results.',
    'KnowledgeSearchTools,WebSearchTools',
    1,
    4
)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description = VALUES(description),
    system_prompt = VALUES(system_prompt),
    tool_whitelist = VALUES(tool_whitelist);
