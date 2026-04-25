-- V40: RFC-051 PR-2 — page protection flag.
-- locked=1 blocks AI / tool / UI deletion and batch cleanup. Combined with
-- page_type='system' for the built-in overview / log pages.
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS locked TINYINT NOT NULL DEFAULT 0;
