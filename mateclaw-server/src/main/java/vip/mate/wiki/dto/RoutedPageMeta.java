package vip.mate.wiki.dto;

/**
 * RFC-051 PR-6 (skeleton): one entry of {@link RouteResult#create()}.
 * <p>
 * {@code purposeHint} is optional; when populated by the router it feeds
 * the create-page prompt's framing.
 */
public record RoutedPageMeta(String slug, String title, String summary, String purposeHint) {

    public RoutedPageMeta(String slug, String title, String summary) {
        this(slug, title, summary, null);
    }
}
