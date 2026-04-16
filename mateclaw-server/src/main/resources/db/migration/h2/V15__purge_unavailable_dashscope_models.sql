-- V15: Purge DashScope model seed rows that are unavailable on the native protocol
-- These model names are listed by DashScope compatible-mode /v1/models but will fail
-- with 400 InvalidParameter or "url error, please check url" on the native endpoint
-- (/api/v1/services/aigc/text-generation/generation), which is what Spring AI Alibaba
-- DashScopeChatModel actually uses at runtime. Keeping them around only leads to user
-- confusion when conversations silently fail.
DELETE FROM mate_model_config
WHERE id IN (1000000170, 1000000171)
  AND provider = 'dashscope'
  AND builtin = TRUE;
-- 1000000170 = qwen3.5-plus
-- 1000000171 = qwen3.5-max
