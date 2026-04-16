-- V16: Broaden the DashScope native-protocol purge beyond the two explicit ids in V15.
-- Any model whose name matches "qwenN.M-*" (dot-versioned family like qwen3.5-*, qwen3.6-*)
-- only exists on compatible-mode and fails on the native endpoint with
-- "[InvalidParameter] url error". Users who manually added such ids through the
-- "Add model" form before V16's runtime guard was in place end up with conversations
-- that silently fail. Clear them here so those users get a fresh start.
DELETE FROM mate_model_config
WHERE provider = 'dashscope'
  AND (model_name LIKE 'qwen1.%'
       OR model_name LIKE 'qwen2.%'
       OR model_name LIKE 'qwen3.%'
       OR model_name LIKE 'qwen4.%'
       OR model_name LIKE 'qwen5.%');
