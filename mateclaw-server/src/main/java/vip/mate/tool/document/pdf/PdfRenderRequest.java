package vip.mate.tool.document.pdf;

/**
 * Request payload handed to a {@link PdfBackend}. The orchestrator builds
 * this once per call after parsing frontmatter and resolving page size; the
 * backends are read-only consumers.
 *
 * @param markdown   markdown body with the YAML frontmatter block already stripped
 * @param frontmatter parsed (or synthesised from a leading {@code # H1}) frontmatter
 * @param pageSize   "A4" or "LETTER"
 * @param engine     the engine preference the caller gave; the orchestrator
 *                   uses this to decide which backend to ask, but each backend
 *                   only sees the request after the choice has been made and
 *                   may largely ignore the field
 */
public record PdfRenderRequest(
        String markdown,
        PdfFrontmatter frontmatter,
        String pageSize,
        PdfProperties.Engine engine) {

    public PdfRenderRequest {
        if (markdown == null) markdown = "";
        if (frontmatter == null) frontmatter = PdfFrontmatter.empty();
        if (pageSize == null || pageSize.isBlank()) pageSize = "A4";
        if (engine == null) engine = PdfProperties.Engine.AUTO;
    }
}
