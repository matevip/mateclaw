-- Issue #50 follow-up: V69 dedup grouped MIN(id) without filtering deleted=0.
-- If a (workspace_id, agent_id, name) tuple had [deleted=1 row id=100,
-- deleted=0 row id=200], V69 kept id=100 and physically removed id=200,
-- making the active job invisible to the runtime (which queries deleted=0).
-- CronJobEntity.deleted is declared but never set by current code, so
-- defensively treat any deleted=1 row as stale and remove it physically.

-- Step 1: physically purge any deleted=1 rows. The cron service treats
-- the entity as hard-delete-only (no @TableLogic, no global logic-delete
-- config), so any deleted=1 rows are legacy artifacts and unsafe to keep.
DELETE FROM mate_cron_job WHERE deleted = 1;

-- Step 2: idempotent re-dedup against active rows only. If V69 already
-- left the table clean, this is a no-op. If V69 picked a deleted=1 row
-- as the survivor and Step 1 just removed it, an active duplicate may
-- still need re-converging.
DELETE FROM mate_cron_job
WHERE deleted = 0
  AND id NOT IN (
    SELECT keep_id FROM (
        SELECT MIN(id) AS keep_id
        FROM mate_cron_job
        WHERE deleted = 0
        GROUP BY workspace_id, agent_id, name
    )
  );
