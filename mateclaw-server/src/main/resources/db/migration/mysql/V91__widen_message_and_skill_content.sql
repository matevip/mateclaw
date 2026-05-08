-- V91: Widen mate_message.content / content_parts and mate_skill.skill_content
-- from TEXT (64KB) to MEDIUMTEXT (16MB).
--
-- TEXT caps at 65,535 bytes. A multi-turn ReAct session accumulates tool calls
-- and observations into content_parts JSON well past that cap, and a long
-- Chinese final answer (~22k chars × 3 bytes UTF-8) overflows `content`.
-- The truncation rejects the assistant message INSERT after the SSE stream
-- has already finished, so users see the reply live but it disappears on
-- page reload (only the user message survives in the DB).
--
-- Idempotent: only modifies the column when its current type is still TEXT.

SET @c := (SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'mate_message'
             AND COLUMN_NAME  = 'content');
SET @s := IF(@c = 'text',
             'ALTER TABLE mate_message MODIFY COLUMN content MEDIUMTEXT',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'mate_message'
             AND COLUMN_NAME  = 'content_parts');
SET @s := IF(@c = 'text',
             'ALTER TABLE mate_message MODIFY COLUMN content_parts MEDIUMTEXT',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'mate_skill'
             AND COLUMN_NAME  = 'skill_content');
SET @s := IF(@c = 'text',
             'ALTER TABLE mate_skill MODIFY COLUMN skill_content MEDIUMTEXT',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
