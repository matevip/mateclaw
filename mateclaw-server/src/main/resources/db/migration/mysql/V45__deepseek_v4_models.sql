-- Add DeepSeek V4 (flash + pro) model entries for MySQL deployments.
-- Cross-dialect parity with h2/V45 — see that file's header for context.

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
(1000000282, 'DeepSeek V4 Flash', 'deepseek', 'deepseek-v4-flash', 'DeepSeek V4 Flash (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000283, 'DeepSeek V4 Pro', 'deepseek', 'deepseek-v4-pro', 'DeepSeek V4 Pro (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
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
