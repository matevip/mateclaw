-- RFC-063r §2.9: cron run delivery state machine (MySQL dialect).
-- See V57 H2 file for state-machine + design reasoning.
--
-- MySQL has no `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` —
-- guard each statement via INFORMATION_SCHEMA + dynamic SQL (project pattern
-- previously used in V4/V19/V44).

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run' AND COLUMN_NAME = 'delivery_status');
SET @s := IF(@c = 0, 'ALTER TABLE mate_cron_job_run ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT ''NONE''', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run' AND COLUMN_NAME = 'delivery_target');
SET @s := IF(@c = 0, 'ALTER TABLE mate_cron_job_run ADD COLUMN delivery_target VARCHAR(512)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run' AND COLUMN_NAME = 'delivery_error');
SET @s := IF(@c = 0, 'ALTER TABLE mate_cron_job_run ADD COLUMN delivery_error VARCHAR(500)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run' AND INDEX_NAME = 'idx_cron_run_pending_started');
SET @s := IF(@c = 0, 'CREATE INDEX idx_cron_run_pending_started ON mate_cron_job_run(delivery_status, started_at)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
