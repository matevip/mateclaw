package vip.mate.wiki.service;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC-047 P1: Stateful parser for the BatchCreate LLM response format.
 * <p>
 * Expected format:
 * <pre>
 * ---FILE: slug-one---
 * {"slug":"slug-one","title":"Title One","summary":"...","content":"..."}
 * ---END FILE---
 *
 * ---FILE: slug-two---
 * {"slug":"slug-two","title":"Title Two","summary":"...","content":"..."}
 * ---END FILE---
 * </pre>
 * <p>
 * Design notes:
 * - One FILE block per page; JSON payload is a single object (not NDJSON).
 * - Malformed blocks (missing END FILE, blank JSON body) are skipped with a warning.
 * - Trailing / leading whitespace within a block is trimmed before JSON parse.
 * - Extra text outside FILE blocks (preamble, commentary) is silently ignored.
 */
public class WikiBatchCreateParser {

    /** Parsed page from a single FILE block. */
    public record ParsedPage(String slug, String rawJson) {}

    private static final String FILE_START_PREFIX = "---FILE:";
    private static final String FILE_END_MARKER = "---END FILE---";

    /**
     * Parse the full BatchCreate LLM response into a list of parsed pages.
     * Never throws — malformed blocks produce warnings but parsing continues.
     *
     * @param response raw LLM text output
     * @return ordered list of successfully parsed pages (may be empty)
     */
    public List<ParsedPage> parse(String response) {
        List<ParsedPage> result = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return result;
        }

        String[] lines = response.split("\n", -1);
        State state = State.OUTSIDE;
        String currentSlug = null;
        StringBuilder bodyBuffer = null;

        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();

            switch (state) {
                case OUTSIDE -> {
                    if (isFileStart(line)) {
                        currentSlug = extractSlug(line);
                        bodyBuffer = new StringBuilder();
                        state = State.INSIDE;
                    }
                    // Everything else outside blocks is silently ignored
                }
                case INSIDE -> {
                    if (line.trim().equals(FILE_END_MARKER)) {
                        String json = bodyBuffer.toString().strip();
                        if (!json.isBlank() && currentSlug != null) {
                            result.add(new ParsedPage(currentSlug, json));
                        }
                        currentSlug = null;
                        bodyBuffer = null;
                        state = State.OUTSIDE;
                    } else if (isFileStart(line)) {
                        // New FILE block without END FILE — previous block is malformed; start fresh
                        currentSlug = extractSlug(line);
                        bodyBuffer = new StringBuilder();
                        // Stay in INSIDE state
                    } else {
                        bodyBuffer.append(line).append('\n');
                    }
                }
            }
        }

        // Unclosed block at EOF — discard
        return result;
    }

    private boolean isFileStart(String line) {
        return line.startsWith(FILE_START_PREFIX);
    }

    /**
     * Extract slug from a line like {@code ---FILE: slug-name---}.
     * Returns the text between the first colon+space and the trailing "---" (or end of line).
     */
    private String extractSlug(String line) {
        // line starts with "---FILE:"
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) return "";
        String after = line.substring(colonIdx + 1).strip();
        if (after.endsWith("---")) {
            after = after.substring(0, after.length() - 3).strip();
        }
        return after;
    }

    private enum State { OUTSIDE, INSIDE }
}
