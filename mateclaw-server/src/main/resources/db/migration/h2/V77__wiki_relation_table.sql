-- mate_wiki_relation: persistent cache of page-to-page multi-signal relations.
--
-- Distinct from mate_wiki_page_citation, which models page-to-chunk citations.
-- A row here represents one directed (or undirected, see notes below) edge in
-- the wiki page graph for a given knowledge base, materializing:
--   * the aggregate relevance score across registered signal strategies
--     (direct link, shared chunk, shared raw, semantic similarity, ...)
--   * a per-signal breakdown for explainability
--   * an optional taxonomy tag (mention / cite / supports / contradicts /
--     extends) populated by the planning stage of the compile pipeline
--   * confidence + evidence snippets sourced from the same compile output
--   * cache invalidation metadata so readers can decide whether to recompute

CREATE TABLE IF NOT EXISTS mate_wiki_relation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id           BIGINT NOT NULL,
    page_a_id       BIGINT NOT NULL,
    page_b_id       BIGINT NOT NULL,

    -- Aggregate relevance score (sum of weighted signal contributions).
    total_score     DECIMAL(8, 4),

    -- Per-signal breakdown, e.g. {"direct_link":2.0,"shared_chunk":5.0}.
    signals_json    CLOB,

    -- Relation type taxonomy: mention | cite | supports | contradicts | extends.
    type            VARCHAR(32),

    -- Confidence taxonomy: EXTRACTED | INFERRED | AMBIGUOUS | UNVERIFIED.
    confidence      VARCHAR(16),

    -- Verbatim or paraphrased justification (≤ 500 chars enforced in Java layer).
    evidence        CLOB,

    -- When evidence is a quote, the raw material id it was pulled from.
    evidence_raw_id BIGINT,

    -- Provenance tag: llm-extracted | wikilink-context | manual.
    source          VARCHAR(32),

    -- Cache invalidation metadata.
    computed_at     TIMESTAMP,
    computed_hash   VARCHAR(64),

    -- Standard rows.
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT       NOT NULL DEFAULT 0
);

-- Unique edge identity within a KB (deleted column included so soft-deleted
-- rows can coexist with re-inserted ones during re-compute cycles).
CREATE UNIQUE INDEX IF NOT EXISTS uk_wr_pair
    ON mate_wiki_relation (kb_id, page_a_id, page_b_id, deleted);

-- "Top-K related to page X" queries.
CREATE INDEX IF NOT EXISTS idx_wr_page_a
    ON mate_wiki_relation (kb_id, page_a_id, total_score DESC);

-- KB-wide ranking queries (e.g., strongest edges across the graph).
CREATE INDEX IF NOT EXISTS idx_wr_kb_score
    ON mate_wiki_relation (kb_id, total_score DESC);

-- Cache invalidator scans (e.g., "everything older than X").
CREATE INDEX IF NOT EXISTS idx_wr_computed_at
    ON mate_wiki_relation (computed_at);
