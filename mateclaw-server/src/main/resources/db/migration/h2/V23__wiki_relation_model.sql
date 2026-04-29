CREATE TABLE IF NOT EXISTS mate_wiki_page_citation (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    page_id       BIGINT        NOT NULL,
    chunk_id      BIGINT        NOT NULL,
    paragraph_idx INT           NOT NULL DEFAULT 0,
    anchor_text   VARCHAR(512),
    confidence    DECIMAL(4,3)  NOT NULL DEFAULT 1.000,
    created_by    VARCHAR(32)   NOT NULL DEFAULT 'system',
    create_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted       TINYINT       NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wpc_page  ON mate_wiki_page_citation (page_id);
CREATE INDEX IF NOT EXISTS idx_wpc_chunk ON mate_wiki_page_citation (chunk_id);

ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS page_type    VARCHAR(32) NOT NULL DEFAULT 'concept';
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS purpose_hint TEXT;
