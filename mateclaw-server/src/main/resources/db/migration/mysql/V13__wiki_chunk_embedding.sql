-- V13: Add embedding column to mate_wiki_chunk (RFC-011 Phase 2)
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS embedding BLOB DEFAULT NULL;
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64) DEFAULT NULL;
