-- Issue #50: deduplicate accumulated cron jobs and prevent future duplicates
-- at the DB level. MySQL doesn't allow DELETE with a subquery that scans the
-- same table directly, so we use the LEFT JOIN + IS NULL pattern.
--
-- Step 1 — purge duplicate active rows, keeping the earliest id per
-- (workspace_id, agent_id, name). Hard delete because this entity has no
-- @TableLogic; deleteById() already performs physical deletes.
DELETE t FROM mate_cron_job t
LEFT JOIN (
    SELECT MIN(id) AS keep_id
    FROM mate_cron_job
    GROUP BY workspace_id, agent_id, name
) k ON t.id = k.keep_id
WHERE k.keep_id IS NULL;

-- Step 2 — add the unique index, idempotent via INFORMATION_SCHEMA guard
-- (MySQL < 8.0.29 has no CREATE INDEX IF NOT EXISTS).
SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'mate_cron_job'
                      AND INDEX_NAME = 'uk_cron_job_workspace_agent_name');
SET @stmt := IF(@idx_exists = 0,
                'CREATE UNIQUE INDEX uk_cron_job_workspace_agent_name ON mate_cron_job(workspace_id, agent_id, name)',
                'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
