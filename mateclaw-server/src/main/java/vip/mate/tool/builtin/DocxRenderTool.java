package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.MarkdownDocxRenderer;

/**
 * Render a brand-new .docx from Markdown without ever forking a process.
 *
 * <p>The previous path forwarded these requests to {@code skills/docx} which
 * runs {@code npm install docx} on first use (3-5 minutes). For "create new
 * document" intents that subprocess is wholly unnecessary; this tool produces
 * the bytes in the JVM, stashes them in {@link GeneratedFileCache}, and
 * returns a Markdown link the user can click to download.
 *
 * <p>The skill workflow is still authoritative for editing existing .docx,
 * tracked changes, and other XML-level operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocxRenderTool {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final MarkdownDocxRenderer renderer;
    private final GeneratedFileCache cache;

    @Tool(description = """
        Render a new .docx file from Markdown text and return a one-time download URL.
        Use for creating NEW documents: reports, memos, contracts, letters, resumes.
        Supports: headings (# ## ###), bold (**text**), bullet lists (- item),
                  numbered lists (1. item), tables (| col | col |), plain paragraphs.

        Do NOT use for:
        - Editing an existing .docx file (use run_skill_script with unpack/edit/pack)
        - Adding tracked changes or comments (use run_skill_script)
        - GB/T 9704 official documents (use writeGongwen tool, BmacClaw only)

        Returns a markdown link the user can click to download the file.
        The link is valid for 10 minutes.
        """)
    public String renderDocx(
            @ToolParam(description = "Document content in Markdown format")
            String markdown,
            @ToolParam(description = "Output filename without extension, e.g. 'monthly-report'")
            String filename,
            @ToolParam(description = "Page size: A4 or LETTER (default: A4)", required = false)
            String pageSize) {

        if (markdown == null || markdown.isBlank()) {
            return "错误：markdown 参数为空，无法生成文档。";
        }

        String safeName = sanitizeFilename(filename);
        String displayName = safeName + ".docx";
        String size = (pageSize == null || pageSize.isBlank()) ? "A4" : pageSize.trim();

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(markdown, size);
            String id = cache.put(bytes, displayName, DOCX_MIME);
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[DocxRender] generated {} ({} bytes, {}ms, id={})",
                    displayName, bytes.length, elapsed, id);

            String url = "/api/v1/files/generated/" + id;
            return "文档已生成：[" + displayName + "](" + url + ")（链接 10 分钟内有效）";
        } catch (Exception e) {
            log.error("[DocxRender] render failed for {}: {}", displayName, e.getMessage(), e);
            return "渲染失败：" + e.getMessage();
        }
    }

    /**
     * Strip path separators and other unsafe characters from a user-supplied
     * filename. Falls back to a generic name when nothing usable remains.
     */
    private String sanitizeFilename(String name) {
        if (name == null) return "document";
        String trimmed = name.trim();
        if (trimmed.toLowerCase().endsWith(".docx")) {
            trimmed = trimmed.substring(0, trimmed.length() - 5);
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().strip();
        return cleaned.isEmpty() ? "document" : cleaned;
    }
}
