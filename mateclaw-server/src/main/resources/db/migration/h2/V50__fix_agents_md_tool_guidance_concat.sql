-- V50: Fix V48's broken AGENTS.md UPDATE on H2.
--
-- V48 used `||` to concatenate strings in the H2 SET clause, but the H2
-- connection runs in `MODE=MySQL`, where `||` is LOGICAL OR, not string
-- concatenation. The result is that V48's REPLACE() set `content` to a
-- garbage boolean expression, corrupting AGENTS.md for the seeded agents
-- and sending the LLM back to `cat <<EOF` shell tricks instead of
-- calling write_file.
--
-- This migration rewrites the offending paragraph using CONCAT() (which
-- works under both H2 native and MODE=MySQL). It tolerates whatever V48
-- left behind because it overwrites the entire content for the three
-- seeded agents to a known-good template.
--
-- Idempotent: only touches AGENTS.md rows for the three seeded agents.

UPDATE mate_workspace_file
SET content = CONCAT(
    '## 记忆', CHAR(10),
    CHAR(10),
    '你的记忆由数据库工作区文件提供连续性：', CHAR(10),
    CHAR(10),
    '- `PROFILE.md`：稳定用户画像与协作偏好', CHAR(10),
    '- `MEMORY.md`：长期事实、经验教训、工具设置、反复出现的模式', CHAR(10),
    '- `memory/YYYY-MM-DD.md`：当日事件、观察、一次性上下文', CHAR(10),
    CHAR(10),
    '### 记忆策略', CHAR(10),
    CHAR(10),
    '- 稳定信息进入 `PROFILE.md` 或 `MEMORY.md`', CHAR(10),
    '- 临时事件进入 `memory/YYYY-MM-DD.md`', CHAR(10),
    '- 修改前先读取原文，优先做增量编辑而不是整篇重写', CHAR(10),
    '- 避免记录敏感信息，除非用户明确要求', CHAR(10),
    CHAR(10),
    '### 主动召回', CHAR(10),
    CHAR(10),
    '- 遇到历史偏好、旧决策、持续任务、用户习惯时，优先查看工作区记忆', CHAR(10),
    '- 不确定具体发生日期时，检查相关 `memory/YYYY-MM-DD.md`', CHAR(10),
    CHAR(10),
    '## 安全', CHAR(10),
    CHAR(10),
    '- 绝不泄露私密数据。', CHAR(10),
    '- 拿不准的事情，先确认。', CHAR(10),
    CHAR(10),
    '## 边界', CHAR(10),
    CHAR(10),
    '- 私密的保持私密。', CHAR(10),
    '- 需要执行文件操作或命令时，**必须**调用对应的工具：', CHAR(10),
    '  - 读文件 → `read_file`', CHAR(10),
    '  - 写新文件或覆盖整个文件 → `write_file`（一次写完整内容，不要用 printf / heredoc / echo / cat << EOF 拼字符串）', CHAR(10),
    '  - 修改已有文件局部内容 → `edit_file`', CHAR(10),
    '  - 执行 shell 命令 → `execute_shell_command`', CHAR(10),
    '  禁止用 shell 命令绕过 `write_file` 写文件。系统会自动对危险操作弹出审批确认。', CHAR(10),
    '- 拿不准就先问。', CHAR(10),
    CHAR(10),
    '## 风格', CHAR(10),
    CHAR(10),
    '该简洁就简洁，重要时详细。', CHAR(10),
    CHAR(10),
    '## 连续性', CHAR(10),
    CHAR(10),
    '每次会话都全新醒来。工作区文件就是你的记忆。读它们。更新它们。', CHAR(10)
)
WHERE filename = 'AGENTS.md'
  AND agent_id IN (1000000001, 1000000002, 1000000003);
