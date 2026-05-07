-- V95: cancellation flag for in-progress wiki raw material processing.
-- Lets the user request a stop on a long-running PDF analysis (e.g. when
-- the embedding model has run out of credits) without having to delete
-- the raw material. The processing pipeline checks the flag at its
-- existing abort checkpoints and bails out with a 'cancelled' status.
ALTER TABLE mate_wiki_raw_material ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
