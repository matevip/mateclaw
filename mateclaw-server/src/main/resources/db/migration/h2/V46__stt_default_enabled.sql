-- Default-enable STT on existing deployments. New installs run V46 first
-- (table empty, INSERT succeeds), then DatabaseBootstrapRunner re-runs the
-- same statements from data-{en,zh,mysql-en,mysql-zh}.sql with the same
-- skip-if-exists semantics, so this is idempotent across both paths.
--
-- Why default to enabled: STT requires a recording UI gesture to even be
-- exercised, so leaving it off by default just makes "the mic button does
-- nothing" the most-reported support issue. Once enabled, it still does
-- nothing dangerous unless the user has also configured an OpenAI / DashScope
-- API key — those are the real gating credentials.
--
-- Idiom: INSERT ... SELECT ... WHERE NOT EXISTS, keyed on setting_key.
-- Earlier versions of this migration used MERGE INTO ... KEY (id), which
-- crashed on deployments where the user had already toggled STT in the UI:
-- their row landed at a runtime-assigned snowflake id, then this migration
-- tried to insert a fresh row at id=1000000020 and tripped the UNIQUE index
-- on setting_key. Skip-if-exists preserves whatever value the user picked
-- (don't override an explicit "off" with "on"). See
-- https://git.mate.vip/mate/MateClaw issue noted 2026-04-26.

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000020, 'sttEnabled', 'true', 'Enable speech-to-text (TalkMode mic input)', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttEnabled');

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000021, 'sttProvider', 'auto', 'STT provider: auto / openai / dashscope', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttProvider');

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000022, 'sttFallbackEnabled', 'true', 'Try alternate STT provider when the primary fails', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttFallbackEnabled');
