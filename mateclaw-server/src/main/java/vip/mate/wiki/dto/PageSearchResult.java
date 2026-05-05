package vip.mate.wiki.dto;

import java.util.List;

/**
 * Enhanced search hit returned by hybrid retrieval.
 *
 * <p>Carries the matched page identity ({@link #slug}, {@link #title},
 * {@link #summary}), a query-specific {@link #snippet} extracted from the
 * page body, the per-mode score breakdown ({@link #matchedBy}) and a
 * human-readable {@link #reason} for why the page surfaced. The
 * {@link #imageRefs} field is populated with up to N image references
 * pulled from the page body so the UI can render thumbnails inline with
 * the hit list.
 */
public record PageSearchResult(
    String slug,
    String title,
    String summary,
    String snippet,
    List<String> matchedBy,
    String reason,
    double score,
    List<ImageRef> imageRefs
) {

    /** Convenience factory used by callers that have not (yet) computed image refs. */
    public static PageSearchResult of(String slug, String title, String summary, String snippet,
                                       List<String> matchedBy, String reason, double score) {
        return new PageSearchResult(slug, title, summary, snippet, matchedBy, reason, score, List.of());
    }
}
