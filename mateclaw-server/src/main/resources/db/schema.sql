-- MateClaw 数据库初始化脚本
-- 兼容 H2（开发模式）和 MySQL 8.0+（生产模式）

-- 用户表
CREATE TABLE IF NOT EXISTS mate_user (
    id          BIGINT       NOT NULL PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(200) NOT NULL,
    nickname    VARCHAR(64),
    avatar      VARCHAR(256),
    email       VARCHAR(128),
    role        VARCHAR(32)  NOT NULL DEFAULT 'user',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    deleted     INT          NOT NULL DEFAULT 0
);

-- Agent 配置表
CREATE TABLE IF NOT EXISTS mate_agent (
    id             BIGINT       NOT NULL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    description    TEXT,
    agent_type     VARCHAR(32)  NOT NULL DEFAULT 'react',
    system_prompt  TEXT,
    model_name     VARCHAR(128),
    max_iterations INT          NOT NULL DEFAULT 10,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    icon           VARCHAR(256),
    tags           VARCHAR(256),
    create_time    DATETIME     NOT NULL,
    update_time    DATETIME     NOT NULL,
    deleted        INT          NOT NULL DEFAULT 0
);

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
    builtin      BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
);

-- 模型 Provider 表
CREATE TABLE IF NOT EXISTS mate_model_provider (
    provider_id                 VARCHAR(64)  NOT NULL PRIMARY KEY,
    name                        VARCHAR(128) NOT NULL,
    api_key_prefix              VARCHAR(32),
    chat_model                  VARCHAR(64),
    api_key                     VARCHAR(256),
    base_url                    VARCHAR(512),
    generate_kwargs             TEXT,
    is_custom                   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_local                    BOOLEAN      NOT NULL DEFAULT FALSE,
    support_model_discovery     BOOLEAN      NOT NULL DEFAULT FALSE,
    support_connection_check    BOOLEAN      NOT NULL DEFAULT FALSE,
    freeze_url                  BOOLEAN      NOT NULL DEFAULT FALSE,
    require_api_key             BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time                 DATETIME     NOT NULL,
    update_time                 DATETIME     NOT NULL
);

-- 系统设置表
CREATE TABLE IF NOT EXISTS mate_system_setting (
    id           BIGINT       NOT NULL PRIMARY KEY,
    setting_key  VARCHAR(128) NOT NULL UNIQUE,
    setting_value TEXT,
    description  VARCHAR(256),
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL
);

ALTER TABLE mate_model_config ADD COLUMN IF NOT EXISTS builtin BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE mate_model_config ADD COLUMN IF NOT EXISTS max_input_tokens INT DEFAULT 0;
ALTER TABLE mate_model_config ADD COLUMN IF NOT EXISTS enable_search BOOLEAN DEFAULT FALSE;
ALTER TABLE mate_model_config ADD COLUMN IF NOT EXISTS search_strategy VARCHAR(32) DEFAULT NULL;

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
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    builtin       BOOLEAN      NOT NULL DEFAULT FALSE,
    tags          VARCHAR(256),
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);

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
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    builtin       BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);

-- 渠道表
CREATE TABLE IF NOT EXISTS mate_channel (
    id           BIGINT       NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    channel_type VARCHAR(32)  NOT NULL,
    agent_id     BIGINT,
    bot_prefix   VARCHAR(64),
    config_json  TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    description  VARCHAR(256),
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
);

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
    deleted          INT          NOT NULL DEFAULT 0
);

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
    metadata        JSON,  -- 存储 toolCalls, plan, currentPhase, pendingApproval 等元数据
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

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
);

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
    deleted     INT          NOT NULL DEFAULT 0
);

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
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    next_run_time   DATETIME,
    last_run_time   DATETIME,
    create_time     DATETIME      NOT NULL,
    update_time     DATETIME      NOT NULL,
    deleted         INT           NOT NULL DEFAULT 0
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_message_conversation ON mate_message(conversation_id);
CREATE INDEX IF NOT EXISTS idx_conversation_username ON mate_conversation(username);
CREATE INDEX IF NOT EXISTS idx_sub_plan_plan_id ON mate_sub_plan(plan_id);
CREATE INDEX IF NOT EXISTS idx_model_config_model_name ON mate_model_config(model_name);

