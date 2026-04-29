-- V38: Expand mate_wiki_chunk.content from TEXT (64KB) to MEDIUMTEXT (16MB)
-- TEXT max = 65,535 bytes; a 30,000-char Chinese chunk needs ~90,000 bytes (3 bytes/char UTF-8).
-- MEDIUMTEXT supports up to 16,777,215 bytes, safely covering any realistic chunk size.
SET @c := (SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'mate_wiki_chunk'
             AND COLUMN_NAME  = 'content');
SET @s := IF(@c = 'text',
             'ALTER TABLE mate_wiki_chunk MODIFY COLUMN content MEDIUMTEXT NOT NULL',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
