package vip.mate.tool.browser;

import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Diagnoses why the browser tool might fail to launch on this host.
 *
 * <p>Runs a dry inventory — detecting system browsers, Playwright cache, Node runtime,
 * required shared libraries on Linux, container / root context — without actually
 * launching a session. Produces a structured report with actionable next steps.
 */
@Slf4j
@Service
public class BrowserDiagnosticsService {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    private static final boolean IS_LINUX = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("linux");

    /** Shared libraries Chromium needs on Linux. Missing any is a hard block. */
    private static final List<String> REQUIRED_LINUX_LIBS = List.of(
            "libnss3", "libgbm", "libasound", "libxkbcommon", "libx11", "libxcomposite",
            "libxdamage", "libxrandr", "libxfixes", "libatk", "libcups", "libpango"
    );

    private final BrowserProperties props;

    public BrowserDiagnosticsService(BrowserProperties props) {
        this.props = props;
    }

    public Report run() {
        List<Finding> findings = new ArrayList<>();
        findings.add(inspectEnvironment());
        findings.add(inspectConfiguredCdp());
        findings.add(inspectConfiguredPath());
        findings.add(inspectEnvPath());
        findings.add(inspectSystemBrowsers());
        findings.add(inspectPlaywrightCache());
        if (IS_LINUX) {
            findings.add(inspectLinuxLibs());
        }

        String overall = deriveOverall(findings);
        List<String> advice = deriveAdvice(findings);
        return new Report(overall, findings, advice);
    }

    // ==================== Individual probes ====================

