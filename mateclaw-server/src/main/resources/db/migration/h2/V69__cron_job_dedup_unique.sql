-- Issue #50: deduplicate accumulated cron jobs and prevent future duplicates
-- at the DB level.
--
-- Step 1 — purge duplicate active rows, keeping the earliest id (= earliest
-- creation, since IDs are snowflake-monotonic). Hard delete because this
-- entity has no @TableLogic; deleteById() already performs physical deletes.
DELETE FROM mate_cron_job
WHERE id NOT IN (
    SELECT keep_id FROM (
        SELECT MIN(id) AS keep_id
        FROM mate_cron_job
        GROUP BY workspace_id, agent_id, name
    )
);

-- Step 2 — enforce uniqueness so concurrent LLM-driven creates can't
-- re-introduce duplicates. The service layer also dedups in-process; this
-- index is the racy-write safety net.
CREATE UNIQUE INDEX IF NOT EXISTS uk_cron_job_workspace_agent_name
    ON mate_cron_job(workspace_id, agent_id, name);
