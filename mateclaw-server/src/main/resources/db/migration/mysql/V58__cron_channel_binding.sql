-- RFC-063r §2.9: bind a cron job to its originating channel (MySQL dialect).

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job' AND COLUMN_NAME = 'channel_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_cron_job ADD COLUMN channel_id BIGINT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job' AND COLUMN_NAME = 'delivery_config');
SET @s := IF(@c = 0, 'ALTER TABLE mate_cron_job ADD COLUMN delivery_config TEXT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job' AND INDEX_NAME = 'idx_cron_channel');
SET @s := IF(@c = 0, 'CREATE INDEX idx_cron_channel ON mate_cron_job(channel_id)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
