-- V3: Register CronJobTool as built-in tool (RFC-003)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000018, 'CronJobTool', 'Scheduled Tasks', 'Create, list, enable/disable, and delete scheduled tasks (cron jobs) through chat.', 'builtin', 'cronJobTool', '⏰', TRUE, TRUE, NOW(), NOW(), 0);
