-- ordered multi-provider fallback chain
--
-- `fallback_priority` defines the order in which a provider is tried after the
-- primary model exhausts retries:
--   0       : not in the fallback chain (default — matches pre-RFC behavior)
--   1, 2, … : try in ascending order
--
-- Seed: keep DashScope as priority 1 so existing deployments preserve the
-- single-fallback-to-DashScope behavior the hardcoded path used to provide.

ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS fallback_priority INT DEFAULT 0;

UPDATE mate_model_provider SET fallback_priority = 1
 WHERE provider_id = 'dashscope' AND (fallback_priority IS NULL OR fallback_priority = 0);
