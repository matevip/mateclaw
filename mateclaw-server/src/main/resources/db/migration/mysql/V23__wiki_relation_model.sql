CREATE TABLE IF NOT EXISTS mate_wiki_page_citation (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    page_id       BIGINT        NOT NULL,
    chunk_id      BIGINT        NOT NULL,
    paragraph_idx INT           NOT NULL DEFAULT 0,
    anchor_text   VARCHAR(512),
    confidence    DECIMAL(4,3)  NOT NULL DEFAULT 1.000,
    created_by    VARCHAR(32)   NOT NULL DEFAULT 'system',
    create_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted       TINYINT       NOT NULL DEFAULT 0,
    INDEX idx_wpc_page  (page_id),
    INDEX idx_wpc_chunk (chunk_id)
);

SET @c1 = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
    AND COLUMN_NAME = 'page_type');
SET @s1 = IF(@c1 = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN page_type VARCHAR(32) NOT NULL DEFAULT ''concept''',
    'SELECT 1');
PREPARE p1 FROM @s1; EXECUTE p1; DEALLOCATE PREPARE p1;

SET @c2 = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
    AND COLUMN_NAME = 'purpose_hint');
SET @s2 = IF(@c2 = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN purpose_hint TEXT',
    'SELECT 1');
PREPARE p2 FROM @s2; EXECUTE p2; DEALLOCATE PREPARE p2;
