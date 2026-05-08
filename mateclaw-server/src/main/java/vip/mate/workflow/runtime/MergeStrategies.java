package vip.mate.workflow.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers implementing the four v0 merge strategies the {@code write_memory}
 * step supports. Stateless so the same logic backs the production
 * {@link MemoryWriter} binding and any test fake.
 *
 * <ul>
 *   <li>{@code append} — incoming content is concatenated to the existing
 *       body with a separating blank line. The simplest no-magic merge.</li>
 *   <li>{@code replace_section} — the incoming body's first non-blank line is
 *       expected to be a Markdown {@code ## } heading; if a section with that
 *       heading already exists in the file, it is replaced (heading inclusive
 *       through the line before the next {@code ## } heading or EOF);
 *       otherwise the incoming body is appended with a blank-line separator.</li>
 *   <li>{@code upsert_kv} — every line of the incoming body that matches
 *       {@code key: value} is treated as a key/value pair. Existing matching
 *       keys are updated in place; new keys are appended. Non-kv lines in the
 *       incoming body are dropped (they would otherwise re-introduce
 *       freeform text on every run).</li>
 *   <li>{@code overwrite} — replace the file with the incoming body
 *       verbatim. The escape hatch when no other strategy fits.</li>
 * </ul>
 */
public final class MergeStrategies {

    /** Heading line for {@code replace_section}. */
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^##\\s+.+$", Pattern.MULTILINE);

    /** {@code key: value} line for {@code upsert_kv} parsing. */
    private static final Pattern KV_LINE = Pattern.compile(
            "^([A-Za-z0-9_.\\-]+)\\s*:\\s*(.*)$");

    private MergeStrategies() {}

    public static String apply(String existing, String incoming, String strategy) {
        String existingSafe = existing == null ? "" : existing;
        String incomingSafe = incoming == null ? "" : incoming;
        return switch (strategy) {
            case "append"          -> append(existingSafe, incomingSafe);
            case "replace_section" -> replaceSection(existingSafe, incomingSafe);
            case "upsert_kv"       -> upsertKv(existingSafe, incomingSafe);
            case "overwrite"       -> incomingSafe;
            default -> throw new IllegalArgumentException(
                    "unknown merge strategy '" + strategy
                            + "' — must be append / replace_section / upsert_kv / overwrite");
        };
    }

    private static String append(String existing, String incoming) {
        if (existing.isEmpty()) return incoming;
        if (incoming.isEmpty()) return existing;
        String trimmed = existing.endsWith("\n") ? existing : existing + "\n";
        return trimmed + "\n" + incoming;
    }

    private static String replaceSection(String existing, String incoming) {
        String heading = firstHeading(incoming);
        if (heading == null) {
            // No heading on the incoming side — fall back to append so the
            // step never silently drops content.
            return append(existing, incoming);
        }
        int existingStart = indexOfHeading(existing, heading);
        if (existingStart < 0) {
            return append(existing, incoming);
        }
        int existingEnd = indexOfNextHeading(existing, existingStart + heading.length());
        if (existingEnd < 0) existingEnd = existing.length();
        StringBuilder out = new StringBuilder();
        out.append(existing, 0, existingStart);
        out.append(incoming);
        if (!incoming.endsWith("\n")) out.append('\n');
        if (existingEnd < existing.length()) {
            out.append(existing, existingEnd, existing.length());
        }
        return out.toString();
    }

    private static String firstHeading(String body) {
        Matcher m = SECTION_HEADING.matcher(body);
        return m.find() ? m.group().stripTrailing() : null;
    }

    private static int indexOfHeading(String body, String heading) {
        // Match the heading at start of line (after any line break or at
        // position 0) so we don't false-match an inline "## " inside a code
        // block by accident.
        Pattern p = Pattern.compile("(?m)^" + Pattern.quote(heading) + "\\s*$");
        Matcher m = p.matcher(body);
        return m.find() ? m.start() : -1;
    }

    private static int indexOfNextHeading(String body, int from) {
        Matcher m = SECTION_HEADING.matcher(body);
        if (m.find(from)) return m.start();
        return -1;
    }

    private static String upsertKv(String existing, String incoming) {
        Map<String, String> updates = new LinkedHashMap<>();
        for (String line : incoming.split("\\R", -1)) {
            Matcher m = KV_LINE.matcher(line.trim());
            if (m.matches()) {
                updates.put(m.group(1), m.group(2));
            }
        }
        if (updates.isEmpty()) return existing;

        StringBuilder out = new StringBuilder();
        for (String line : existing.split("\\R", -1)) {
            Matcher m = KV_LINE.matcher(line.trim());
            if (m.matches() && updates.containsKey(m.group(1))) {
                out.append(m.group(1)).append(": ").append(updates.remove(m.group(1)));
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        // Trim trailing empty line we always added so a clean file stays clean.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        // Append any incoming keys that did not exist in the original file.
        for (var e : updates.entrySet()) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(e.getKey()).append(": ").append(e.getValue());
        }
        return out.toString();
    }
}
