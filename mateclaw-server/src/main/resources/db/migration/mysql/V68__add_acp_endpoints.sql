-- V68: ACP (Agent Communication Protocol) endpoint registry (RFC-090 Phase 7)
-- See h2/V68 for column rationale; MySQL needs INSERT ... ON DUPLICATE KEY
-- and a unique index on name for the seed merge to be idempotent.
CREATE TABLE IF NOT EXISTS mate_acp_endpoint (
    id              BIGINT       NOT NULL PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL,
    display_name    VARCHAR(128),
    description     TEXT,
    command         VARCHAR(256) NOT NULL,
    args_json       TEXT,
    env_json        TEXT,
    tool_parse_mode VARCHAR(32)  NOT NULL DEFAULT 'call_title',
    builtin         BOOLEAN      NOT NULL DEFAULT FALSE,
    trusted         BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    stdio_buffer_limit_bytes BIGINT NOT NULL DEFAULT 52428800,
    last_status     VARCHAR(32),
    last_tested_at  DATETIME,
    last_error      TEXT,
    workspace_id    BIGINT       NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_acp_endpoint_name (name)
);

INSERT INTO mate_acp_endpoint
    (id, name, display_name, description, command, args_json, env_json,
     tool_parse_mode, builtin, trusted, enabled,
     stdio_buffer_limit_bytes, workspace_id, create_time, update_time, deleted)
VALUES
    (9100001, 'codex', 'OpenAI Codex CLI', 'Delegate to the Codex ACP agent via npx',
     'npx', '["-y","@zed-industries/codex-acp"]', '{}',
     'call_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0),
    (9100002, 'claude-code', 'Claude Code', 'Delegate to Anthropic\'s Claude Code agent via npx',
     'npx', '["-y","@zed-industries/claude-agent-acp"]', '{}',
     'update_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0),
    (9100003, 'opencode', 'OpenCode', 'Delegate to OpenCode ACP agent (binary on PATH)',
     'opencode', '["acp"]', '{}',
     'update_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0),
    (9100004, 'qwen-code', 'Qwen Code', 'Delegate to Qwen Code ACP agent (binary on PATH)',
     'qwen', '["--acp"]', '{}',
     'call_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description  = VALUES(description),
    command      = VALUES(command),
    args_json    = VALUES(args_json),
    tool_parse_mode = VALUES(tool_parse_mode),
    builtin      = VALUES(builtin),
    trusted      = VALUES(trusted),
    update_time  = NOW();
