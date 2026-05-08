-- Persist the most recent dispatch outcome message on the trigger row
-- itself so the UI can show *why* a trigger has stopped firing without
-- joining trigger_event for forensics. The dispatcher writes a non-null
-- message on SKIPPED / FAILED outcomes and clears it on FIRED.

ALTER TABLE mate_trigger ADD COLUMN IF NOT EXISTS last_error VARCHAR(2048);
ALTER TABLE mate_trigger ADD COLUMN IF NOT EXISTS last_dispatched_at TIMESTAMP;
