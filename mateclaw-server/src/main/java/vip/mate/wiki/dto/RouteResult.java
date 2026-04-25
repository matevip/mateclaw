package vip.mate.wiki.dto;

import java.util.List;

/**
 * RFC-051 PR-6 (skeleton): structured route-phase output.
 * <p>
 * Today {@code processChunkTwoPhase} parses the route LLM's free-form JSON
 * with a hand-rolled extractor, which fails on weak models that emit
 * trailing commentary or mismatched braces. The follow-up wiring will use
 * Spring AI {@code BeanOutputConverter<RouteResult>} so the prompt and the
 * parser share one schema.
 * <p>
 * Defining the type up-front (PR-6 partial) lets the prompt rewrite, parser
 * swap, and call-site change land in one cohesive PR without renames.
 *
 * @param create new pages the router proposes (slug + title + summary)
 * @param update slugs of existing pages that should absorb this chunk
 */
public record RouteResult(List<RoutedPageMeta> create, List<String> update) {

    public RouteResult {
        if (create == null) create = List.of();
        if (update == null) update = List.of();
    }

    public boolean isEmpty() {
        return create.isEmpty() && update.isEmpty();
    }
}
