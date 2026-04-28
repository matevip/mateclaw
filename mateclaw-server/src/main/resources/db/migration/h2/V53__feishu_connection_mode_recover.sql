-- V53: Recover from V52's silent no-op.
--
-- V52 was supposed to flip every Feishu channel's connection_mode to
-- "websocket". Its REPLACE pattern matched the compact JSON form
-- '"connection_mode":"webhook"' but the form UI persists configJson via
-- JSON.stringify(cfg, null, 2), which produces '"connection_mode": "webhook"'
-- (with a space after the colon). V52 ran in 4 ms, updated zero rows, and
-- got marked "successfully applied" — Flyway's repair() then refuses to
-- re-run it on later starts.
--
-- This migration redoes the work with REPLACE patterns that cover both
-- formats, plus a permissive WHERE clause so any row that escaped V52 is
-- now caught. Idempotent on rows already at "websocket".

UPDATE mate_channel
SET config_json = CASE
        WHEN config_json IS NULL OR TRIM(config_json) = '' OR TRIM(config_json) = '{}' THEN
            '{"connection_mode":"websocket"}'
        WHEN POSITION('"connection_mode"' IN config_json) = 0 THEN
            '{"connection_mode":"websocket",' || SUBSTRING(config_json FROM 2)
        ELSE
            REPLACE(
                REPLACE(config_json,
                    '"connection_mode": "webhook"', '"connection_mode": "websocket"'),
                '"connection_mode":"webhook"', '"connection_mode":"websocket"'
            )
    END
WHERE channel_type = 'feishu'
  AND deleted = 0
  AND (
        config_json IS NULL
     OR (POSITION('"connection_mode": "websocket"' IN config_json) = 0
         AND POSITION('"connection_mode":"websocket"' IN config_json) = 0)
  );
