-- RFC-083: workspace-isolate cron jobs
-- (issue: https://github.com/matevip/mateclaw/issues/37).

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'mate_cron_job'
                      AND COLUMN_NAME = 'workspace_id');
SET @stmt := IF(@col_exists = 0,
                'ALTER TABLE mate_cron_job ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1',
                'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'mate_cron_job'
                      AND INDEX_NAME = 'idx_cron_job_workspace');
SET @stmt := IF(@idx_exists = 0,
                'CREATE INDEX idx_cron_job_workspace ON mate_cron_job(workspace_id, deleted)',
                'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
