-- V10: 声明式 Hook 系统
-- mate_hook       hook 定义（YAML 文件或 UI 写入）
-- mate_hook_run   hook 触发审计
-- (原本命名为 V9 但与 V9__usage_cache_tokens.sql 撞号；重命名为 V10 以共存)

CREATE TABLE IF NOT EXISTS mate_hook (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(512),
    enabled             TINYINT(1)   NOT NULL DEFAULT 1,
    event_type          VARCHAR(64)  NOT NULL,
    match_expression    TEXT,
    action_kind         VARCHAR(32)  NOT NULL,
    action_config       TEXT         NOT NULL,
    rate_limit_per_min  INT          DEFAULT 60,
    timeout_ms          INT          DEFAULT 3000,
    source              VARCHAR(16)  DEFAULT 'db',
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    INDEX idx_hook_event_type (event_type),
    INDEX idx_hook_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mate_hook_run (
    id           BIGINT       NOT NULL PRIMARY KEY,
    hook_id      BIGINT       NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    duration_ms  INT          DEFAULT 0,
    message      VARCHAR(512),
    created_at   DATETIME     NOT NULL,
    INDEX idx_hook_run_hook_id (hook_id),
    INDEX idx_hook_run_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
