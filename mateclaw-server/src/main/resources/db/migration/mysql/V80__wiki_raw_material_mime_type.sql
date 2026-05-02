-- Adds the MIME type column to mate_wiki_raw_material so the upload pipeline
-- can route uploads to the right downstream extractor. Image source types
-- in particular need the original Content-Type to pick a vision provider
-- and to render previews correctly without re-sniffing the file.
--
-- MySQL does not support `ADD COLUMN IF NOT EXISTS`, so we guard via
-- INFORMATION_SCHEMA + a prepared statement to keep the migration
-- idempotent across re-runs and partial failures.

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE mate_wiki_raw_material ADD COLUMN mime_type VARCHAR(64)',
        'SELECT 1')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE table_schema = DATABASE()
      AND table_name   = 'mate_wiki_raw_material'
      AND column_name  = 'mime_type'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
