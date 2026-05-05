-- V66: Per-model capability declaration (issue #44)
-- Optional JSON array such as ["vision","video","audio"]; NULL falls back to
-- ModelCapabilityService built-in heuristics.
ALTER TABLE mate_model_config ADD COLUMN IF NOT EXISTS modalities VARCHAR(512) DEFAULT NULL;
