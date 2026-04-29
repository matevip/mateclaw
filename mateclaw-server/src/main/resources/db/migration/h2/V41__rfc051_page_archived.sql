-- V41: RFC-051 PR-7 — soft-archive flag.
-- archived=1 hides a page from list / search / related results without
-- destroying it, so re-ingesting the source raw doesn't regenerate it.
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS archived TINYINT NOT NULL DEFAULT 0;
