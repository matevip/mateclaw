package vip.mate.tool.document.pdf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Orchestrate PDF rendering. Picks a {@link PdfBackend} based on the caller's
 * engine preference, the backend's {@link PdfBackend#isAvailable()} probe, and
 * its {@link PdfBackend#supports(PdfRenderRequest)} declaration. Both backends
 * receive a normalised {@link PdfRenderRequest} so they don't have to redo
 * frontmatter parsing or page-size defaulting.
 *
 * <p>Dispatch table:
 * <pre>
 * engine=AUTO  + libreoffice ok + supports request → libreoffice
 * engine=AUTO  + libreoffice missing OR can't do header/footer → openhtmltopdf
 * engine=LIBREOFFICE  + supports → libreoffice (else throw)
 * engine=HTML  → openhtmltopdf
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PdfProperties.class)
public class MarkdownPdfRenderer {

    private final LibreOfficePdfBackend libreOffice;
    private final FlyingSaucerPdfBackend html;
    private final PdfProperties properties;

    public record Result(byte[] bytes, String backend) {}

    public Result render(String markdown, String pageSize, PdfProperties.Engine engine) throws Exception {
        if (engine == null) engine = properties.defaultEngine();

        PdfFrontmatter fm = PdfFrontmatter.parseOrSynthesise(markdown);
        String body = PdfFrontmatter.stripFrontmatter(markdown);
        PdfRenderRequest request = new PdfRenderRequest(body, fm, pageSize, engine);

        PdfBackend chosen = pick(request);
        long t0 = System.currentTimeMillis();
        byte[] bytes = chosen.render(request);
        log.info("[Pdf] rendered via {} ({} bytes, {}ms, frontmatter cover={} header={} footer={})",
                chosen.name(), bytes.length, System.currentTimeMillis() - t0,
                fm.hasCover(), fm.hasHeader(), fm.hasFooter());
        return new Result(bytes, chosen.name());
    }

    private PdfBackend pick(PdfRenderRequest request) {
        return switch (request.engine()) {
            case LIBREOFFICE -> {
                if (!libreOffice.isAvailable()) {
                    throw new IllegalStateException(
                            "engine=libreoffice but soffice is not available on PATH");
                }
                if (!libreOffice.supports(request)) {
                    throw new IllegalStateException(
                            "engine=libreoffice but the request needs cover/header/footer; "
                                    + "use engine=html or remove those frontmatter fields");
                }
                yield libreOffice;
            }
            case HTML -> html;
            case AUTO -> {
                if (libreOffice.isAvailable() && libreOffice.supports(request)) {
                    yield libreOffice;
                }
                yield html;
            }
        };
    }
}
