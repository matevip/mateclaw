-- V49: Re-enable WriteFileTool and EditFileTool.
--
-- These two tool beans were observed disabled in production
-- (mate_tool.enabled = FALSE for bean_name in writeFileTool / editFileTool),
-- which makes ToolRegistry skip them during agent toolset construction.
-- The LLM still sees a tool named `write_file` from some other surface
-- (likely a name collision through Spring AI's tool discovery from a
-- non-disabled bean's @Tool annotation), and ToolGuard accepts the call —
-- so the request reaches approval. But after approval, the replay path
-- looks the callback up in toolCallbackMap and gets nothing, returning
-- "Tool not found: write_file" to the LLM. The LLM then falls back to
-- assembling files via execute_shell_command + printf / heredoc, which
-- silently collapses multi-line content on this host and burns the
-- iteration budget without producing usable output.
--
-- The right fix is to make sure the tool the LLM sees is the one the
-- executor can run. Re-enable both rows; if a future install really wants
-- write/edit disabled, the user can flip the toggle in the admin UI again.
--
-- Idempotent: only flips rows currently disabled.

UPDATE mate_tool SET enabled = TRUE
WHERE bean_name IN ('writeFileTool', 'editFileTool')
  AND enabled = FALSE;
