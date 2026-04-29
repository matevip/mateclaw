-- Dream v2 Phase 3: Fact projection tables (read-only derived from canonical)
-- Ref: rfc-038 §3.3

CREATE TABLE IF NOT EXISTS mate_fact (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id        BIGINT NOT NULL,
    source_ref      VARCHAR(512) NOT NULL,   -- canonical source: "structured/user.md#section_key" or "MEMORY.md#heading"
    category        VARCHAR(64),             -- user_pref, project, tool, general
    subject         VARCHAR(256),            -- entity subject
    predicate       VARCHAR(256),            -- relationship or property
    object_value    TEXT,                    -- entity object or value
    confidence      DOUBLE DEFAULT 1.0,      -- extraction confidence [0..1]
    trust           DOUBLE DEFAULT 0.5,      -- derived trust score (feedback + decay)
    -- Accumulated columns (NOT overwritten by projection rebuild)
    last_used_at    DATETIME,
    use_count       INT DEFAULT 0,
    -- Metadata
    extracted_by    VARCHAR(32) DEFAULT 'pattern',  -- pattern | llm
    create_time     DATETIME NOT NULL,
    update_time     DATETIME NOT NULL,
    deleted         TINYINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_fact_agent ON mate_fact(agent_id, deleted);
CREATE INDEX IF NOT EXISTS idx_fact_agent_source ON mate_fact(agent_id, source_ref);
CREATE INDEX IF NOT EXISTS idx_fact_agent_subject ON mate_fact(agent_id, subject);

-- Entity references (for multi-hop queries)
CREATE TABLE IF NOT EXISTS mate_fact_entity_ref (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    fact_id         BIGINT NOT NULL,
    entity_name     VARCHAR(256) NOT NULL,
    entity_type     VARCHAR(64),             -- person, tool, project, concept
    role            VARCHAR(32) NOT NULL,    -- subject | object
    create_time     DATETIME NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fact_ref_entity ON mate_fact_entity_ref(entity_name, entity_type);
CREATE INDEX IF NOT EXISTS idx_fact_ref_fact ON mate_fact_entity_ref(fact_id);

-- Contradiction tracking (populated by Dream contradiction detection step)
CREATE TABLE IF NOT EXISTS mate_fact_contradiction (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id        BIGINT NOT NULL,
    fact_a_id       BIGINT NOT NULL,
    fact_b_id       BIGINT NOT NULL,
    description     TEXT,
    resolution      VARCHAR(32),             -- null | KEEP_A | KEEP_B | MERGE | IGNORE
    resolved_at     DATETIME,
    resolved_by     VARCHAR(64),
    create_time     DATETIME NOT NULL,
    update_time     DATETIME NOT NULL,
    deleted         TINYINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_contradiction_agent ON mate_fact_contradiction(agent_id, resolution);
