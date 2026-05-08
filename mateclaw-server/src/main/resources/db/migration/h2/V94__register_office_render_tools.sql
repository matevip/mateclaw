-- V94: Register XlsxRenderTool / PptxRenderTool / PdfRenderTool as built-in tools.
-- These mirror DocxRenderTool (V31) so agents can bind them through the tool picker
-- and so the AvailableToolService surfaces them in the UI.
-- Idempotent: MERGE INTO updates existing rows when id matches.

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000020, 'XlsxRenderTool', 'XLSX Render', 'Render Markdown directly into a .xlsx workbook and return a one-time download link. In-process Apache POI; each # heading becomes a sheet, pipe tables become rows, numeric cells auto-detected.', 'builtin', 'xlsxRenderTool', '📊', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000021, 'PptxRenderTool', 'PPTX Render', 'Render Marp-style Markdown directly into a .pptx deck and return a one-time download link. In-process Apache POI; --- separates slides, # / ## titles, - bullets, <!-- speaker notes -->.', 'builtin', 'pptxRenderTool', '🎞️', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000022, 'PdfRenderTool', 'PDF Render', 'Render Markdown into a final-form .pdf and return a one-time download link. Two backends (LibreOffice subprocess preferred, OpenPDF + Flying Saucer fallback); supports YAML frontmatter for cover / page header / page footer.', 'builtin', 'pdfRenderTool', '📄', TRUE, TRUE, NOW(), NOW(), 0);
