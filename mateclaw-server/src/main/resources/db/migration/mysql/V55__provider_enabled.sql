-- V55 (RFC-074): explicit user-enabled flag on providers. See H2 sibling for
-- the full rationale; this file only differs in dialect-specific syntax.
--
-- MySQL lacks `ADD COLUMN IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS` —
-- guard via INFORMATION_SCHEMA + dynamic SQL so re-runs are no-ops.

-- ── Add `enabled` column ───────────────────────────────────────────────────
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_provider'
             AND COLUMN_NAME = 'enabled');
SET @s := IF(@c = 0,
             'ALTER TABLE mate_model_provider ADD COLUMN enabled BOOLEAN DEFAULT FALSE',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── Index supporting Rule 3's 30-day usage lookup ──────────────────────────
SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_message'
             AND INDEX_NAME = 'idx_message_runtime_provider_time');
SET @s := IF(@i = 0,
             'CREATE INDEX idx_message_runtime_provider_time ON mate_message(runtime_provider, create_time)',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── Rule 1: real (non-masked, non-empty) API key → user is using it ────────
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE api_key IS NOT NULL AND api_key <> '' AND INSTR(api_key, '*') = 0;

-- ── Rule 2: OAuth provider with token → user is using it ───────────────────
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE oauth_access_token IS NOT NULL AND oauth_access_token <> '';

-- ── Rule 3: local provider with messages in last 30 days → user is using it ─
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE is_local = TRUE
   AND provider_id IN (
     SELECT DISTINCT runtime_provider
       FROM mate_message
      WHERE runtime_provider IS NOT NULL
        AND create_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)
   );

-- ── Rule 4: provider whose model is the current default → user is using it ─
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE provider_id IN (SELECT provider FROM mate_model_config WHERE is_default = TRUE);
