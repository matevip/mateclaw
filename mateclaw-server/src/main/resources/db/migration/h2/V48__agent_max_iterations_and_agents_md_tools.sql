-- V48: Two unrelated agent runtime fixes that surfaced in the same dogfood session.
--
-- 1) max_iterations: V47 only matched rows still holding the original seed (25 / 20).
--    User-customized rows (any value other than 25/20) were silently skipped, so the
--    StateGraph ReAct agent kept running with maxIterations=25 even after V47.
--    V48 widens the condition: any seeded agent row with max_iterations < 100 gets
--    bumped to 100, matching the QwenPaw-style ceiling. Custom rows above 100 still
--    get clamped at runtime by AgentGraphBuilder.
--
-- 2) AGENTS.md tool guidance: the workspace's seeded AGENTS.md only mentioned
--    `execute_shell_command` and `read_file`, leading the LLM to write document
--    chapters by piping printf / heredoc through the shell (which silently folds
--    multi-line strings into a single line on this host) instead of using the
--    `write_file` tool that's actually registered. After 25+ iterations of failed
--    shell tricks the agent ran out of budget without ever calling renderDocxFromFiles.
--    Replace the relevant line so write_file / edit_file are surfaced. Other
--    workspace files (SOUL.md, MEMORY.md, etc.) are untouched.

UPDATE mate_agent SET max_iterations = 100
WHERE id IN (1000000001, 1000000002, 1000000003)
  AND (max_iterations IS NULL OR max_iterations < 100);

UPDATE mate_workspace_file
SET content = REPLACE(
        content,
        '需要执行文件操作或命令时，直接调用对应的工具（如 execute_shell_command、read_file 等），不要用文本描述你要做什么。',
        '需要执行文件操作或命令时，直接调用对应的工具：' || CHAR(10)
            || '- 读文件 → `read_file`' || CHAR(10)
            || '- 写新文件或覆盖整个文件 → `write_file`（一次写完整内容，不要用 printf / heredoc / echo 拼）' || CHAR(10)
            || '- 修改已有文件局部内容 → `edit_file`' || CHAR(10)
            || '- 执行 shell 命令 → `execute_shell_command`' || CHAR(10)
            || '不要用文本描述你要做什么。'
    )
WHERE filename = 'AGENTS.md'
  AND content LIKE '%execute_shell_command、read_file%';
