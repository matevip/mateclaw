-- MateClaw 数据库初始化脚本（MySQL / MariaDB 专用）

-- 用户表
CREATE TABLE IF NOT EXISTS mate_user (
    id          BIGINT       NOT NULL PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(200) NOT NULL,
    nickname    VARCHAR(64),
    avatar      VARCHAR(256),
    email       VARCHAR(128),
    role        VARCHAR(32)  NOT NULL DEFAULT 'user',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    deleted     INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent 配置表
CREATE TABLE IF NOT EXISTS mate_agent (
    id             BIGINT       NOT NULL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    description    TEXT,
    agent_type     VARCHAR(32)  NOT NULL DEFAULT 'react',
    system_prompt  TEXT,
    model_name     VARCHAR(128),
    max_iterations INT          NOT NULL DEFAULT 10,
    enabled        TINYINT(1)   NOT NULL DEFAULT 1,
    icon           VARCHAR(256),
    tags           VARCHAR(256),
    create_time    DATETIME     NOT NULL,
    update_time    DATETIME     NOT NULL,
    deleted        INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模型配置表
CREATE TABLE IF NOT EXISTS mate_model_config (
    id           BIGINT       NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    provider     VARCHAR(64)  NOT NULL DEFAULT 'dashscope',
    model_name   VARCHAR(128) NOT NULL,
    description  TEXT,
    temperature  DOUBLE,
    max_tokens   INT,
    top_p        DOUBLE,
    builtin      TINYINT(1)   NOT NULL DEFAULT 1,
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    is_default   TINYINT(1)   NOT NULL DEFAULT 0,
    max_input_tokens INT      DEFAULT 0,
    enable_search TINYINT(1)  DEFAULT 0,
    search_strategy VARCHAR(32) DEFAULT NULL,
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0,
    INDEX idx_model_config_model_name (model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模型 Provider 表
CREATE TABLE IF NOT EXISTS mate_model_provider (
    provider_id                 VARCHAR(64)  NOT NULL PRIMARY KEY,
    name                        VARCHAR(128) NOT NULL,
    api_key_prefix              VARCHAR(32),
    chat_model                  VARCHAR(64),
    api_key                     VARCHAR(256),
    base_url                    VARCHAR(512),
    generate_kwargs             TEXT,
    is_custom                   TINYINT(1)   NOT NULL DEFAULT 0,
    is_local                    TINYINT(1)   NOT NULL DEFAULT 0,
    support_model_discovery     TINYINT(1)   NOT NULL DEFAULT 0,
    support_connection_check    TINYINT(1)   NOT NULL DEFAULT 0,
    freeze_url                  TINYINT(1)   NOT NULL DEFAULT 0,
    require_api_key             TINYINT(1)   NOT NULL DEFAULT 1,
    auth_type                   VARCHAR(16)  NOT NULL DEFAULT 'api_key',
    oauth_access_token          TEXT,
    oauth_refresh_token         TEXT,
    oauth_expires_at            BIGINT,
    oauth_account_id            VARCHAR(128),
    create_time                 DATETIME     NOT NULL,
    update_time                 DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 系统设置表
CREATE TABLE IF NOT EXISTS mate_system_setting (
    id           BIGINT       NOT NULL PRIMARY KEY,
    setting_key  VARCHAR(128) NOT NULL UNIQUE,
    setting_value TEXT,
    description  VARCHAR(256),
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 技能表
CREATE TABLE IF NOT EXISTS mate_skill (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    skill_type    VARCHAR(32)  NOT NULL DEFAULT 'dynamic',
    icon          VARCHAR(256),
    version       VARCHAR(32),
    author        VARCHAR(64),
    config_json   TEXT,
    source_code   TEXT,
    skill_content TEXT,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    builtin       TINYINT(1)   NOT NULL DEFAULT 0,
    tags          VARCHAR(256),
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 工具表
CREATE TABLE IF NOT EXISTS mate_tool (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    display_name  VARCHAR(128),
    description   TEXT,
    tool_type     VARCHAR(32)  NOT NULL DEFAULT 'builtin',
    bean_name     VARCHAR(128),
    icon          VARCHAR(256),
    mcp_endpoint  VARCHAR(256),
    params_schema TEXT,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    builtin       TINYINT(1)   NOT NULL DEFAULT 0,
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 渠道表
CREATE TABLE IF NOT EXISTS mate_channel (
    id           BIGINT       NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    channel_type VARCHAR(32)  NOT NULL,
    agent_id     BIGINT,
    bot_prefix   VARCHAR(64),
    config_json  TEXT,
    enabled      TINYINT(1)   NOT NULL DEFAULT 0,
    description  VARCHAR(256),
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话表
CREATE TABLE IF NOT EXISTS mate_conversation (
    id               BIGINT       NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL UNIQUE,
    title            VARCHAR(256),
    agent_id         BIGINT,
    username         VARCHAR(64),
    message_count    INT          NOT NULL DEFAULT 0,
    last_message     TEXT,
    last_active_time DATETIME,
    stream_status    VARCHAR(16)  NOT NULL DEFAULT 'idle',
    create_time      DATETIME     NOT NULL,
    update_time      DATETIME     NOT NULL,
    deleted          INT          NOT NULL DEFAULT 0,
    INDEX idx_conversation_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 消息表
CREATE TABLE IF NOT EXISTS mate_message (
    id              BIGINT       NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    content         TEXT,
    content_parts   TEXT,
    tool_name       VARCHAR(128),
    token_usage     INT,
    prompt_tokens   INT          DEFAULT 0,
    completion_tokens INT        DEFAULT 0,
    runtime_model   VARCHAR(128),
    runtime_provider VARCHAR(64),
    status          VARCHAR(32)  NOT NULL DEFAULT 'completed',
    metadata        JSON COMMENT '存储 toolCalls, plan, currentPhase, pendingApproval 等元数据',
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0,
    INDEX idx_message_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 执行计划表
CREATE TABLE IF NOT EXISTS mate_plan (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        VARCHAR(64),
    goal            TEXT,
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending',
    total_steps     INT          NOT NULL DEFAULT 0,
    completed_steps INT          NOT NULL DEFAULT 0,
    summary         TEXT,
    start_time      DATETIME,
    end_time        DATETIME,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 子计划步骤表
CREATE TABLE IF NOT EXISTS mate_sub_plan (
    id          BIGINT       NOT NULL PRIMARY KEY,
    plan_id     BIGINT       NOT NULL,
    step_index  INT          NOT NULL,
    description TEXT,
    status      VARCHAR(32)  NOT NULL DEFAULT 'pending',
    result      TEXT,
    start_time  DATETIME,
    end_time    DATETIME,
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    deleted     INT          NOT NULL DEFAULT 0,
    INDEX idx_sub_plan_plan_id (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 定时任务表
CREATE TABLE IF NOT EXISTS mate_cron_job (
    id              BIGINT        NOT NULL PRIMARY KEY,
    name            VARCHAR(128)  NOT NULL,
    cron_expression VARCHAR(128)  NOT NULL,
    timezone        VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai',
    agent_id        BIGINT        NOT NULL,
    task_type       VARCHAR(16)   NOT NULL DEFAULT 'text',
    trigger_message TEXT,
    request_body    TEXT,
    enabled         TINYINT(1)    NOT NULL DEFAULT 1,
    next_run_time   DATETIME,
    last_run_time   DATETIME,
    create_time     DATETIME      NOT NULL,
    update_time     DATETIME      NOT NULL,
    deleted         INT           NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 渠道会话存储表
CREATE TABLE IF NOT EXISTS mate_channel_session (
    id              BIGINT       NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL UNIQUE,
    channel_type    VARCHAR(32)  NOT NULL,
    target_id       VARCHAR(512) NOT NULL,
    sender_id       VARCHAR(128),
    sender_name     VARCHAR(128),
    channel_id      BIGINT,
    last_active_time DATETIME    NOT NULL,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0,
    INDEX idx_channel_session_type (channel_type),
    INDEX idx_channel_session_channel_id (channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 工作区文件表（Agent 级 Markdown 文档管理）
CREATE TABLE IF NOT EXISTS mate_workspace_file (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    filename        VARCHAR(256) NOT NULL,
    content         LONGTEXT,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    enabled         TINYINT(1)   NOT NULL DEFAULT 0,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0,
    INDEX idx_workspace_file_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== MCP Server 管理 ====================

CREATE TABLE IF NOT EXISTS mate_mcp_server (
    id                      BIGINT       NOT NULL PRIMARY KEY,
    name                    VARCHAR(128) NOT NULL,
    description             TEXT,
    transport               VARCHAR(32)  NOT NULL DEFAULT 'stdio',
    url                     VARCHAR(512),
    headers_json            TEXT,
    command                 VARCHAR(512),
    args_json               TEXT,
    env_json                TEXT,
    cwd                     VARCHAR(512),
    enabled                 TINYINT(1)   NOT NULL DEFAULT 1,
    connect_timeout_seconds INT          NOT NULL DEFAULT 30,
    read_timeout_seconds    INT          NOT NULL DEFAULT 30,
    last_status             VARCHAR(32)  NOT NULL DEFAULT 'disconnected',
    last_error              TEXT,
    last_connected_time     DATETIME,
    tool_count              INT          NOT NULL DEFAULT 0,
    builtin                 TINYINT(1)   NOT NULL DEFAULT 0,
    create_time             DATETIME     NOT NULL,
    update_time             DATETIME     NOT NULL,
    deleted                 INT          NOT NULL DEFAULT 0,
    INDEX idx_mcp_server_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 工具安全治理（ToolGuard） ====================

-- 工具审批表
CREATE TABLE IF NOT EXISTS mate_tool_approval (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    pending_id          VARCHAR(32)  NOT NULL UNIQUE,
    conversation_id     VARCHAR(128) NOT NULL,
    user_id             VARCHAR(64),
    agent_id            VARCHAR(64),
    channel_type        VARCHAR(32),
    requester_name      VARCHAR(128),
    reply_target        VARCHAR(512),
    tool_name           VARCHAR(128) NOT NULL,
    tool_arguments      TEXT,
    tool_call_payload   TEXT,
    tool_call_hash      VARCHAR(64),
    sibling_tool_calls  TEXT,
    summary             TEXT,
    findings_json       TEXT,
    max_severity        VARCHAR(16),
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    resolved_by         VARCHAR(64),
    created_at          DATETIME     NOT NULL,
    resolved_at         DATETIME,
    expire_at           DATETIME,
    create_time         DATETIME     NOT NULL,
    update_time         DATETIME     NOT NULL,
    deleted             INT          NOT NULL DEFAULT 0,
    INDEX idx_tool_approval_conv (conversation_id),
    INDEX idx_tool_approval_status (status),
    INDEX idx_tool_approval_pending_id (pending_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 安全规则表
CREATE TABLE IF NOT EXISTS mate_tool_guard_rule (
    id              BIGINT       NOT NULL PRIMARY KEY,
    rule_id         VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    tool_name       VARCHAR(128),
    param_name      VARCHAR(128),
    category        VARCHAR(64)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL,
    decision        VARCHAR(16)  NOT NULL DEFAULT 'NEEDS_APPROVAL',
    pattern         VARCHAR(512) NOT NULL,
    exclude_pattern VARCHAR(512),
    remediation     TEXT,
    builtin         TINYINT(1)   NOT NULL DEFAULT 0,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    priority        INT          NOT NULL DEFAULT 100,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 安全全局配置表
CREATE TABLE IF NOT EXISTS mate_tool_guard_config (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    enabled              TINYINT(1)   NOT NULL DEFAULT 1,
    guard_scope          VARCHAR(32)  NOT NULL DEFAULT 'all',
    guarded_tools_json   TEXT,
    denied_tools_json    TEXT,
    file_guard_enabled   TINYINT(1)   NOT NULL DEFAULT 1,
    sensitive_paths_json TEXT,
    audit_enabled        TINYINT(1)   NOT NULL DEFAULT 1,
    audit_min_severity   VARCHAR(16)  NOT NULL DEFAULT 'INFO',
    audit_retention_days INT          NOT NULL DEFAULT 90,
    create_time          DATETIME     NOT NULL,
    update_time          DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 安全审计日志表
CREATE TABLE IF NOT EXISTS mate_tool_guard_audit_log (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    conversation_id     VARCHAR(128),
    agent_id            VARCHAR(64),
    user_id             VARCHAR(64),
    channel_type        VARCHAR(32),
    tool_name           VARCHAR(128) NOT NULL,
    tool_params_json    TEXT,
    decision            VARCHAR(16)  NOT NULL,
    max_severity        VARCHAR(16),
    findings_json       TEXT,
    pending_id          VARCHAR(32),
    replay_payload_hash VARCHAR(64),
    create_time         DATETIME     NOT NULL,
    update_time         DATETIME     NOT NULL,
    deleted             INT          NOT NULL DEFAULT 0,
    INDEX idx_guard_audit_conv (conversation_id),
    INDEX idx_guard_audit_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 外部数据源 ====================

CREATE TABLE IF NOT EXISTS mate_datasource (
    id              BIGINT       NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    db_type         VARCHAR(32)  NOT NULL,
    host            VARCHAR(256) NOT NULL,
    port            INT          NOT NULL,
    database_name   VARCHAR(128) NOT NULL,
    username        VARCHAR(128),
    password        VARCHAR(512),
    extra_params    VARCHAR(512),
    schema_name     VARCHAR(128),
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    last_test_time  DATETIME,
    last_test_ok    TINYINT(1),
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 异步任务（视频/图片生成等长耗时操作） ====================

CREATE TABLE IF NOT EXISTS mate_async_task (
    id               BIGINT        NOT NULL PRIMARY KEY,
    task_id          VARCHAR(64)   NOT NULL,
    task_type        VARCHAR(32)   NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'pending',
    conversation_id  VARCHAR(128),
    message_id       BIGINT,
    provider_name    VARCHAR(64),
    provider_task_id VARCHAR(128),
    request_json     TEXT,
    result_json      TEXT,
    error_message    VARCHAR(512),
    progress         INT           DEFAULT 0,
    created_by       VARCHAR(64),
    create_time      DATETIME      NOT NULL,
    update_time      DATETIME      NOT NULL,
    UNIQUE KEY uk_async_task_taskid (task_id),
    INDEX idx_async_task_conv (conversation_id),
    INDEX idx_async_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 记忆召回追踪 ====================

CREATE TABLE IF NOT EXISTS mate_memory_recall (
    id                BIGINT       NOT NULL PRIMARY KEY,
    agent_id          BIGINT       NOT NULL,
    filename          VARCHAR(256) NOT NULL,
    snippet_hash      VARCHAR(64),
    snippet_preview   VARCHAR(512),
    recall_count      INT          NOT NULL DEFAULT 0,
    daily_count       INT          NOT NULL DEFAULT 0,
    query_hashes      TEXT,
    score             DOUBLE       NOT NULL DEFAULT 0.0,
    last_recalled_at  DATETIME,
    promoted          TINYINT(1)   NOT NULL DEFAULT 0,
    create_time       DATETIME     NOT NULL,
    update_time       DATETIME     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0,
    INDEX idx_memory_recall_agent (agent_id),
    INDEX idx_memory_recall_agent_file (agent_id, filename),
    INDEX idx_memory_recall_score (agent_id, score),
    INDEX idx_memory_recall_candidates (agent_id, promoted, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== Wiki 知识库 ====================

CREATE TABLE IF NOT EXISTS mate_wiki_knowledge_base (
    id               BIGINT       NOT NULL PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      TEXT,
    agent_id         BIGINT,
    config_content   LONGTEXT,
    source_directory VARCHAR(512),
    status           VARCHAR(32)  NOT NULL DEFAULT 'active',
    page_count       INT          NOT NULL DEFAULT 0,
    raw_count        INT          NOT NULL DEFAULT 0,
    create_time      DATETIME     NOT NULL,
    update_time      DATETIME     NOT NULL,
    deleted          INT          NOT NULL DEFAULT 0,
    INDEX idx_wiki_kb_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_wiki_raw_material (
    id                BIGINT       NOT NULL PRIMARY KEY,
    kb_id             BIGINT       NOT NULL,
    title             VARCHAR(256) NOT NULL,
    source_type       VARCHAR(32)  NOT NULL DEFAULT 'text',
    source_path       VARCHAR(512),
    original_content  LONGTEXT,
    extracted_text    LONGTEXT,
    content_hash      VARCHAR(64),
    file_size         BIGINT       NOT NULL DEFAULT 0,
    processing_status VARCHAR(32)  NOT NULL DEFAULT 'pending',
    last_processed_at DATETIME,
    error_message     VARCHAR(512),
    create_time       DATETIME     NOT NULL,
    update_time       DATETIME     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0,
    INDEX idx_wiki_raw_kb (kb_id),
    INDEX idx_wiki_raw_status (kb_id, processing_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_wiki_page (
    id              BIGINT       NOT NULL PRIMARY KEY,
    kb_id           BIGINT       NOT NULL,
    slug            VARCHAR(256) NOT NULL,
    title           VARCHAR(256) NOT NULL,
    content         LONGTEXT,
    summary         VARCHAR(1024),
    outgoing_links  LONGTEXT,
    source_raw_ids  LONGTEXT,
    version         INT          NOT NULL DEFAULT 1,
    last_updated_by VARCHAR(32)  NOT NULL DEFAULT 'ai',
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_wiki_page_kb_slug (kb_id, slug),
    INDEX idx_wiki_page_kb (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 工作区表（Phase 2）
-- =============================================

-- 工作区
CREATE TABLE IF NOT EXISTS mate_workspace (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    description   VARCHAR(256),
    owner_id      BIGINT,
    settings_json TEXT,
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_workspace_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 工作区成员
CREATE TABLE IF NOT EXISTS mate_workspace_member (
    id           BIGINT      NOT NULL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(32) NOT NULL DEFAULT 'member',
    create_time  DATETIME    NOT NULL,
    update_time  DATETIME    NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0,
    INDEX idx_ws_member_workspace (workspace_id),
    INDEX idx_ws_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 现有表增加 workspace_id 列（幂等）
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，使用存储过程处理
DROP PROCEDURE IF EXISTS mate_add_workspace_id;
DELIMITER $$
CREATE PROCEDURE mate_add_workspace_id()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='mate_agent' AND COLUMN_NAME='workspace_id') THEN
        ALTER TABLE mate_agent ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='mate_channel' AND COLUMN_NAME='workspace_id') THEN
        ALTER TABLE mate_channel ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='mate_conversation' AND COLUMN_NAME='workspace_id') THEN
        ALTER TABLE mate_conversation ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='mate_wiki_knowledge_base' AND COLUMN_NAME='workspace_id') THEN
        ALTER TABLE mate_wiki_knowledge_base ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='mate_tool' AND COLUMN_NAME='workspace_id') THEN
        ALTER TABLE mate_tool ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='mate_skill' AND COLUMN_NAME='workspace_id') THEN
        ALTER TABLE mate_skill ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END$$
DELIMITER ;

CALL mate_add_workspace_id();
DROP PROCEDURE IF EXISTS mate_add_workspace_id;
