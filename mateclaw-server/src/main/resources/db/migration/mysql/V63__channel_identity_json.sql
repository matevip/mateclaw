-- RFC-084 follow-up: persist the identity returned by ChannelVerifier so
-- the channel list can show "Connected as @MyBot" instead of generic
-- type-level descriptions. Populated on wizard create from VerificationResult;
-- refreshed by adapters on first successful connect.

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'mate_channel'
                      AND COLUMN_NAME = 'identity_json');
SET @stmt := IF(@col_exists = 0,
                'ALTER TABLE mate_channel ADD COLUMN identity_json TEXT',
                'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