-- 渠道会话存储表（主动推送标识缓存）
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
    deleted         INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_channel_session_type ON mate_channel_session(channel_type);
CREATE INDEX IF NOT EXISTS idx_channel_session_channel_id ON mate_channel_session(channel_id);

-- 工作区文件表（Agent 级 Markdown 文档管理）
CREATE TABLE IF NOT EXISTS mate_workspace_file (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    filename        VARCHAR(256) NOT NULL,
    content         CLOB,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_workspace_file_agent ON mate_workspace_file(agent_id);

-- ==================== MCP Server 管理 ====================

-- MCP Server 配置表（独立于 mate_tool，一个 server 可暴露多个 tools）
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
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    connect_timeout_seconds INT          NOT NULL DEFAULT 30,
    read_timeout_seconds    INT          NOT NULL DEFAULT 30,
    last_status             VARCHAR(32)  NOT NULL DEFAULT 'disconnected',
    last_error              TEXT,
    last_connected_time     DATETIME,
    tool_count              INT          NOT NULL DEFAULT 0,
    builtin                 BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time             DATETIME     NOT NULL,
    update_time             DATETIME     NOT NULL,
    deleted                 INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_mcp_server_enabled ON mate_mcp_server(enabled);

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
    deleted             INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tool_approval_conv ON mate_tool_approval(conversation_id);
CREATE INDEX IF NOT EXISTS idx_tool_approval_status ON mate_tool_approval(status);
CREATE INDEX IF NOT EXISTS idx_tool_approval_pending_id ON mate_tool_approval(pending_id);

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
    builtin         BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    priority        INT          NOT NULL DEFAULT 100,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

-- 安全全局配置表
CREATE TABLE IF NOT EXISTS mate_tool_guard_config (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    guard_scope          VARCHAR(32)  NOT NULL DEFAULT 'all',
    guarded_tools_json   TEXT,
    denied_tools_json    TEXT,
    file_guard_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    sensitive_paths_json TEXT,
    audit_enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    audit_min_severity   VARCHAR(16)  NOT NULL DEFAULT 'INFO',
    audit_retention_days INT          NOT NULL DEFAULT 90,
    create_time          DATETIME     NOT NULL,
    update_time          DATETIME     NOT NULL
);

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
    deleted             INT          NOT NULL DEFAULT 0
);

-- 外部数据源表（查数功能）
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
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_test_time  DATETIME,
    last_test_ok    BOOLEAN,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

-- 为现有表添加 metadata 列（向后兼容，防止迁移时数据丢失）
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS metadata JSON;

CREATE INDEX IF NOT EXISTS idx_guard_audit_conv ON mate_tool_guard_audit_log(conversation_id);
CREATE INDEX IF NOT EXISTS idx_guard_audit_time ON mate_tool_guard_audit_log(create_time);

-- 记忆召回追踪表（Dreaming 评分驱动记忆整合）
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
    promoted          BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time       DATETIME     NOT NULL,
    update_time       DATETIME     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_memory_recall_agent ON mate_memory_recall(agent_id);
CREATE INDEX IF NOT EXISTS idx_memory_recall_agent_file ON mate_memory_recall(agent_id, filename);
CREATE INDEX IF NOT EXISTS idx_memory_recall_score ON mate_memory_recall(agent_id, score);
CREATE INDEX IF NOT EXISTS idx_memory_recall_candidates ON mate_memory_recall(agent_id, promoted, deleted);

-- 补充复合索引（高频查询优化）
CREATE INDEX IF NOT EXISTS idx_message_conv_time ON mate_message(conversation_id, create_time);
CREATE INDEX IF NOT EXISTS idx_workspace_file_agent_enabled ON mate_workspace_file(agent_id, enabled);

