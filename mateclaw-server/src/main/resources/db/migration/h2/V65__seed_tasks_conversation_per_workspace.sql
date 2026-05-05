-- Cron unification: seed a per-workspace "tasks" conversation so cron-run
-- output (now routed there by CronConversationResolver) is reachable from
-- the sidebar. Without this, the new conversationId tasks_<wsId> would be
-- written into mate_message but the conversation row wouldn't exist yet
-- and the sidebar/list query wouldn't surface it.
--
-- Idempotent via NOT EXISTS — re-running on a populated DB does nothing.

INSERT INTO mate_conversation
    (id, conversation_id, title, agent_id, username, message_count,
     last_message, last_active_time, stream_status, workspace_id,
     parent_conversation_id, create_time, update_time, deleted)
SELECT
    -- Synthesize a stable id in the seed range so re-runs collide harmlessly.
    1000200000 + ws.id,
    'tasks_' || ws.id,
    '📋 定时任务',
    NULL,
    'system',
    0,
    NULL,
    NOW(),
    'idle',
    ws.id,
    NULL,
    NOW(),
    NOW(),
    0
FROM mate_workspace ws
WHERE ws.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM mate_conversation c
      WHERE c.conversation_id = 'tasks_' || ws.id AND c.deleted = 0
  );
