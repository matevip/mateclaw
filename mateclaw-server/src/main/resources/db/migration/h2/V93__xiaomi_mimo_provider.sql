-- V93: register Xiaomi MiMo as an OpenAI-compatible provider with a
-- pre-seeded model catalog covering the MiMo-V2.5 and MiMo-V2 families.
--
-- Endpoint: https://api.xiaomimimo.com/v1 (OpenAI-compatible chat
-- completions schema). API keys issued by the Xiaomi MiMo platform are
-- accepted directly as bearer tokens; no special prefix is enforced.
-- Model discovery and connection check both follow the standard
-- OpenAI /v1/models contract, so they are enabled by default.

-- -- Provider --------------------------------------------------------------
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES (
  'xiaomi-mimo',
  'Xiaomi MiMo',
  '',
  'OpenAIChatModel',
  '',
  'https://api.xiaomimimo.com/v1',
  '{}',
  FALSE, FALSE, TRUE, TRUE, TRUE, TRUE,
  NOW(), NOW()
);

-- -- Model catalog ---------------------------------------------------------
-- Five entries covering the V2.5 and V2 families. Temperature defaults to
-- 0.7 to match peer OpenAI-compatible providers; max_tokens 4096 follows
-- the same conservative default used by other built-in entries.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
  (1000001200, 'MiMo V2.5 Pro', 'xiaomi-mimo', 'mimo-v2.5-pro', 'Xiaomi MiMo V2.5 Pro — latest flagship reasoning + coding model',         0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000001201, 'MiMo V2.5',     'xiaomi-mimo', 'mimo-v2.5',     'Xiaomi MiMo V2.5 — balanced model in the V2.5 family',                    0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000001202, 'MiMo V2 Pro',   'xiaomi-mimo', 'mimo-v2-pro',   'Xiaomi MiMo V2 Pro — 1M token context window flagship',                   0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000001203, 'MiMo V2 Omni',  'xiaomi-mimo', 'mimo-v2-omni',  'Xiaomi MiMo V2 Omni — multimodal variant supporting text, vision, audio', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000001204, 'MiMo V2 Flash', 'xiaomi-mimo', 'mimo-v2-flash', 'Xiaomi MiMo V2 Flash — fast, low-latency variant with 262K context',      0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);
