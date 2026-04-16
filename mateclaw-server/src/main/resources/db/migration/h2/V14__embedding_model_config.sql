-- V14: Embedding model UI config (对标 Dify)
-- 区分 chat / embedding 两种模型类型，让 embedding 也能通过 Settings/Models UI 管理
ALTER TABLE mate_model_config ADD COLUMN IF NOT EXISTS model_type VARCHAR(32) DEFAULT 'chat';

-- 知识库可绑定特定 embedding 模型；NULL 表示使用系统默认
ALTER TABLE mate_wiki_knowledge_base ADD COLUMN IF NOT EXISTS embedding_model_id BIGINT DEFAULT NULL;

-- 播种 DashScope embedding 模型（builtin，共享 dashscope provider 的 api_key）
-- 1000001001..003 段用于 embedding 模型，与 1000000xxx 段的 chat 模型错开
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
KEY (id)
VALUES (1000001001, 'Text Embedding v3', 'dashscope', 'text-embedding-v3',
        'DashScope 通义千问 v3 通用文本向量模型（1024 维）', 0, 0, 0,
        TRUE, TRUE, TRUE, 'embedding', NOW(), NOW(), 0);

MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
KEY (id)
VALUES (1000001002, 'Text Embedding v2', 'dashscope', 'text-embedding-v2',
        'DashScope 通义千问 v2 文本向量模型（1536 维）', 0, 0, 0,
        TRUE, TRUE, FALSE, 'embedding', NOW(), NOW(), 0);

-- 系统默认 embedding 模型记录到 mate_system_setting（id 必须显式指定，该表无自增）
-- 1000001100 段保留给 embedding 相关设置
MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000001100, 'embedding.default.model.id', '1000001001',
        'Default embedding model id for wiki semantic search', NOW(), NOW());
