package vip.mate.tool.document.pdf;

import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extract the YAML frontmatter block at the top of a markdown body so the PDF
 * pipeline can drive cover / page header / page footer text from it. The
 * frontmatter block — when present — has the form:
 * <pre>
 * ---
 * title: 季度报告
 * subtitle: Q1 2026
 * header: 内部资料
 * footer: Mate Inc. © 2026
 * ---
 * </pre>
 *
 * <p>Markdown without frontmatter parses to {@link #empty()}; the renderer
 * then synthesises a cover from the first {@code # H1} heading and uses
 * default header / footer text.
 */
public record PdfFrontmatter(
        String title,
        String subtitle,
        String header,
        String footer,
        boolean explicitCover) {

    /**
     * Backwards-compat constructor for callers that only know about the four
     * text slots; the cover-source flag defaults to {@code false} (synthetic).
     */
    public PdfFrontmatter(String title, String subtitle, String header, String footer) {
        this(title, subtitle, header, footer, false);
    }

    public boolean hasCover() {
        return notBlank(title) || notBlank(subtitle);
    }

    /**
     * Whether the cover came from a YAML frontmatter block (true) or was
     * synthesised by promoting a leading {@code # H1} into a cover title (false).
     * Synthetic covers are not real layout requirements — the LibreOffice
     * backend can ignore them and render the H1 inline as part of the document.
     */
    public boolean hasExplicitCover() {
        return explicitCover && hasCover();
    }

    public boolean hasHeader() {
        return notBlank(header);
    }

    public boolean hasFooter() {
        return notBlank(footer);
    }

    /** Whether ANY of the frontmatter slots is populated. */
    public boolean isPresent() {
        return hasCover() || hasHeader() || hasFooter();
    }

    public static PdfFrontmatter empty() {
        return new PdfFrontmatter(null, null, null, null, false);
    }

    public static PdfFrontmatter parse(String markdown) {
        if (markdown == null || markdown.isBlank()) return empty();

        Parser parser = Parser.builder()
                .extensions(List.of(YamlFrontMatterExtension.create()))
                .build();
        Node document = parser.parse(markdown);

        YamlFrontMatterVisitor visitor = new YamlFrontMatterVisitor();
        document.accept(visitor);
        Map<String, List<String>> data = visitor.getData();
        if (data == null || data.isEmpty()) return empty();

        return new PdfFrontmatter(
                first(data, "title"),
                first(data, "subtitle"),
                first(data, "header"),
                first(data, "footer"),
                /* explicitCover = */ true);
    }

    private static String first(Map<String, List<String>> data, String key) {
        List<String> values = data.get(key);
        if (values == null || values.isEmpty()) return null;
        String v = values.get(0);
        if (v == null) return null;
        // YAML scalar values come back with surrounding quotes preserved when the
        // user wrote `title: "..."`. Strip a single matching pair so the rendered
        // cover doesn't show literal quote characters.
        v = v.trim();
        if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
                || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2)) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Convenience: read frontmatter, if missing look for a leading {@code # H1}
     * to use as the cover title. The synthesised result is flagged with
     * {@code explicitCover=false} so backends that cannot render an actual
     * cover page (LibreOffice via the docx intermediate) can safely ignore it
     * — the H1 will still render as the first heading inline.
     */
    public static PdfFrontmatter parseOrSynthesise(String markdown) {
        PdfFrontmatter fm = parse(markdown);
        if (fm.hasCover()) return fm;

        String firstHeading = firstHeading(markdown);
        if (firstHeading != null) {
            return new PdfFrontmatter(firstHeading, fm.subtitle(), fm.header(), fm.footer(),
                    /* explicitCover = */ false);
        }
        return fm;
    }

    private static String firstHeading(String markdown) {
        for (String rawLine : markdown.split("\\R", -1)) {
            String line = rawLine.strip();
            if (line.startsWith("# ") && line.length() > 2) {
                return line.substring(2).strip();
            }
        }
        return null;
    }

    /** Strip a leading YAML frontmatter block from a markdown body. */
    public static String stripFrontmatter(String markdown) {
        if (markdown == null) return "";
        String trimmed = markdown.stripLeading();
        if (!trimmed.startsWith("---")) return markdown;
        int firstBreak = trimmed.indexOf('\n');
        if (firstBreak < 0) return markdown;
        int closing = trimmed.indexOf("\n---", firstBreak);
        if (closing < 0) return markdown;
        int after = trimmed.indexOf('\n', closing + 4);
        return after < 0 ? "" : trimmed.substring(after + 1);
    }

    /** Try to find {@link Optional} variant for callers preferring null-safe accessors. */
    public Optional<String> titleOpt() { return Optional.ofNullable(title).filter(PdfFrontmatter::nb); }
    public Optional<String> subtitleOpt() { return Optional.ofNullable(subtitle).filter(PdfFrontmatter::nb); }
    public Optional<String> headerOpt() { return Optional.ofNullable(header).filter(PdfFrontmatter::nb); }
    public Optional<String> footerOpt() { return Optional.ofNullable(footer).filter(PdfFrontmatter::nb); }

    private static boolean nb(String s) { return !s.isBlank(); }
}
