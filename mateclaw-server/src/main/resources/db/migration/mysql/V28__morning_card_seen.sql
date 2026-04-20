-- Dream v2 Phase 2b: Morning Card seen state per (user, agent)
-- Ref: rfc-034 F5 — DO NOT add to mate_user; use separate table
CREATE TABLE IF NOT EXISTS mate_morning_card_seen (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    agent_id        BIGINT NOT NULL,
    last_seen_at    DATETIME NOT NULL,
    last_report_id  BIGINT,
    create_time     DATETIME NOT NULL,
    update_time     DATETIME NOT NULL,
    UNIQUE KEY uk_user_agent (user_id, agent_id)
);
