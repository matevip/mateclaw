-- V7: Add last_processed_hash to mate_wiki_raw_material for skip-if-unchanged optimization
-- RFC-012 Change 5: when reprocessing, skip LLM pipeline if content_hash == last_processed_hash
ALTER TABLE mate_wiki_raw_material ADD COLUMN IF NOT EXISTS last_processed_hash VARCHAR(64) DEFAULT NULL;
