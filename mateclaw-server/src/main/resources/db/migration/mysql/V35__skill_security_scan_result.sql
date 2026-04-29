-- V35: RFC-042 §2.3 — persist skill security scan result and timestamp.
-- Until now findings lived only in SkillRuntimeStatus memory; after a restart
-- the admin page couldn't explain why a skill was blocked. These two columns
-- keep the last scan's findings (JSON) and time so the UI can render them
-- and offer a rescan control.
--
-- MySQL has no `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guards so
-- the migration is idempotent across redeploys.

SET @c1 := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'mate_skill'
              AND COLUMN_NAME = 'security_scan_result');
SET @s1 := IF(@c1 = 0,
              'ALTER TABLE mate_skill ADD COLUMN security_scan_result TEXT DEFAULT NULL',
              'SELECT 1');
PREPARE stmt1 FROM @s1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

SET @c2 := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'mate_skill'
              AND COLUMN_NAME = 'security_scan_time');
SET @s2 := IF(@c2 = 0,
              'ALTER TABLE mate_skill ADD COLUMN security_scan_time DATETIME DEFAULT NULL',
              'SELECT 1');
PREPARE stmt2 FROM @s2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;
