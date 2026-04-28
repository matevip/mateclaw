-- V54: Localize seeded channel names to Chinese for zh-CN installs.
--
-- The zh seed (db/data-zh.sql) historically planted channels with English
-- display names ("DingTalk Bot", "Feishu Bot", "WeCom Bot", ...). The seed
-- only runs once per fresh install (DatabaseBootstrapRunner short-circuits
-- on isDataAlreadySeeded), so existing zh-CN installs are stuck with the
-- English names forever.
--
-- This migration flips the names to Chinese ONLY when:
--   1. The system language is zh-CN (so en-US installs are untouched)
--   2. The channel still has the original English seeded name (so any
--      user-renamed channel is left alone)
--
-- Idempotent: re-running the migration finds no matching rows after the
-- first apply because the names won't match the English originals anymore.

UPDATE mate_channel SET name = 'Web 控制台'
 WHERE id = 1000000001 AND name = 'Web Console'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = '钉钉机器人'
 WHERE id = 1000000002 AND name = 'DingTalk Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = '飞书机器人'
 WHERE id = 1000000003 AND name = 'Feishu Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = 'Telegram 机器人'
 WHERE id = 1000000004 AND name = 'Telegram Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = 'Discord 机器人'
 WHERE id = 1000000005 AND name = 'Discord Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = '企业微信机器人'
 WHERE id = 1000000006 AND name = 'WeCom Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

UPDATE mate_channel SET name = 'QQ 机器人'
 WHERE id = 1000000007 AND name = 'QQ Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');

-- id 1000000008 ("微信") already in Chinese; intentionally skipped.

UPDATE mate_channel SET name = 'Slack 机器人'
 WHERE id = 1000000009 AND name = 'Slack Bot'
   AND EXISTS (SELECT 1 FROM mate_system_setting
                WHERE setting_key = 'language' AND setting_value = 'zh-CN');