    private Finding inspectEnvironment() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("os", System.getProperty("os.name"));
        data.put("arch", System.getProperty("os.arch"));
        data.put("user", System.getProperty("user.name"));
        data.put("container", BrowserLauncher.isRunningInContainer());
        data.put("root", BrowserLauncher.isRunningAsRoot());
        return new Finding("environment", Status.INFO, "Runtime environment", data, null);
    }

    private Finding inspectConfiguredCdp() {
        String url = props.getCdpUrl();
        if (url == null || url.isBlank()) {
            return new Finding("config.cdp-url", Status.INFO, "mateclaw.browser.cdp-url not set", Map.of(), null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        try {
            String resp = HttpUtil.get(stripTrailing(url) + "/json/version", 2000);
            if (resp != null && resp.contains("webSocketDebuggerUrl")) {
                data.put("reachable", true);
                return new Finding("config.cdp-url", Status.OK,
                        "CDP endpoint reachable", data, null);
            }
            data.put("reachable", false);
            data.put("response", resp);
            return new Finding("config.cdp-url", Status.ERROR,
                    "CDP endpoint did not return a valid /json/version payload", data,
                    "Ensure Chrome was started with --remote-debugging-port=" + port(url) + " and /json/version is reachable.");
        } catch (Exception e) {
            data.put("error", e.getMessage());
            return new Finding("config.cdp-url", Status.ERROR,
                    "CDP endpoint unreachable: " + e.getMessage(), data,
                    "Start Chrome with --remote-debugging-port or clear mateclaw.browser.cdp-url.");
        }
    }

    private Finding inspectConfiguredPath() {
        String path = props.getChromePath();
        if (path == null || path.isBlank()) {
            return new Finding("config.chrome-path", Status.INFO, "mateclaw.browser.chrome-path not set", Map.of(), null);
        }
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            return new Finding("config.chrome-path", Status.ERROR,
                    "Configured chrome-path does not exist: " + path, Map.of("path", path),
                    "Install Chrome at that path, or clear mateclaw.browser.chrome-path.");
        }
        if (!Files.isExecutable(p)) {
            return new Finding("config.chrome-path", Status.ERROR,
                    "Configured chrome-path is not executable: " + path, Map.of("path", path),
                    "chmod +x the binary, or point to the real chrome executable.");
        }
        return new Finding("config.chrome-path", Status.OK, "Configured chrome-path is valid",
                Map.of("path", path), null);
    }

    private Finding inspectEnvPath() {
        String env = System.getenv("CHROME_PATH");
        if (env == null || env.isBlank()) {
            return new Finding("env.CHROME_PATH", Status.INFO, "CHROME_PATH not set", Map.of(), null);
        }
        Path p = Path.of(env);
        if (!Files.exists(p)) {
            return new Finding("env.CHROME_PATH", Status.WARN,
                    "CHROME_PATH points to a missing file: " + env, Map.of("path", env),
                    "Fix CHROME_PATH or unset it to let auto-detection run.");
        }
        return new Finding("env.CHROME_PATH", Status.OK, "CHROME_PATH resolves to a real file",
                Map.of("path", env), null);
    }

    private Finding inspectSystemBrowsers() {
        List<Map<String, Object>> found = new ArrayList<>();
        for (Path candidate : BrowserLauncher.systemBrowserCandidates()) {
            if (Files.exists(candidate)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", candidate.toString());
                entry.put("executable", Files.isExecutable(candidate));
                found.add(entry);
            }
        }
        if (found.isEmpty()) {
            return new Finding("system.browsers", Status.WARN,
                    "No system Chrome / Edge / Brave found on well-known paths",
                    Map.of("scanned", BrowserLauncher.systemBrowserCandidates().stream().map(Path::toString).toList()),
                    installBrowserAdvice());
        }
        return new Finding("system.browsers", Status.OK,
                "Found " + found.size() + " system browser(s)", Map.of("found", found), null);
    }

    private Finding inspectPlaywrightCache() {
        Path cacheDir = playwrightCacheDir();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cacheDir", cacheDir.toString());
        if (!Files.isDirectory(cacheDir)) {
            return new Finding("playwright.cache", Status.WARN,
                    "Playwright browser cache not found (bundled chromium unavailable)", data,
                    "Run `mvn exec:java -e -Dexec.mainClass=\"com.microsoft.playwright.CLI\" -Dexec.args=\"install chromium\"` " +
                            "or rely on system Chrome (recommended).");
        }
        try (var stream = Files.list(cacheDir)) {
            List<String> entries = stream.map(p -> p.getFileName().toString()).filter(n -> n.contains("chromium")).toList();
            data.put("chromiumBuilds", entries);
            if (entries.isEmpty()) {
                return new Finding("playwright.cache", Status.WARN,
                        "Playwright cache has no chromium build", data,
                        "Run playwright install chromium or use system Chrome.");
            }
            return new Finding("playwright.cache", Status.OK,
                    "Playwright bundled chromium available (" + entries.size() + " build(s))", data, null);
        } catch (IOException e) {
            data.put("error", e.getMessage());
            return new Finding("playwright.cache", Status.WARN,
                    "Failed to read Playwright cache: " + e.getMessage(), data, null);
        }
    }

    private Finding inspectLinuxLibs() {
        // Pick the first available system browser to ldd-check.
        Path binary = BrowserLauncher.systemBrowserCandidates().stream()
                .filter(Files::exists).findFirst().orElse(null);
        if (binary == null) {
            return new Finding("linux.libs", Status.INFO, "No system browser to ldd-check", Map.of(), null);
        }
        try {
            Process p = new ProcessBuilder("ldd", binary.toString())
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            String dump = out.toString();
            List<String> missing = new ArrayList<>();
            for (String line : dump.split("\n")) {
                if (line.contains("not found")) {
                    missing.add(line.trim());
                }
            }
            if (!missing.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("binary", binary.toString());
                data.put("missing", missing);
                return new Finding("linux.libs", Status.ERROR,
                        "Chromium shared libraries missing — browser will fail to start", data,
                        "apt-get install -y " + String.join(" ", REQUIRED_LINUX_LIBS.stream().map(l -> l + "-dev").toList())
                                + "  (or your distro's equivalent)");
            }
            return new Finding("linux.libs", Status.OK, "All required shared libraries resolved",
                    Map.of("binary", binary.toString()), null);
        } catch (Exception e) {
            return new Finding("linux.libs", Status.INFO,
                    "ldd probe failed: " + e.getMessage(), Map.of(), null);
        }
    }

    // ==================== Helpers ====================

    private static Path playwrightCacheDir() {
        String override = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (override != null && !override.isBlank() && !"0".equals(override)) {
            return Path.of(override);
        }
        String home = System.getProperty("user.home");
        if (IS_WINDOWS) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                return Path.of(local, "ms-playwright");
            }
            return Path.of(home, "AppData", "Local", "ms-playwright");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return Path.of(home, "Library", "Caches", "ms-playwright");
        }
        return Path.of(home, ".cache", "ms-playwright");
    }

    private static String stripTrailing(String url) {
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String port(String url) {
        int colon = url.lastIndexOf(':');
        if (colon < 0) return "?";
        String tail = url.substring(colon + 1);
        int slash = tail.indexOf('/');
        return slash > 0 ? tail.substring(0, slash) : tail;
    }

    private static String installBrowserAdvice() {
        if (IS_WINDOWS) {
            return "Install Chrome (https://www.google.com/chrome/) or Edge, or set mateclaw.browser.chrome-path.";
        }
        if (IS_LINUX) {
            return "Install Chrome: `wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | apt-key add - && apt install google-chrome-stable` or `apt install chromium`.";
        }
        return "Install Chrome or Edge, or set mateclaw.browser.chrome-path to point at a browser binary.";
    }

    private static String deriveOverall(List<Finding> findings) {
        boolean hasError = findings.stream().anyMatch(f -> f.status == Status.ERROR);
        boolean hasWarn = findings.stream().anyMatch(f -> f.status == Status.WARN);
        boolean canLaunch = findings.stream().anyMatch(
                f -> f.status == Status.OK && (f.id.equals("system.browsers")
                        || f.id.equals("config.cdp-url") || f.id.equals("config.chrome-path")
                        || f.id.equals("playwright.cache")));
        if (canLaunch && !hasError) return "healthy";
        if (canLaunch) return "warning";
        if (hasError || !canLaunch) return "error";
        return hasWarn ? "warning" : "healthy";
    }

    private static List<String> deriveAdvice(List<Finding> findings) {
        List<String> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.advice != null && (f.status == Status.ERROR || f.status == Status.WARN)) {
                out.add("[" + f.id + "] " + f.advice);
            }
        }
        if (out.isEmpty()) {
            out.add("Browser stack looks healthy.");
        }
        return out;
    }

    // ==================== Records ====================

    public enum Status { OK, WARN, ERROR, INFO }

    public record Finding(String id, Status status, String message, Map<String, Object> data, String advice) {}

    public record Report(String overall, List<Finding> findings, List<String> advice) {}

    /** Summarise the report as a short string suitable for logs / tool responses. */
    public static String summarise(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Browser diagnostics: ").append(r.overall).append('\n');
        for (Finding f : r.findings) {
            sb.append("  [").append(f.status).append("] ").append(f.id).append(" — ").append(f.message).append('\n');
        }
        if (!r.advice.isEmpty()) {
            sb.append("Advice:\n");
            for (String a : r.advice) sb.append("  - ").append(a).append('\n');
        }
        return sb.toString();
    }
}
