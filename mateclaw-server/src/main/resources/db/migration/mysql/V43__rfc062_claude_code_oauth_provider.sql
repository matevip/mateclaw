-- RFC-062: Seed the Anthropic Claude Code OAuth provider + its Claude 4.7
-- model bindings on existing deployments. New installs already get these
-- rows from data-mysql-{en,zh}.sql via DatabaseBootstrapRunner; this
-- migration is for operators upgrading from <= V42.
--
-- INSERT ... ON DUPLICATE KEY UPDATE is the MySQL idempotent upsert.
-- Same V number is used in h2/ for cross-dialect parity.

INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, auth_type, create_time, update_time)
VALUES ('anthropic-claude-code', 'Anthropic Claude Code (OAuth)', '', 'ClaudeCodeChatModel', '', 'https://api.anthropic.com', '{}', FALSE, FALSE, FALSE, FALSE, TRUE, FALSE, 'oauth', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    chat_model = VALUES(chat_model),
    base_url = VALUES(base_url),
    auth_type = VALUES(auth_type),
    update_time = VALUES(update_time);

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
(1000000280, 'Claude Opus 4.7', 'anthropic-claude-code', 'claude-opus-4-7', 'Claude Opus 4.7 via Claude Code Pro/Max subscription', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000281, 'Claude Sonnet 4.7', 'anthropic-claude-code', 'claude-sonnet-4-7', 'Claude Sonnet 4.7 via Claude Code Pro/Max subscription', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    provider = VALUES(provider),
    model_name = VALUES(model_name),
    description = VALUES(description),
    temperature = VALUES(temperature),
    max_tokens = VALUES(max_tokens),
    top_p = VALUES(top_p),
    builtin = VALUES(builtin),
    enabled = VALUES(enabled),
    is_default = VALUES(is_default),
    update_time = VALUES(update_time),
    deleted = VALUES(deleted);
