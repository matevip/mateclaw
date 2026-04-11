-- ==================== 内置工具同步（每次启动都执行，MERGE 是幂等的） ====================
-- 只包含 mate_tool 注册，不包含工作区文件等用户数据。
-- 新增内置工具时在此文件追加一条 MERGE，重启后即生效，无需重建数据库。

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000001, 'DateTimeTool', '日期时间', '获取当前日期和时间信息', 'builtin', 'dateTimeTool', '🕐', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000002, 'WebSearchTool', '网络搜索', '在互联网上搜索实时信息', 'builtin', 'webSearchTool', '🔍', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000003, 'ShellExecuteTool', '命令执行', '在本地服务器上执行 Shell 命令。用于执行系统命令、查看文件、运行脚本等操作。危险操作会触发审批确认。', 'builtin', 'shellExecuteTool', '🖥', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000004, 'ReadFileTool', '读取文件', '读取指定文件的内容，支持按行范围读取，自动截断超大输出。', 'builtin', 'readFileTool', '📖', TRUE, TRUE, NOW(), NOW(), 0);

-- WriteFileTool / EditFileTool 默认禁用（enabled=FALSE），MERGE 只在 id 不存在时才插入默认值
-- 如果用户已经手动在 UI 改为启用，此处的 MERGE 不会把 enabled 重置
-- （注意：MERGE KEY(id) 在 id 已存在时会覆写 enabled，所以保持与 data.sql 一致的默认值即可）
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000005, 'WriteFileTool', '写入文件', '将内容写入指定文件。如果文件已存在则完全覆写，不存在则创建新文件。每次执行需要用户审批确认。', 'builtin', 'writeFileTool', '📝', FALSE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000006, 'EditFileTool', '编辑文件', '通过查找替换编辑文件内容，精确匹配 old_text 并替换为 new_text。每次执行需要用户审批确认。', 'builtin', 'editFileTool', '✏️', FALSE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000007, 'SkillFileTool', '技能文件读取', '读取技能包内的文件（SKILL.md/references/scripts），列出技能文件目录树。支持 read_skill_file 和 list_skill_files 两个工具。', 'builtin', 'skillFileTool', '📖', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000008, 'SkillScriptTool', '技能脚本执行', '执行技能包 scripts/ 目录下的脚本（Python/Bash/Node），路径严格限制在技能目录内。', 'builtin', 'skillScriptTool', '⚡', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000009, 'FileTypeDetectorTool', '文件类型检测', '检测文件的 MIME 类型和类别，区分文本文件和 PDF/Office 文档，帮助选择合适的读取工具。', 'builtin', 'fileTypeDetectorTool', '🔍', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000010, 'DocumentExtractTool', '文档文本提取', '从 PDF、Word、Excel、PowerPoint 等 Office 文档中提取纯文本内容。支持 fallback 链：系统命令优先，Java 实现兜底。', 'builtin', 'documentExtractTool', '📄', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000011, 'WorkspaceMemoryTool', '工作区记忆', '读写数据库中的工作区 Markdown 文档，用于维护 PROFILE.md、MEMORY.md 和 memory/YYYY-MM-DD.md 等持久记忆。', 'builtin', 'workspaceMemoryTool', '🧠', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000014, 'DelegateAgentTool', 'Agent 委派', '委派任务给其他 Agent 执行，实现多 Agent 协作。支持按名称调用目标 Agent，在独立会话中运行并返回结果。', 'builtin', 'delegateAgentTool', '🤝', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000015, 'DatasourceTool', '数据源查询', '查询外部数据源的元数据：列出可用数据源、查看表列表、查看表结构（列名/类型/注释）。支持 MySQL、PostgreSQL、ClickHouse。', 'builtin', 'datasourceTool', '🗄', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000016, 'SqlQueryTool', 'SQL 查询', '在外部数据源上执行只读 SQL 查询。仅允许 SELECT 语句，自动添加 LIMIT 保护，结果格式化为表格展示。', 'builtin', 'sqlQueryTool', '📊', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000017, 'WikiTool', 'Wiki 知识库', '读取、搜索 Wiki 知识库中的结构化页面，并追溯原始来源文件。支持 wiki_read_page、wiki_list_pages、wiki_search_pages、wiki_trace_source 四个工具。', 'builtin', 'wikiTool', '📚', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000018, 'CronJobTool', '定时任务', '通过对话创建、查看、启停和删除定时任务。支持 5 字段 cron 表达式，灵活设定执行时间。', 'builtin', 'cronJobTool', '⏰', TRUE, TRUE, NOW(), NOW(), 0);
