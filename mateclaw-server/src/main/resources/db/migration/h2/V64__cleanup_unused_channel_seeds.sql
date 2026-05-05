-- RFC-084 follow-up: clean up the 8 placeholder channel rows that older
-- seed scripts inserted for dingtalk / feishu / telegram / discord / wecom /
-- qq / weixin / slack. Those rows have been removed from data-*.sql, but
-- existing installs still carry them as id 1000000002..1000000009.
--
-- The predicate is conservative on three independent signals:
--   1. id is in the known seed range (1000000002..1000000009)
--   2. channel_type is one of the 8 placeholder types
--   3. enabled = false (user never activated)
--
-- Hard-delete is fine here: no FK constraints on mate_channel, and a user
-- who actually used a placeholder would have flipped enabled=true (which
-- excludes them from this DELETE). An earlier soft-delete attempt with a
-- config_json LIKE filter ran but matched 0 rows, so this version drops
-- the LIKE and just removes the rows outright.
--
-- FlywayRepairConfig auto-fixes the checksum if this file changes again,
-- so no manual repair step is needed on installs that already ran an
-- earlier version of V64.

DELETE FROM mate_channel
WHERE id IN (1000000002, 1000000003, 1000000004, 1000000005,
             1000000006, 1000000007, 1000000008, 1000000009)
  AND channel_type IN ('dingtalk', 'feishu', 'telegram', 'discord',
                       'wecom', 'qq', 'weixin', 'slack')
  AND enabled = FALSE;
