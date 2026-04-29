package vip.mate.wiki.dto;

/**
 * RFC-029: Lightweight page projection without content.
 * <p>
 * RFC-051 PR-2: {@code pageType} added so callers can default-filter system
 * pages from list/search/related results. Backwards-compatible 4-arg
 * factory keeps older constructors (e.g. {@code new WikiPageLite(id, slug,
 * title, summary)}) compiling — they yield {@code pageType=null}, which the
 * filter treats as "not system".
 */
public record WikiPageLite(Long id, String slug, String title, String summary, String pageType) {

    public WikiPageLite(Long id, String slug, String title, String summary) {
        this(id, slug, title, summary, null);
    }

    /** True when this page should be hidden from default tool/search results. */
    public boolean isSystem() {
        return "system".equals(pageType);
    }
}
