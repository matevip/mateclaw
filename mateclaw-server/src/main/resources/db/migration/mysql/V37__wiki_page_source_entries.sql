-- RFC-047 P2: Add source_entries column to mate_wiki_page for paired (rawId, rawTitle) lineage.
-- Paired entries guarantee title-rawId alignment even when raw titles change.
-- Dual-written alongside the existing source_raw_ids for backwards compatibility.

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'mate_wiki_page'
      AND COLUMN_NAME  = 'source_entries'
);

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN source_entries JSON NULL COMMENT ''JSON array of {rawId, rawTitle} pairs — canonical source lineage (RFC-047)''',
    'SELECT 1'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
