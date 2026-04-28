-- RFC-063r §2.12: persist the originating ChatOrigin as a JSON snapshot on the
-- approval row. Used by ChannelMessageRouter.replayApprovedToolCall + web
-- ApprovalController so cross-process / cross-restart approval replays still
-- preserve the channel binding (Memento pattern).

ALTER TABLE mate_tool_approval ADD COLUMN IF NOT EXISTS chat_origin TEXT;
