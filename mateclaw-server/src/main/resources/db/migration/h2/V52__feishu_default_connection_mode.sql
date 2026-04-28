-- V52: Intentional no-op (deprecated).
--
-- Original intent: flip every Feishu channel's connection_mode to "websocket".
-- The first cut of this migration matched the compact JSON form
-- '"connection_mode":"webhook"' but the form UI persists configJson via
-- JSON.stringify(cfg, null, 2), which produces '"connection_mode": "webhook"'
-- (with a space after the colon). V52 ran in 4 ms, updated zero rows, and
-- got marked "successfully applied" in flyway_schema_history. Flyway's
-- repair() refuses to re-run a successful migration even after the SQL is
-- corrected, so the original V52 is effectively dead on every install that
-- already ran it.
--
-- The actual migration was moved to V53. V52 stays here as a no-op so
-- existing schema_history records remain valid; FlywayRepairConfig will
-- align checksums on the next startup.
--
-- The SELECT below is a portable no-op that satisfies Flyway's "at least
-- one statement" requirement on both H2 and MySQL.

SELECT 1;
