-- RFC-047 P2: Add source_entries column to mate_wiki_page for paired (rawId, rawTitle) lineage.
-- Paired entries guarantee title-rawId alignment even when raw titles change.
-- Dual-written alongside the existing source_raw_ids for backwards compatibility.

ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS source_entries VARCHAR(4096) NULL
    COMMENT 'JSON array of {rawId, rawTitle} pairs — canonical source lineage (RFC-047)';
