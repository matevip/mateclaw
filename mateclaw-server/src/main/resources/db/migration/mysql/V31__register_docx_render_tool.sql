-- V31: Register DocxRenderTool as built-in tool (RFC-045)
-- Idempotent: ON DUPLICATE KEY UPDATE keeps the row in sync if it already exists.
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000019, 'DocxRenderTool', 'DOCX Render', 'Render Markdown directly into a .docx and return a one-time download link. In-process Apache POI implementation, no Node.js subprocess; supports headings, bold, lists, tables. Preferred tool for creating new documents.', 'builtin', 'docxRenderTool', '📝', TRUE, TRUE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE name=VALUES(name), display_name=VALUES(display_name), description=VALUES(description), bean_name=VALUES(bean_name), icon=VALUES(icon), update_time=VALUES(update_time);
