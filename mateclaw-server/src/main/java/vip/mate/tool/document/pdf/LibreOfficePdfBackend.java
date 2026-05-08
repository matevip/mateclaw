package vip.mate.tool.document.pdf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.document.MarkdownDocxRenderer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Render PDF by routing markdown through {@link MarkdownDocxRenderer} and
 * then handing the docx to a {@code soffice --convert-to pdf} subprocess.
 * LibreOffice's typesetter beats anything we can write in-process for plain
 * narrative text, especially with mixed CJK + Latin scripts, so this is the
 * preferred path when the local install has it.
 *
 * <p>Limitations the orchestrator must respect:
 * <ul>
 *   <li>The intermediate docx has no first-class cover page, page header,
 *       or page footer the way {@link OpenHtmlToPdfBackend} does. Calls that
 *       want those features go to the HTML path instead — see
 *       {@link #supports(PdfRenderRequest)}.</li>
 *   <li>Page numbers themselves come for free: LibreOffice adds them by
 *       default during PDF export.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibreOfficePdfBackend implements PdfBackend {

    private static final long CONVERT_TIMEOUT_SECONDS = 90;

    private final MarkdownDocxRenderer docxRenderer;
    private final PdfProperties properties;

    @Override
    public String name() { return "libreoffice"; }

    @Override
    public boolean isAvailable() {
        if (!properties.libreoffice().enabled()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(properties.libreoffice().binary(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain stdout so the child can exit even on systems whose pipe buffers
            // are tiny; the version string is short, this won't block.
            p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[LibreOfficePdf] soffice probe failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The docx intermediate cannot carry page headers / footers / an explicit
     * cover page, so we decline requests that need those. The orchestrator
     * routes such requests to {@link FlyingSaucerPdfBackend} instead.
     *
     * <p>Synthetic covers (an H1 that {@code parseOrSynthesise} promoted into a
     * cover title) are NOT rejected — those would otherwise force AUTO mode to
     * pick the in-process backend for almost every markdown body, since LLM
     * output overwhelmingly starts with a {@code # H1}. The H1 will simply
     * render as the document's first heading, which is what users expect when
     * they didn't ask for a cover explicitly.
     */
    @Override
    public boolean supports(PdfRenderRequest request) {
        PdfFrontmatter fm = request.frontmatter();
        return !fm.hasExplicitCover() && !fm.hasHeader() && !fm.hasFooter();
    }

    @Override
    public byte[] render(PdfRenderRequest request) throws Exception {
        // Use the same A4/LETTER page-size argument shape MarkdownDocxRenderer expects.
        byte[] docxBytes = docxRenderer.render(request.markdown(), request.pageSize());

        Path tempDir = Files.createTempDirectory("mc_pdf_");
        try {
            Path docxFile = tempDir.resolve("input.docx");
            Files.write(docxFile, docxBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    properties.libreoffice().binary(),
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", tempDir.toString(),
                    docxFile.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] stderr = p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("soffice conversion timed out after " + CONVERT_TIMEOUT_SECONDS + "s");
            }
            if (p.exitValue() != 0) {
                throw new IOException("soffice exit " + p.exitValue() + ": "
                        + new String(stderr).strip());
            }

            Path pdfFile = tempDir.resolve("input.pdf");
            if (!Files.isRegularFile(pdfFile)) {
                throw new IOException("soffice produced no PDF (stderr: "
                        + new String(stderr).strip() + ")");
            }
            return Files.readAllBytes(pdfFile);
        } finally {
            cleanup(tempDir);
        }
    }

    private void cleanup(Path tempDir) {
        try (var stream = Files.walk(tempDir)) {
            List<Path> entries = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the temp dir lives inside java.io.tmpdir
                    // and will be reclaimed by the OS on next reboot if we lose the race.
                }
            }
        } catch (IOException ignored) {
            // ditto
        }
        // Suppress IDE warning about unused parameter when File.delete fails silently.
        File f = tempDir.toFile();
        if (f.exists() && !f.delete()) {
            log.debug("[LibreOfficePdf] could not delete temp dir {}", tempDir);
        }
    }
}
