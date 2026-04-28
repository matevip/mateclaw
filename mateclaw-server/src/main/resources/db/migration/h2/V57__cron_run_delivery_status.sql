-- RFC-063r §2.9: cron run delivery state machine.
--
-- delivery_status drives the SQL-CAS that prevents duplicate proactive sends
-- (replaces RFC-063 v1's Caffeine 30-min TTL idempotency). 4-state machine:
--   NONE          — no delivery strategy (web-origin cron)
--   PENDING       — claimed by AbstractCronResultDelivery#claimRun
--   DELIVERED     — markDelivered after successful proactiveSend
--   NOT_DELIVERED — markNotDelivered (or stale-cleanup timeout)
--
-- delivery_target VARCHAR(512) covers both IM userId (≤64) and Feishu
-- sessionWebhook URLs (~200-256). Index (delivery_status, started_at) is
-- the exact predicate of CronRunStaleCleanup.sweep().

ALTER TABLE mate_cron_job_run ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(16) DEFAULT 'NONE' NOT NULL;
ALTER TABLE mate_cron_job_run ADD COLUMN IF NOT EXISTS delivery_target VARCHAR(512);
ALTER TABLE mate_cron_job_run ADD COLUMN IF NOT EXISTS delivery_error  VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_cron_run_pending_started ON mate_cron_job_run(delivery_status, started_at);
