-- V99: register a DashScope OpenAI-compatible provider entry alongside the
-- existing native dashscope provider, plus the dot-versioned Qwen families
-- (qwen3.5-*, qwen3.6-*) that only ship on compatible-mode/v1.
--
-- Why a separate provider:
-- The dashscope provider runs on DashScopeChatModel (native protocol). Calling
-- a dot-versioned model id through the native text-generation/generation
-- endpoint returns 400 InvalidParameter — those models are only exposed via
-- the OpenAI-compatible endpoint. Rather than dynamically rewriting the
-- protocol per model, we register a sibling provider that uses
-- OpenAIChatModel against compatible-mode/v1 with the same sk- API key.
--
-- Existing seed file db/data-zh.sql already carries the same rows for fresh
-- installs; this migration is the upgrade path for already-deployed databases
-- (DatabaseBootstrapRunner skips the seed when mate_user is non-empty).

-- -- Provider --------------------------------------------------------------
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES (
  'dashscope-compat',
  'DashScope (兼容模式)',
  'sk-',
  'OpenAIChatModel',
  '',
  'https://dashscope.aliyuncs.com/compatible-mode/v1',
  '{}',
  FALSE, FALSE, TRUE, TRUE, TRUE, TRUE,
  NOW(), NOW()
);

-- -- Model catalog ---------------------------------------------------------
-- Dot-versioned Qwen families exposed through compatible-mode. IDs use the
-- 1000000601-1000000606 block reserved for this provider so future additions
-- under dashscope-compat can grow contiguously.
--
-- NB: only the {-plus, -vl-plus} variants are public on compatible-mode at the
-- time this migration was written. The {-max, -vl-max} variants exist in the
-- model marketplace but return 404 (`The model 'qwen3.6-max' does not exist
-- or you do not have access to it.`) for all general accounts. We seed only
-- the verified-callable ones; users with whitelist access can add the others
-- through Settings → Models manually.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
  (1000000601, 'Qwen3.6 Plus',  'dashscope-compat', 'qwen3.6-plus',  '通义千问 3.6 Plus 旗舰，平衡推理与速度（兼容模式专属）',          0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000603, 'Qwen3.5 Plus',  'dashscope-compat', 'qwen3.5-plus',  '通义千问 3.5 Plus（兼容模式专属）',                                0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000605, 'Qwen3 VL Plus', 'dashscope-compat', 'qwen3-vl-plus', '通义千问 3 视觉理解 Plus，支持图像、视频输入（兼容模式专属）',     0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);