-- ==================== OAuth 支持 ====================
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS auth_type VARCHAR(16) NOT NULL DEFAULT 'api_key';
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_access_token TEXT;
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_refresh_token TEXT;
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_expires_at BIGINT;
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_account_id VARCHAR(128);

-- 清理 Codex 不支持的 ChatGPT OAuth 模型（gpt-4o, o3, o4-mini 在 Codex 模式下不可用）
DELETE FROM mate_model_config WHERE provider = 'openai-chatgpt' AND model_name IN ('gpt-4o', 'o3', 'o4-mini');

-- ==================== 异步任务（视频/图片生成等长耗时操作） ====================

CREATE TABLE IF NOT EXISTS mate_async_task (
    id               BIGINT        NOT NULL PRIMARY KEY,
    task_id          VARCHAR(64)   NOT NULL UNIQUE,
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
    update_time      DATETIME      NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_async_task_taskid ON mate_async_task(task_id);
CREATE INDEX IF NOT EXISTS idx_async_task_conv ON mate_async_task(conversation_id);
CREATE INDEX IF NOT EXISTS idx_async_task_status ON mate_async_task(status);

-- ==================== Wiki 知识库 ====================

CREATE TABLE IF NOT EXISTS mate_wiki_knowledge_base (
    id               BIGINT       NOT NULL PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      TEXT,
    agent_id         BIGINT,
    config_content   CLOB,
    source_directory VARCHAR(512),
    status           VARCHAR(32)  NOT NULL DEFAULT 'active',
    page_count       INT          NOT NULL DEFAULT 0,
    raw_count        INT          NOT NULL DEFAULT 0,
    create_time      DATETIME     NOT NULL,
    update_time      DATETIME     NOT NULL,
    deleted          INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_kb_agent ON mate_wiki_knowledge_base(agent_id);

CREATE TABLE IF NOT EXISTS mate_wiki_raw_material (
    id                BIGINT       NOT NULL PRIMARY KEY,
    kb_id             BIGINT       NOT NULL,
    title             VARCHAR(256) NOT NULL,
    source_type       VARCHAR(32)  NOT NULL DEFAULT 'text',
    source_path       VARCHAR(512),
    original_content  CLOB,
    extracted_text    CLOB,
    content_hash      VARCHAR(64),
    file_size         BIGINT       NOT NULL DEFAULT 0,
    processing_status VARCHAR(32)  NOT NULL DEFAULT 'pending',
    last_processed_at DATETIME,
    error_message     VARCHAR(512),
    create_time       DATETIME     NOT NULL,
    update_time       DATETIME     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_raw_kb ON mate_wiki_raw_material(kb_id);
CREATE INDEX IF NOT EXISTS idx_wiki_raw_status ON mate_wiki_raw_material(kb_id, processing_status);

CREATE TABLE IF NOT EXISTS mate_wiki_page (
    id              BIGINT       NOT NULL PRIMARY KEY,
    kb_id           BIGINT       NOT NULL,
    slug            VARCHAR(256) NOT NULL,
    title           VARCHAR(256) NOT NULL,
    content         CLOB,
    summary         VARCHAR(1024),
    outgoing_links  CLOB,
    source_raw_ids  CLOB,
    version         INT          NOT NULL DEFAULT 1,
    last_updated_by VARCHAR(32)  NOT NULL DEFAULT 'ai',
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_wiki_page_kb_slug UNIQUE (kb_id, slug)
);
CREATE INDEX IF NOT EXISTS idx_wiki_page_kb ON mate_wiki_page(kb_id);

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
    CONSTRAINT uk_workspace_slug UNIQUE (slug)
);

-- 工作区成员
CREATE TABLE IF NOT EXISTS mate_workspace_member (
    id           BIGINT      NOT NULL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(32) NOT NULL DEFAULT 'member',
    create_time  DATETIME    NOT NULL,
    update_time  DATETIME    NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ws_member_workspace ON mate_workspace_member(workspace_id);
CREATE INDEX IF NOT EXISTS idx_ws_member_user ON mate_workspace_member(user_id);

-- 现有表增加 workspace_id 列
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_channel ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_wiki_knowledge_base ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_tool ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
