-- See the H2 file for context. MySQL 8.0 doesn't support
-- `ADD COLUMN IF NOT EXISTS`, so the existence check goes through
-- INFORMATION_SCHEMA + a prepared statement.

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_trigger'
      AND COLUMN_NAME = 'last_error'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_trigger ADD COLUMN last_error VARCHAR(2048)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_trigger'
      AND COLUMN_NAME = 'last_dispatched_at'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_trigger ADD COLUMN last_dispatched_at TIMESTAMP NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
