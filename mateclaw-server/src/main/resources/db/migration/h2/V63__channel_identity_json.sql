-- RFC-084 follow-up: persist the identity returned by ChannelVerifier so
-- the channel list can show "Connected as @MyBot" instead of generic
-- type-level descriptions. Populated on wizard create from VerificationResult;
-- refreshed by adapters on first successful connect.

ALTER TABLE mate_channel ADD COLUMN IF NOT EXISTS identity_json TEXT;
