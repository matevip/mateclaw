-- RFC-077 §4.1: track which user created an Agent, so members can delete
-- their own Agents without needing workspace admin role (issue #26 Bug B).

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_agent' AND COLUMN_NAME = 'creator_user_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_agent ADD COLUMN creator_user_id BIGINT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_agent' AND INDEX_NAME = 'idx_agent_creator_user');
SET @s := IF(@c = 0, 'CREATE INDEX idx_agent_creator_user ON mate_agent(creator_user_id)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
