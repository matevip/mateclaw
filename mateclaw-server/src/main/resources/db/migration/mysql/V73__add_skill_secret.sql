-- V73: Per-skill encrypted secret store (RFC-091 settings bridge).
-- Holds AES-encrypted values for skill manifest fields with type=secret —
-- e.g. AIRTABLE_API_KEY for the airtable-base wizard template. The
-- runtime layer (SkillScriptExecutionService) decrypts and injects these
-- as environment variables when spawning skill subprocesses, so SKILL.md
-- bodies can reference them as plain `$AIRTABLE_API_KEY` without baking
-- the secret into the manifest.
--
-- MySQL doesn't support `ADD COLUMN IF NOT EXISTS`; we get idempotency
-- via `CREATE TABLE IF NOT EXISTS` plus an INFORMATION_SCHEMA guard for
-- index creation.

CREATE TABLE IF NOT EXISTS mate_skill_secret (
    id              BIGINT        NOT NULL PRIMARY KEY,
    skill_id        BIGINT        NOT NULL,
    secret_key      VARCHAR(128)  NOT NULL,
    encrypted_value TEXT          NOT NULL,
    create_time     DATETIME      NOT NULL,
    update_time     DATETIME      NOT NULL,
    deleted         TINYINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_skill_secret_key (skill_id, secret_key),
    KEY idx_skill_secret_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Per-skill AES-encrypted secret values (RFC-091 settings bridge).';
