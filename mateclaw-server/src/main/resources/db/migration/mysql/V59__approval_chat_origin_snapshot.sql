-- RFC-063r §2.12: persist ChatOrigin Memento snapshot on approval (MySQL dialect).

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_tool_approval' AND COLUMN_NAME = 'chat_origin');
SET @s := IF(@c = 0, 'ALTER TABLE mate_tool_approval ADD COLUMN chat_origin TEXT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
