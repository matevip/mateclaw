-- V71: Register Tencent Hunyuan 3D provider for ai3d service.
-- See h2/V71 for full rationale.

INSERT INTO mate_model_provider (
    provider_id, name, api_key_prefix, chat_model, api_key, base_url,
    generate_kwargs, is_custom, is_local, support_model_discovery,
    support_connection_check, freeze_url, require_api_key, auth_type,
    create_time, update_time
) VALUES (
    'hunyuan-3d', '腾讯混元 3D', 'AKID', 'NotApplicable', '',
    'https://ai3d.tencentcloudapi.com',
    '{"service":"ai3d","version":"2025-05-13","region":"ap-guangzhou"}',
    0, 0, 0, 0, 1, 1, 'tc3_hmac_sha256',
    NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
    name             = VALUES(name),
    api_key_prefix   = VALUES(api_key_prefix),
    chat_model       = VALUES(chat_model),
    base_url         = VALUES(base_url),
    generate_kwargs  = VALUES(generate_kwargs),
    is_custom        = VALUES(is_custom),
    is_local         = VALUES(is_local),
    support_model_discovery = VALUES(support_model_discovery),
    support_connection_check = VALUES(support_connection_check),
    freeze_url       = VALUES(freeze_url),
    require_api_key  = VALUES(require_api_key),
    auth_type        = VALUES(auth_type),
    update_time      = NOW();
