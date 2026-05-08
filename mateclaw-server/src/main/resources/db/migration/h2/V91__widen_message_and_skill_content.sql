-- V91: Mirror MySQL widening of mate_message.content / content_parts and
-- mate_skill.skill_content. H2's TEXT is already CLOB (effectively unbounded)
-- so the change is a no-op semantically; it keeps both dialects in sync.

ALTER TABLE mate_message ALTER COLUMN content CLOB;
ALTER TABLE mate_message ALTER COLUMN content_parts CLOB;
ALTER TABLE mate_skill ALTER COLUMN skill_content CLOB;
