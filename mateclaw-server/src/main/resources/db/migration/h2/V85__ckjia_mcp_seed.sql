-- Seed default ckjia-shopping MCP server config (disabled by default).
-- Admin enables in Settings > MCP Connections after pointing url to their
-- ckjia instance and configuring CKJIA_MCP_KEY env var for authorization.
--
-- Column name is `url` (not `endpoint`) per McpServerEntity.
-- headers_json uses ${CKJIA_MCP_KEY} placeholder so the plaintext API key
-- never lands in the database (parseHeaders expands env vars at request time).

-- mate_mcp_server.id is BIGINT NOT NULL PRIMARY KEY without H2-side auto-increment;
-- production uses MyBatis Plus Snowflake (@TableId IdType.ASSIGN_ID) at insert time,
-- but Flyway bypasses that path so seed rows must supply an explicit id. Following
-- the existing seed convention (1000000901 = filesystem, 1000000902 = github),
-- ckjia-shopping takes the next free slot 1000000903.
MERGE INTO mate_mcp_server (
    id, name, transport, url, headers_json, enabled, description,
    connect_timeout_seconds, read_timeout_seconds, builtin, create_time, update_time, deleted
) KEY(name)
VALUES (
    1000000903,
    'ckjia-shopping',
    'sse',
    'http://localhost:8085/sse',
    '{"Authorization": "Bearer ${CKJIA_MCP_KEY}"}',
    FALSE,
    'CKJIA cross-platform price comparison MCP server (Taobao/JD/Tmall/Pinduoduo).',
    30, 30, TRUE,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
);
