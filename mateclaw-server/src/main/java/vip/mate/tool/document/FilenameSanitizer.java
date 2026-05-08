package vip.mate.tool.document;

import java.util.Locale;

/**
 * Strip path separators and other characters that are illegal in download
 * filenames from an LLM-supplied name. The LLM is allowed to suffix the
 * extension itself (e.g. "report.docx") — {@link #sanitize} drops a known
 * extension before sanitizing so callers can re-append it consistently.
 */
public final class FilenameSanitizer {

    private FilenameSanitizer() {}

    /**
     * @param name     candidate name from the LLM (may be null / blank / contain ext)
     * @param fallback name to use when {@code name} is null, blank, or sanitizes to empty
     * @param dropExt  optional trailing extension to strip case-insensitively
     *                 before sanitizing (e.g. {@code ".docx"}); pass {@code null}
     *                 to skip
     * @return a non-blank base name with no path separators or shell metacharacters
     */
    public static String sanitize(String name, String fallback, String dropExt) {
        if (name == null) return fallback;
        String trimmed = name.trim();
        if (dropExt != null && !dropExt.isEmpty()
                && trimmed.toLowerCase(Locale.ROOT).endsWith(dropExt.toLowerCase(Locale.ROOT))) {
            trimmed = trimmed.substring(0, trimmed.length() - dropExt.length());
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
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
