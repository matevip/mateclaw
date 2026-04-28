-- V56: register Volcano Ark Coding Plan as a separate provider with its own
-- pre-seeded model catalog.
--
-- Ark exposes a dedicated endpoint for the "Coding Plan" subscription —
-- ark.cn-beijing.volces.com/api/coding/v3 — distinct from the general
-- /api/v3 catalog. The same Volcano account / API key works against both
-- endpoints, but the model id sets are different (Coding Plan exposes
-- `ark-code-latest`, `doubao-seed-code`, coding-tuned Kimi/GLM, etc.).
-- Splitting into a sibling provider lets users keep a chat-tuned and a
-- coding-tuned default side by side.
--
-- The /v3 suffix is handled by the generalized OpenAI-compatible path
-- resolver — no special completionsPath override needed.

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES (
  'volcengine-plan',
  'Volcano Engine Coding Plan (火山方舟代码计划)',
  '',
  'OpenAIChatModel',
  '',
  'https://ark.cn-beijing.volces.com/api/coding/v3',
  '{}',
  FALSE, FALSE, TRUE, TRUE, TRUE, TRUE,
  NOW(), NOW()
);

-- Pre-seed the publicly listed Coding Plan model ids. Date suffixes are
-- part of the official id and must stay verbatim. Users can refresh via
-- discovery once the API key is set.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
  (1000000320, 'Ark Coding Plan',         'volcengine-plan', 'ark-code-latest',                 '方舟代码计划旗舰模型，256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000321, 'Doubao Seed Code',        'volcengine-plan', 'doubao-seed-code',                '豆包代码模型，256K 上下文',         0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000322, 'Doubao Seed Code Preview','volcengine-plan', 'doubao-seed-code-preview-251028', '豆包代码预览模型，256K 上下文',     0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000323, 'GLM 4.7 Coding',          'volcengine-plan', 'glm-4.7',                         'GLM 4.7 编码版（火山方舟托管），200K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000324, 'Kimi K2 Thinking',        'volcengine-plan', 'kimi-k2-thinking',                'Kimi K2 推理版（火山方舟托管），256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000325, 'Kimi K2.5 Coding',        'volcengine-plan', 'kimi-k2.5',                       'Kimi K2.5 编码版（火山方舟托管），256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);
