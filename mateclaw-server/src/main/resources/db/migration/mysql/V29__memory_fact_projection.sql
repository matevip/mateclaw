-- Dream v2 Phase 3: Fact projection tables (read-only derived from canonical)
-- Ref: rfc-038 §3.3

CREATE TABLE IF NOT EXISTS mate_fact (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id        BIGINT NOT NULL,
    source_ref      VARCHAR(512) NOT NULL,
    category        VARCHAR(64),
    subject         VARCHAR(256),
    predicate       VARCHAR(256),
    object_value    TEXT,
    confidence      DOUBLE DEFAULT 1.0,
    trust           DOUBLE DEFAULT 0.5,
    last_used_at    DATETIME,
    use_count       INT DEFAULT 0,
    extracted_by    VARCHAR(32) DEFAULT 'pattern',
    create_time     DATETIME NOT NULL,
    update_time     DATETIME NOT NULL,
    deleted         TINYINT DEFAULT 0,
    INDEX idx_fact_agent (agent_id, deleted),
    INDEX idx_fact_agent_source (agent_id, source_ref(255)),
    INDEX idx_fact_agent_subject (agent_id, subject(255))
);

CREATE TABLE IF NOT EXISTS mate_fact_entity_ref (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    fact_id         BIGINT NOT NULL,
    entity_name     VARCHAR(256) NOT NULL,
    entity_type     VARCHAR(64),
    role            VARCHAR(32) NOT NULL,
    create_time     DATETIME NOT NULL,
    INDEX idx_fact_ref_entity (entity_name(128), entity_type),
    INDEX idx_fact_ref_fact (fact_id)
);

CREATE TABLE IF NOT EXISTS mate_fact_contradiction (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id        BIGINT NOT NULL,
    fact_a_id       BIGINT NOT NULL,
    fact_b_id       BIGINT NOT NULL,
    description     TEXT,
    resolution      VARCHAR(32),
    resolved_at     DATETIME,
    resolved_by     VARCHAR(64),
    create_time     DATETIME NOT NULL,
    update_time     DATETIME NOT NULL,
    deleted         TINYINT DEFAULT 0,
    INDEX idx_contradiction_agent (agent_id, resolution)
);
