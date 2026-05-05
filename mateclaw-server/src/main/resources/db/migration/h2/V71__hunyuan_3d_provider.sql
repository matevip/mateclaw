-- V71: Register Tencent Hunyuan 3D provider for ai3d service.
-- The api_key column carries "SecretId:SecretKey" (colon-joined) — same
-- two-part credential pattern as Kling. base_url defaults to the auto-routed
-- ai3d.tencentcloudapi.com host but can be pointed at a regional endpoint
-- (e.g. ai3d.ap-guangzhou.tencentcloudapi.com) when the operator wants to pin.
--
-- chat_model is intentionally non-empty (a placeholder marker for the
-- generic ProviderInitProbe) — Hunyuan 3D is not actually a chat-completions
-- model, but the provider table currently requires the column. The probe
-- skips providers tagged is_local=TRUE / freeze_url=TRUE for chat liveness,
-- so the freeze_url=TRUE flag below also keeps the LLM failover chain from
-- ever attempting to dispatch chat traffic here.

MERGE INTO mate_model_provider (
    provider_id, name, api_key_prefix, chat_model, api_key, base_url,
    generate_kwargs, is_custom, is_local, support_model_discovery,
    support_connection_check, freeze_url, require_api_key, auth_type,
    create_time, update_time
)
KEY (provider_id)
VALUES (
    'hunyuan-3d', '腾讯混元 3D', 'AKID', 'NotApplicable', '',
    'https://ai3d.tencentcloudapi.com',
    '{"service":"ai3d","version":"2025-05-13","region":"ap-guangzhou"}',
    FALSE, FALSE, FALSE, FALSE, TRUE, TRUE, 'tc3_hmac_sha256',
    NOW(), NOW()
);
