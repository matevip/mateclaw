-- RFC-077 §4.1: track which user created an Agent, so members can delete
-- their own Agents without needing workspace admin role (issue #26 Bug B).

ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS creator_user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_agent_creator_user ON mate_agent(creator_user_id);
