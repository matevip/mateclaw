-- V90: register coding-plan subscription endpoints as separate providers with
-- their own pre-seeded model catalogs.
--
-- Two vendors are covered:
--
-- 1. Zhipu (BigModel / Z.AI) — dedicated subscription endpoints alongside the
--    regular /api/paas/v4 catalogs:
--      * China:         https://open.bigmodel.cn/api/coding/paas/v4
--      * International: https://api.z.ai/api/coding/paas/v4
--
-- 2. Aliyun DashScope Coding Plan — international sibling of the existing
--    aliyun-codingplan provider:
--      * International: https://coding-intl.dashscope.aliyuncs.com/v1
--
-- All subscription endpoints accept the same OpenAI-compatible chat
-- completions schema; only the base URL changes between regions, and the
-- same account / API key works against both regions of a given vendor.
-- Splitting into sibling providers (alongside zhipu-cn / zhipu-intl /
-- aliyun-codingplan) lets a user keep a chat-tuned and a coding-tuned
-- default side by side — matching the pattern already established by
-- volcengine-plan.

-- -- Zhipu Coding Plan (China) ----------------------------------------------
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES (
  'zhipu-cn-codingplan',
  'Zhipu Coding Plan (BigModel)',
  '',
  'OpenAIChatModel',
  '',
  'https://open.bigmodel.cn/api/coding/paas/v4',
  '{"completionsPath":"/chat/completions"}',
  FALSE, FALSE, FALSE, FALSE, TRUE, TRUE,
  NOW(), NOW()
);

-- -- Zhipu Coding Plan (International) ---------------------------------------
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES (
  'zhipu-intl-codingplan',
  'Zhipu Coding Plan (Z.AI)',
  '',
  'OpenAIChatModel',
  '',
  'https://api.z.ai/api/coding/paas/v4',
  '{"completionsPath":"/chat/completions"}',
  FALSE, FALSE, FALSE, FALSE, TRUE, TRUE,
  NOW(), NOW()
);

-- -- Aliyun DashScope Coding Plan (International) ---------------------------
-- Sibling of the existing 'aliyun-codingplan' (China) entry seeded in
-- data-zh.sql / data-mysql-zh.sql. Same flag set, same api_key_prefix.
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES (
  'aliyun-codingplan-intl',
  'Aliyun Coding Plan (International)',
  'sk-sp',
  'OpenAIChatModel',
  '',
  'https://coding-intl.dashscope.aliyuncs.com/v1',
  '{}',
  FALSE, FALSE, FALSE, FALSE, TRUE, TRUE,
  NOW(), NOW()
);

-- -- Zhipu Coding Plan model catalog ----------------------------------------
-- Pre-seed the GLM model ids the coding subscription accepts. Vision-only
-- variants (glm-5v-*) are intentionally omitted — the coding subscription
-- targets text/code workloads. Temperature defaults to 0.2 to favour
-- deterministic output for code generation.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
  (1000000230, 'GLM-5 Coding',       'zhipu-cn-codingplan', 'glm-5',       '智谱编码套餐 — GLM-5 旗舰',       0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000231, 'GLM-5.1 Coding',     'zhipu-cn-codingplan', 'glm-5.1',     '智谱编码套餐 — GLM-5.1 最新旗舰', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000232, 'GLM-5-Turbo Coding', 'zhipu-cn-codingplan', 'glm-5-turbo', '智谱编码套餐 — GLM-5 高速版',     0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000233, 'GLM-4.7 Coding',     'zhipu-cn-codingplan', 'glm-4.7',     '智谱编码套餐 — GLM-4.7',           0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000234, 'GLM-5 Coding',       'zhipu-intl-codingplan', 'glm-5',       'Zhipu Coding Plan — GLM-5 flagship',          0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000235, 'GLM-5.1 Coding',     'zhipu-intl-codingplan', 'glm-5.1',     'Zhipu Coding Plan — GLM-5.1 latest flagship', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000236, 'GLM-5-Turbo Coding', 'zhipu-intl-codingplan', 'glm-5-turbo', 'Zhipu Coding Plan — GLM-5 fast variant',      0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000237, 'GLM-4.7 Coding',     'zhipu-intl-codingplan', 'glm-4.7',     'Zhipu Coding Plan — GLM-4.7',                 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);

-- -- Aliyun Coding Plan model catalog (cn backfill + intl mirror) ----------
-- Backfill: the existing aliyun-codingplan (cn) seed in data-zh.sql is
-- missing qwen3.6-plus, the current Qwen flagship in the coding subscription.
-- Add it here so the cn entry stays parallel with intl.
--
-- Then mirror the full coding-plan catalog onto the new intl provider. Both
-- regions accept the same model ids; only the base URL differs.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
  -- Backfill: qwen3.6-plus on aliyun-codingplan (cn).
  (1000000162, 'Qwen3.6 Plus',         'aliyun-codingplan',      'qwen3.6-plus',         '阿里云编码套餐 — Qwen3.6 Plus 旗舰',       0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  -- Aliyun Coding Plan (International) catalog — mirrors the cn 8 + the
  -- backfilled qwen3.6-plus.
  (1000000241, 'Qwen3.6 Plus',         'aliyun-codingplan-intl', 'qwen3.6-plus',         'Aliyun Coding Plan (Intl) — Qwen3.6 Plus flagship',          0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000242, 'Qwen3.5 Plus',         'aliyun-codingplan-intl', 'qwen3.5-plus',         'Aliyun Coding Plan (Intl) — Qwen3.5 balanced',               0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000243, 'GLM-5',                'aliyun-codingplan-intl', 'glm-5',                'Aliyun Coding Plan (Intl) — GLM-5 hosted on DashScope',      0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000244, 'GLM-4.7',              'aliyun-codingplan-intl', 'glm-4.7',              'Aliyun Coding Plan (Intl) — GLM-4.7 hosted on DashScope',    0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000245, 'MiniMax M2.5',         'aliyun-codingplan-intl', 'MiniMax-M2.5',         'Aliyun Coding Plan (Intl) — MiniMax M2.5 hosted on DashScope', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000246, 'Kimi K2.5',            'aliyun-codingplan-intl', 'kimi-k2.5',            'Aliyun Coding Plan (Intl) — Kimi K2.5 hosted on DashScope',  0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000247, 'Qwen3 Max 2026-01-23', 'aliyun-codingplan-intl', 'qwen3-max-2026-01-23', 'Aliyun Coding Plan (Intl) — Qwen3 Max pinned snapshot',      0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000248, 'Qwen3 Coder Next',     'aliyun-codingplan-intl', 'qwen3-coder-next',     'Aliyun Coding Plan (Intl) — Qwen3 Coder Next, agentic coding', 0.2, 8192, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000249, 'Qwen3 Coder Plus',     'aliyun-codingplan-intl', 'qwen3-coder-plus',     'Aliyun Coding Plan (Intl) — Qwen3 Coder Plus, agentic coding', 0.2, 8192, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);
