-- V38: Expand mate_wiki_chunk.content to CLOB (H2 equivalent of MEDIUMTEXT)
-- H2's TEXT is already effectively unbounded, but align with MySQL change for consistency.
ALTER TABLE mate_wiki_chunk ALTER COLUMN content CLOB NOT NULL;
