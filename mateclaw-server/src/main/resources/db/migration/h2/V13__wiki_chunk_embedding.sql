-- V13: Add embedding column to mate_wiki_chunk (RFC-011 Phase 2)
-- float32[] serialized as little-endian byte[], stored in BLOB
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS embedding BLOB DEFAULT NULL;
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64) DEFAULT NULL;
