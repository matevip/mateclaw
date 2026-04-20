-- Dream v2: candidate state machine fields (rfc-035 §4.1.4)
-- Phase 1 writes values only; filtering enabled in Phase 2.
-- MySQL does not support ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard.
SET @db_name = DATABASE();

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = 'mate_memory_recall' AND COLUMN_NAME = 'review_count');
SET @stmt = IF(@col_exists = 0,
    'ALTER TABLE mate_memory_recall ADD COLUMN review_count INT DEFAULT 0',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = 'mate_memory_recall' AND COLUMN_NAME = 'last_reviewed_at');
SET @stmt = IF(@col_exists = 0,
    'ALTER TABLE mate_memory_recall ADD COLUMN last_reviewed_at DATETIME',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
