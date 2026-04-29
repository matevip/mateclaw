package vip.mate.wiki.dto;

/**
 * RFC-051 PR-5b: one entry of {@link EnrichmentPlan}.
 * <p>
 * Semantics:
 * <ul>
 *   <li>{@code original} — literal text that must already exist in the page.</li>
 *   <li>{@code replacement} — must be a wikilink form, either {@code [[slug]]}
 *       or {@code [[slug|label]]}. The visible text after wrapping has to
 *       equal {@code original}, otherwise the replacement is rejected.</li>
 *   <li>{@code occurrence} — 1-based index. {@code 1} means the first
 *       occurrence in the page; {@code 2} means the second; and so on. Counts
 *       skip text already inside another wikilink.</li>
 * </ul>
 * Default occurrence is 1 when the LLM omits the field.
 */
public record EnrichmentReplacement(String original, String replacement, int occurrence) {

    public EnrichmentReplacement {
        if (occurrence <= 0) occurrence = 1;
    }
}
