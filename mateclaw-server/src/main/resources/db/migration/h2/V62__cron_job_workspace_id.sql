-- RFC-083: workspace-isolate cron jobs
-- (issue: https://github.com/matevip/mateclaw/issues/37).

ALTER TABLE mate_cron_job ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_cron_job_workspace ON mate_cron_job(workspace_id, deleted);
