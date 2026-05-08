package vip.mate.tool.document.pdf;

/**
 * One way to turn markdown bytes into PDF bytes. {@link MarkdownPdfRenderer}
 * picks an implementation at request time based on availability and the
 * caller's {@link PdfRenderRequest#engine()} preference.
 */
public interface PdfBackend {

    /** Stable identifier surfaced in the tool result and in logs. */
    String name();

    /**
     * Whether this backend can run at all on the current host. The default
     * implementation says yes; the LibreOffice backend overrides this to
     * probe for {@code soffice}.
     */
    default boolean isAvailable() { return true; }

    /**
     * Whether this backend can faithfully render the request. The HTML
     * backend always returns {@code true}; the LibreOffice backend declines
     * requests that need cover / header / footer because those features
     * cannot be expressed through the docx intermediate.
     */
    default boolean supports(PdfRenderRequest request) { return true; }

    byte[] render(PdfRenderRequest request) throws Exception;
}
