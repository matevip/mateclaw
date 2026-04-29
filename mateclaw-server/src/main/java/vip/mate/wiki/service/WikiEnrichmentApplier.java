package vip.mate.wiki.service;

import vip.mate.wiki.dto.EnrichmentPlan;
import vip.mate.wiki.dto.EnrichmentReplacement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC-051 PR-5b: validate and apply a replacement-plan enrichment without
 * letting the LLM touch non-link prose.
 * <p>
 * The applier is intentionally pure: no Spring beans, no I/O. That keeps the
 * critical path testable as a plain JUnit class and lets the round-trip
 * invariant ("stripped text equals original") get pinned down in one place.
 *
 * <h2>Invariants</h2>
 * <ol>
 *   <li>Each {@link EnrichmentReplacement#replacement()} must be a wikilink
 *       in {@code [[slug]]} or {@code [[slug|label]]} form.</li>
 *   <li>The visible text of the replacement (slug for the bare form, label
 *       for the alias form) must equal {@link EnrichmentReplacement#original()}
 *       byte-for-byte.</li>
 *   <li>After applying every replacement, stripping all wikilinks back to
 *       their visible text must yield exactly the input page content.</li>
 * </ol>
 * Failures abort the apply and return {@link Result#rejected(String)}; the
 * caller is expected to leave the page untouched on rejection.
 */
public final class WikiEnrichmentApplier {

    /** Default cap so a runaway LLM can't propose 1000 wraps per page. */
    public static final int DEFAULT_MAX_REPLACEMENTS = 50;

    /** Matches {@code [[anything]]} (greedy through to the next ]] but not nested). */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\[\\]]+)]]");

    /** Matches {@code [[slug]]} or {@code [[slug|label]]} on the replacement string. */
    private static final Pattern REPLACEMENT_SHAPE =
            Pattern.compile("^\\[\\[([^\\[\\]|]+)(?:\\|([^\\[\\]]+))?]]$");

    private WikiEnrichmentApplier() {}

    public static Result apply(String originalContent, EnrichmentPlan plan) {
        return apply(originalContent, plan, DEFAULT_MAX_REPLACEMENTS);
    }

    public static Result apply(String originalContent, EnrichmentPlan plan, int maxReplacements) {
        if (originalContent == null) return Result.rejected("content is null");
        if (plan == null || plan.isEmpty()) {
            return Result.unchanged(originalContent);
        }
        if (plan.replacements().size() > maxReplacements) {
            return Result.rejected("too many replacements: "
                    + plan.replacements().size() + " > " + maxReplacements);
        }

        // 1) Per-original index of all candidate positions in the original text,
        //    skipping positions that fall inside an existing wikilink.
        java.util.Map<String, List<Integer>> positionsByOriginal = new java.util.HashMap<>();
        boolean[] insideWikilink = computeWikilinkMask(originalContent);

        // 2) Plan splices: for each replacement pick positions[occurrence-1].
        List<int[]> splices = new ArrayList<>(); // [start, end, replacementIndex]
        List<String> replacementTexts = new ArrayList<>();
        for (EnrichmentReplacement r : plan.replacements()) {
            String original = r.original();
            String replacement = r.replacement();
            if (original == null || original.isEmpty()) {
                return Result.rejected("empty original in replacement");
            }
            Matcher shape = REPLACEMENT_SHAPE.matcher(replacement == null ? "" : replacement);
            if (!shape.matches()) {
                return Result.rejected("replacement is not a wikilink form: " + replacement);
            }
            String slug = shape.group(1).trim();
            String label = shape.group(2);
            String visible = (label == null) ? slug : label;
            if (!visible.equals(original)) {
                return Result.rejected("visible text mismatch: replacement='"
                        + replacement + "' must render '" + original + "'");
            }

            List<Integer> positions = positionsByOriginal.computeIfAbsent(original,
                    o -> findPositions(originalContent, o, insideWikilink));
            int idx = r.occurrence() - 1;
            if (idx < 0 || idx >= positions.size()) {
                // Skip silently — the page may have been re-edited since the LLM saw it.
                continue;
            }
            int start = positions.get(idx);
            splices.add(new int[]{start, start + original.length(), replacementTexts.size()});
            replacementTexts.add(replacement);
        }

        if (splices.isEmpty()) {
            return Result.unchanged(originalContent);
        }

        // 3) Apply in reverse offset order so earlier indices don't shift.
        splices.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder(originalContent);
        java.util.Set<Integer> claimed = new java.util.HashSet<>();
        int applied = 0;
        for (int[] sp : splices) {
            int start = sp[0];
            int end = sp[1];
            // Reject overlapping splices defensively.
            for (int i = start; i < end; i++) {
                if (claimed.contains(i)) {
                    return Result.rejected("overlapping splice at " + start);
                }
            }
            sb.replace(start, end, replacementTexts.get(sp[2]));
            for (int i = start; i < end; i++) claimed.add(i);
            applied++;
        }

        // 4) Round-trip: stripping wikilinks from the result must reproduce input.
        String enriched = sb.toString();
        if (!stripWikilinks(enriched).equals(stripWikilinks(originalContent))) {
            return Result.rejected("round-trip invariant violated");
        }
        return Result.applied(enriched, applied);
    }

    /**
     * Strip every {@code [[...]]} down to its visible text:
     * <ul>
     *   <li>{@code [[slug]]} → {@code slug}</li>
     *   <li>{@code [[slug|label]]} → {@code label}</li>
     * </ul>
     * Used both for round-trip validation and for diff testing.
     */
    public static String stripWikilinks(String content) {
        if (content == null) return "";
        Matcher m = WIKILINK.matcher(content);
        StringBuilder out = new StringBuilder(content.length());
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            String inner = m.group(1);
            int pipe = inner.indexOf('|');
            String visible = pipe >= 0 ? inner.substring(pipe + 1) : inner;
            out.append(visible);
            last = m.end();
        }
        out.append(content, last, content.length());
        return out.toString();
    }

    private static boolean[] computeWikilinkMask(String content) {
        boolean[] mask = new boolean[content.length()];
        Matcher m = WIKILINK.matcher(content);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) mask[i] = true;
        }
        return mask;
    }

    private static List<Integer> findPositions(String content, String needle, boolean[] insideWikilink) {
        List<Integer> out = new ArrayList<>();
        if (needle.isEmpty()) return out;
        int from = 0;
        while (from <= content.length() - needle.length()) {
            int p = content.indexOf(needle, from);
            if (p < 0) break;
            // Skip if any byte of the match falls inside an existing wikilink.
            boolean overlap = false;
            for (int i = p; i < p + needle.length(); i++) {
                if (insideWikilink[i]) { overlap = true; break; }
            }
            if (!overlap) out.add(p);
            from = p + 1;
        }
        return out;
    }

    /**
     * Outcome of {@link #apply(String, EnrichmentPlan)}.
     */
    public sealed interface Result permits Result.Applied, Result.Unchanged, Result.Rejected {

        String content();

        static Result applied(String content, int count) { return new Applied(content, count); }
        static Result unchanged(String content) { return new Unchanged(content); }
        static Result rejected(String reason) { return new Rejected(reason); }

        record Applied(String content, int replacementCount) implements Result {}
        record Unchanged(String content) implements Result {}
        record Rejected(String reason) implements Result {
            @Override public String content() { return null; }
        }
    }
}
