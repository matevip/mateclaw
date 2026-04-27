package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.MarkdownDocxRenderer;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
            // Explicit instruction to suppress LLM hallucinating an absolute host.
            // DeepSeek/Claude have been observed prepending placeholder domains
            // (e.g. https://ai-tools-system.com) when echoing the URL back to the user,
            // breaking the download link. Repeat the path verbatim with no host.
            return "文档已生成：[" + displayName + "](" + url + ")（链接 10 分钟内有效）。\n"
                    + "重要：回答用户时**必须**使用上述相对路径 `" + url + "`，"
                    + "**不要**添加任何 https://、http:// 域名前缀，前端会自动拼接当前主机。";
        } catch (Exception e) {
            log.error("[DocxRender] render failed for {}: {}", displayName, e.getMessage(), e);
            return "渲染失败：" + e.getMessage();
        }
    }

    /**
     * File-based renderer — reads markdown from disk instead of taking it as a
     * tool argument. Bypasses the LLM token-cost cliff: a 80 KB markdown body
     * would otherwise be streamed through the chat completion as part of
     * {@code renderDocx.markdown} args (≈ 20 K tokens, several minutes of
     * generation just to repeat back content the LLM already wrote to disk).
     * <p>
     * Workflow: agent uses {@code write_file} / {@code edit_file} to assemble
     * the markdown locally → calls this tool with the file path → docx is
     * rendered from disk in one IO call. Token cost ≈ 50 (just the path).
     */
    @Tool(description = """
        Render a .docx file from a markdown FILE on disk and return a one-time download URL.
        Use this instead of `renderDocx` when the markdown body is large (>5 KB) — the
        LLM does not need to repeat its own previous output as a tool argument.

        Typical workflow:
          1. write_file(path="report.md", content="# Report\\n...")  // assemble markdown
          2. renderDocxFromFile(filePath="report.md", filename="monthly-report")
          3. return the download link to the user

        The markdown file is read with UTF-8. Path resolution honors the workspace
        boundary (same rules as read_file / write_file).

        Same supported markdown subset as renderDocx (headings, bold, lists, tables).
        Image references (![alt](path)) are NOT yet rendered into the docx — they will
        appear as raw markdown text. SVG inline embedding requires a follow-up tool.
        """)
    public String renderDocxFromFile(
            @ToolParam(description = "Absolute or workspace-relative path to a markdown file")
            String filePath,
            @ToolParam(description = "Output filename without extension, e.g. 'monthly-report'")
            String filename,
            @ToolParam(description = "Page size: A4 or LETTER (default: A4)", required = false)
            String pageSize) {

        if (filePath == null || filePath.isBlank()) {
            return "Error: filePath parameter is empty.";
        }

        Path resolved;
        try {
            resolved = WorkspacePathGuard.validatePath(filePath);
        } catch (Exception e) {
            return "Error: path validation failed — " + e.getMessage();
        }
        if (!Files.exists(resolved)) {
            return "Error: file not found at " + resolved;
        }
        if (!Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
            return "Error: path is not a readable regular file " + resolved;
        }

        String markdown;
        long mdBytes;
        try {
            mdBytes = Files.size(resolved);
            markdown = Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DocxRender] read markdown failed for {}: {}", resolved, e.getMessage(), e);
            return "Error: failed to read markdown — " + e.getMessage();
        }
        if (markdown.isBlank()) {
            return "Error: markdown file is empty " + resolved;
        }

        String safeName = sanitizeFilename(filename);
        String displayName = safeName + ".docx";
        String size = (pageSize == null || pageSize.isBlank()) ? "A4" : pageSize.trim();

        try {
            long t0 = System.currentTimeMillis();
            byte[] bytes = renderer.render(markdown, size);
            String id = cache.put(bytes, displayName, DOCX_MIME);
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[DocxRender] generated {} ({} bytes from {} bytes md, {}ms, id={})",
                    displayName, bytes.length, mdBytes, elapsed, id);

            String url = "/api/v1/files/generated/" + id;
            return "Document generated: [" + displayName + "](" + url + ") (link valid for 10 minutes).\n"
                    + "IMPORTANT: when replying to the user you **must** use the relative path `"
                    + url + "` verbatim. Do **not** prepend any https://, http:// or domain — "
                    + "the frontend will resolve the current host automatically.";
        } catch (Exception e) {
            log.error("[DocxRender] render failed for {} (source: {}): {}",
                    displayName, resolved, e.getMessage(), e);
            return "Render failed: " + e.getMessage();
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
