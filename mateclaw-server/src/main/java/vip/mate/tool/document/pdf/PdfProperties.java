package vip.mate.tool.document.pdf;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the markdown-to-PDF rendering pipeline.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * mateclaw:
 *   pdf:
 *     fontPath: /Library/Fonts/Songti.ttc
 *     defaultEngine: AUTO
 *     libreoffice:
 *       enabled: true
 *       binary: soffice
 * </pre>
 */
@ConfigurationProperties(prefix = "mateclaw.pdf")
public record PdfProperties(
        String fontPath,
        Engine defaultEngine,
        Libreoffice libreoffice) {

    public PdfProperties {
        if (defaultEngine == null) defaultEngine = Engine.AUTO;
        if (libreoffice == null) libreoffice = new Libreoffice(true, "soffice");
    }

    public enum Engine {
        /** Try LibreOffice first, fall back to OpenHTMLtoPDF. */
        AUTO,
        /** Force the LibreOffice subprocess path. Fails if soffice is missing. */
        LIBREOFFICE,
        /** Force the in-process OpenHTMLtoPDF path. */
        HTML
    }

    public record Libreoffice(boolean enabled, String binary) {
        public Libreoffice {
            if (binary == null || binary.isBlank()) binary = "soffice";
        }
    }
}
