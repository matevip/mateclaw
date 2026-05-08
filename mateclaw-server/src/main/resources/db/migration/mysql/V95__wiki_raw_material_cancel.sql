-- V95: cancellation flag for in-progress wiki raw material processing.
-- Lets the user request a stop on a long-running PDF analysis (e.g. when
-- the embedding model has run out of credits) without having to delete
-- the raw material. The processing pipeline checks the flag at its
-- existing abort checkpoints and bails out with a 'cancelled' status.
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'cancel_requested');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_raw_material ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
