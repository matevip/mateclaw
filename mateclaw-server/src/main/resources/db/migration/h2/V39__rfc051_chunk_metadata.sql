-- V39: RFC-051 PR-1a — chunk structural metadata.
-- All columns are nullable; NULL means "unknown" for legacy chunks. token_count
-- backfill happens asynchronously via WikiChunkTokenBackfillJob.
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS page_number INT DEFAULT NULL;
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS token_count INT DEFAULT NULL;
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS header_breadcrumb VARCHAR(1024) DEFAULT NULL;
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS source_section VARCHAR(512) DEFAULT NULL;
