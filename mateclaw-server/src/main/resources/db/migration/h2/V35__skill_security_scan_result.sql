-- V35: RFC-042 §2.3 — persist skill security scan result and timestamp.
-- Until now findings lived only in SkillRuntimeStatus memory; after a restart
-- the admin page couldn't explain why a skill was blocked. These two columns
-- keep the last scan's findings (JSON) and time so the UI can render them
-- and offer a rescan control.
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS security_scan_result TEXT DEFAULT NULL;
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS security_scan_time DATETIME DEFAULT NULL;
