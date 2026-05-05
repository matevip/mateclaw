-- V72: Register the three Tencent Hunyuan 3D model variants in
-- mate_model_config so the "Models & Credentials" provider card surfaces
-- them in the picker. Hunyuan 3D is not a chat-completions model;
-- model_type='model3d' (consistent with model_type='image' / 'video' used
-- for generative non-chat providers — see V32 bailian-team).
--
-- Model -> backend Action mapping (see HunyuanModel3dProvider):
--   HY-3D-Express -> SubmitHunyuanTo3DRapidJob (Prompt/ImageUrl only, fastest)
--   HY-3D-3.0     -> SubmitHunyuanTo3DProJob   (full feature set)
--   HY-3D-3.1     -> SubmitHunyuanTo3DProJob   (latest Pro behavior on
--                                                X-TC-Version=2025-05-13;
--                                                differs from 3.0 internally)

MERGE INTO mate_model_config (
    id, name, provider, model_name, description,
    temperature, max_tokens, top_p, builtin, enabled, is_default,
    model_type, create_time, update_time, deleted
)
KEY (id)
VALUES (
    1000000500, 'HY-3D-3.1', 'hunyuan-3d', 'HY-3D-3.1',
    '腾讯混元 3D 3.1 — 最高精度，支持 PBR / 多视角 / Geometry 白模等专业参数',
    NULL, NULL, NULL,
    TRUE, TRUE, TRUE,
    'model3d', NOW(), NOW(), 0
);

MERGE INTO mate_model_config (
    id, name, provider, model_name, description,
    temperature, max_tokens, top_p, builtin, enabled, is_default,
    model_type, create_time, update_time, deleted
)
KEY (id)
VALUES (
    1000000501, 'HY-3D-3.0', 'hunyuan-3d', 'HY-3D-3.0',
    '腾讯混元 3D 3.0 — 老一代 Pro 模型，与 3.1 共享 SubmitHunyuanTo3DProJob 调用',
    NULL, NULL, NULL,
    TRUE, TRUE, FALSE,
    'model3d', NOW(), NOW(), 0
);

MERGE INTO mate_model_config (
    id, name, provider, model_name, description,
    temperature, max_tokens, top_p, builtin, enabled, is_default,
    model_type, create_time, update_time, deleted
)
KEY (id)
VALUES (
    1000000502, 'HY-3D-Express', 'hunyuan-3d', 'HY-3D-Express',
    '腾讯混元 3D 极速版 — 走 SubmitHunyuanTo3DRapidJob 接口，速度最快但仅支持 Prompt / ImageUrl',
    NULL, NULL, NULL,
    TRUE, TRUE, FALSE,
    'model3d', NOW(), NOW(), 0
);
