-- V55 (RFC-074): explicit user-enabled flag on providers.
--
-- Default FALSE so seeded local providers (Ollama / LM Studio / MLX / llama.cpp)
-- and key-free providers (OpenCode) don't pollute the dropdown until the user
-- turns them on. Existing rows are promoted to enabled=TRUE only when there's
-- evidence the user is using them — the goal is upgrade-time UI cleanup.
--
-- Note: mate_model_config also has an `enabled` column (model-level visibility).
-- The new column here is provider-level. Same name, different table — no clash.
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT FALSE;

-- Index supporting Rule 3's "30-day local usage" lookup. mate_message can be
-- huge on heavy users; without this the EXISTS subquery scans the entire table.
-- Kept after migration — also useful for any future per-provider usage analytics.
CREATE INDEX IF NOT EXISTS idx_message_runtime_provider_time
    ON mate_message(runtime_provider, create_time);

-- Rule 1: real (non-masked, non-empty) API key → user is using it.
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE api_key IS NOT NULL AND api_key <> '' AND POSITION('*' IN api_key) = 0;

-- Rule 2: OAuth provider with token → user is using it.
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE oauth_access_token IS NOT NULL AND oauth_access_token <> '';

-- Rule 3: local provider with messages in last 30 days → user is using it.
-- IN-subquery (not EXISTS) reads better in both H2 and MySQL EXPLAIN, and the
-- distinct set is small (≤ N providers, not N messages).
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE is_local = TRUE
   AND provider_id IN (
     SELECT DISTINCT runtime_provider
       FROM mate_message
      WHERE runtime_provider IS NOT NULL
        AND create_time >= DATEADD('DAY', -30, CURRENT_TIMESTAMP)
   );

-- Rule 4: provider whose model is the current default → user is using it.
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE provider_id IN (SELECT provider FROM mate_model_config WHERE is_default = TRUE);
