-- V51: Remove write_file / edit_file from the global ToolGuard guarded list.
--
-- Scenarios where the agent legitimately writes 20+ chapter / config / asset
-- files in a single turn (project proposals, code refactors, scaffolding,
-- batch i18n updates) drown the user in approval popups — every single
-- write_file becomes "允许执行写文件？" and the workflow stalls. The original
-- intent of guarding these tools was to catch path-traversal exfiltration,
-- but `WorkspacePathGuard.validatePath()` already rejects any path outside
-- the active workspace before write_file / edit_file even run, so the
-- approval prompt is largely redundant.
--
-- This migration narrows the global guarded_tools_json to just
-- `execute_shell_command`, which is the genuinely dangerous surface (it can
-- shell out to rm, curl exfiltrate, etc.) and where per-call approval is
-- worth the friction. Operators who want write/edit guarded again can
-- toggle them back via the admin UI.
--
-- Idempotent: the WHERE clause matches only the global config row.

UPDATE mate_tool_guard_config
SET guarded_tools_json = '["execute_shell_command"]'
WHERE id = 1000000001;
