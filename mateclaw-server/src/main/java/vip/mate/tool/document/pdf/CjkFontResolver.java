package vip.mate.tool.document.pdf;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Locate a font file capable of rendering CJK text for {@link OpenHtmlToPdfBackend}.
 *
 * <p>OpenHTMLtoPDF renders any glyph the registered font does not cover as a
 * blank {@code .notdef} box, so a CJK-capable font is mandatory whenever the
 * markdown contains Chinese, Japanese, or Korean text. We try in this order:
 * <ol>
 *   <li>An explicit {@code mateclaw.pdf.font-path} configuration value.</li>
 *   <li>A short list of OS-default paths that ship with macOS / Windows / common
 *       Linux distributions. The first existing file wins.</li>
 *   <li>{@link Optional#empty()} — the renderer falls back to PDFBox's built-in
 *       Latin-only fonts, which renders Chinese as boxes; logged as a warning.</li>
 * </ol>
 */
@Slf4j
public final class CjkFontResolver {

    // .ttf candidates are listed FIRST because OpenPDF 2.0.5 (used by the
    // FlyingSaucer PDF backend) cannot reliably read Apple-style .ttc font
    // collections — it loads them without throwing, but the resulting
    // BaseFont has an empty cmap and reports `charExists` as false even for
    // ASCII. The PDF then renders as a blank page. .ttf collections do not
    // share that limitation, so we try them first and only fall through to
    // .ttc when nothing else is available. The runtime charExists check in
    // FlyingSaucerPdfBackend will reject any candidate that loads but
    // cannot actually render glyphs.

    private static final String USER_HOME = System.getProperty("user.home", "");

    private static final List<String> CANDIDATES_MACOS = List.of(
            // Popular open-source CJK .ttf fonts that users commonly install
            USER_HOME + "/Library/Fonts/HarmonyOS_SansSC_Regular.ttf",
            "/Library/Fonts/HarmonyOS_SansSC_Regular.ttf",
            USER_HOME + "/Library/Fonts/SourceHanSansSC-Regular.otf",
            "/Library/Fonts/SourceHanSansSC-Regular.otf",
            USER_HOME + "/Library/Fonts/NotoSansSC-Regular.ttf",
            "/Library/Fonts/NotoSansSC-Regular.ttf",
            USER_HOME + "/Library/Fonts/Arial Unicode.ttf",
            "/Library/Fonts/Arial Unicode.ttf",
            // .ttc fallbacks — known to be lossy under OpenPDF on macOS,
            // but listed so the resolver can still warn about them.
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc",
            "/System/Library/Fonts/STHeiti Medium.ttc",
            "/Library/Fonts/Songti.ttc");

    private static final List<String> CANDIDATES_WINDOWS = List.of(
            // Plain .ttf first, .ttc / .otf later
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simhei.ttf", // 黑体
            "C:/Windows/Fonts/simsun.ttf",
            "C:/Windows/Fonts/HarmonyOS_SansSC_Regular.ttf",
            "C:/Windows/Fonts/NotoSansSC-Regular.ttf",
            // Collections last
            "C:/Windows/Fonts/msyh.ttc",   // 微软雅黑
            "C:/Windows/Fonts/simsun.ttc"); // 宋体

    private static final List<String> CANDIDATES_LINUX = List.of(
            // Plain .ttf / .otf first
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf",
            "/usr/share/fonts/truetype/harmonyos-sans/HarmonyOS_SansSC_Regular.ttf",
            "/usr/share/fonts/truetype/source-han-sans/SourceHanSansSC-Regular.otf",
            // Collections last
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/truetype/arphic/ukai.ttc");

    private CjkFontResolver() {}

    public static Optional<Path> resolve(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path explicit = Paths.get(configuredPath.trim());
            if (Files.isRegularFile(explicit)) {
                log.debug("[CjkFont] using configured font: {}", explicit);
                return Optional.of(explicit);
            }
            log.warn("[CjkFont] configured font path does not exist: {}", explicit);
        }

        for (String candidate : candidatesForCurrentOs()) {
            Path p = Paths.get(candidate);
            if (Files.isRegularFile(p)) {
                log.debug("[CjkFont] auto-detected system font: {}", p);
                return Optional.of(p);
            }
        }
        log.warn("[CjkFont] no CJK font found on this host; PDF Chinese characters "
                + "will render as blank boxes. Set mateclaw.pdf.font-path to override.");
        return Optional.empty();
    }

    private static List<String> candidatesForCurrentOs() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) return CANDIDATES_MACOS;
        if (osName.contains("win")) return CANDIDATES_WINDOWS;
        return CANDIDATES_LINUX;
    }
}
