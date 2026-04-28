-- RFC-063r §2.9: bind a cron job to its originating channel + delivery target.
--
-- channel_id is the only field that needs an index (operations team queries
-- "all jobs delivering to channel X"). delivery_config is a JSON column —
-- targetId / threadId / accountId are persisted as a structured value via
-- MyBatis Plus JacksonTypeHandler so future delivery-target fields don't
-- require schema changes.

ALTER TABLE mate_cron_job ADD COLUMN IF NOT EXISTS channel_id      BIGINT;
ALTER TABLE mate_cron_job ADD COLUMN IF NOT EXISTS delivery_config TEXT;

CREATE INDEX IF NOT EXISTS idx_cron_channel ON mate_cron_job(channel_id);
