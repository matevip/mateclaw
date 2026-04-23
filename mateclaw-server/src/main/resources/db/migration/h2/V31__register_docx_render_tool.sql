-- V31: Register DocxRenderTool as built-in tool (RFC-045)
-- Idempotent: MERGE INTO updates existing row when id matches.
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000019, 'DocxRenderTool', 'DOCX Render', 'Render Markdown directly into a .docx and return a one-time download link. In-process Apache POI implementation, no Node.js subprocess; supports headings, bold, lists, tables. Preferred tool for creating new documents.', 'builtin', 'docxRenderTool', '📝', TRUE, TRUE, NOW(), NOW(), 0);
