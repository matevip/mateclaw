CREATE TABLE IF NOT EXISTS mate_skill_usage_stat (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_name          VARCHAR(128) NOT NULL,
    skill_id            BIGINT,
    agent_id            BIGINT NOT NULL DEFAULT 0,
    conversation_id     VARCHAR(128) NOT NULL DEFAULT '',
    load_count          BIGINT NOT NULL DEFAULT 0,
    last_loaded_at      DATETIME(3),
    last_file_path      VARCHAR(512),
    last_token_estimate INT,
    create_time         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted             TINYINT NOT NULL DEFAULT 0,

    UNIQUE KEY uk_skill_usage_scope (skill_name, agent_id, conversation_id),
    KEY idx_skill_usage_agent_recent (agent_id, last_loaded_at),
    KEY idx_skill_usage_name_recent (skill_name, last_loaded_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Skill runtime usage statistics keyed by skill and invocation scope.';
