-- mate_feature_flag: runtime-toggleable feature flag store.
--
-- Each row defines one named flag with optional KB / user whitelists and
-- a percentage rollout. Reads go through an in-memory cache that refreshes
-- on a 30-second timer (and immediately on admin write); the cache is
-- per-instance so multi-instance deployments converge within one tick.

CREATE TABLE IF NOT EXISTS mate_feature_flag (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    flag_key            VARCHAR(128) NOT NULL UNIQUE,

    -- Master switch. When false, isEnabled() returns false regardless of whitelists.
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Free-form note shown in the admin UI; not consumed by code paths.
    description         VARCHAR(512),

    -- Comma-separated KB ids; NULL/empty means the flag applies to all KBs.
    whitelist_kb_ids    CLOB,

    -- Comma-separated user ids; NULL/empty means the flag applies to all users.
    whitelist_user_ids  CLOB,

    -- Hash-based gradual rollout (0..100). Only consulted when both whitelists
    -- are empty; otherwise whitelist matching wins.
    rollout_percent     INT          DEFAULT 0,

    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_mff_key_enabled
    ON mate_feature_flag (flag_key, enabled, deleted);

-- Seed wiki feature flags with safe defaults.
-- New capabilities ship disabled and are turned on via admin API or via
-- editing whitelist_kb_ids during gradual rollout.
MERGE INTO mate_feature_flag (flag_key, enabled, description) KEY (flag_key) VALUES
    ('wiki.ocr.enabled',                FALSE, 'Image OCR / vision-in pipeline for wiki uploads'),
    ('wiki.compile.4stage.enabled',     FALSE, 'Four-stage knowledge base compilation pipeline'),
    ('wiki.compile.cache.enabled',      FALSE, 'Prompt cache layer for the wiki compile pipeline'),
    ('wiki.confidence.enabled',         FALSE, 'Confidence taxonomy on wiki relations and pages'),
    ('wiki.hot_cache.enabled',          FALSE, 'KB-level recent-activity snapshot injected into agent system prompt'),
    ('wiki.graph.insights.enabled',     FALSE, 'Wiki graph insights panel (surprising connections, gaps, bridges)'),
    ('wiki.graph.adamic_adar.enabled',  FALSE, 'Adamic-Adar graph signal (additive to existing four signals)'),
    ('wiki.graph.boundary.enabled',     FALSE, 'Boundary score for surfacing dangling pages'),
    ('wiki.relation.cache.enabled',     TRUE,  'Persistent cache for wiki page-to-page relation computation');
