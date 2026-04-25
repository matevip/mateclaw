package vip.mate.wiki.dto;

/**
 * RFC-051 PR-1a: a chunk-to-be along with the structural metadata produced by
 * {@code DocumentPreprocessService} / {@code WikiContentNormalizer}.
 * <p>
 * This carries everything {@link vip.mate.wiki.service.WikiChunkService} needs
 * to persist a chunk row, including the new metadata columns added in V39:
 * {@code page_number}, {@code token_count}, {@code header_breadcrumb},
 * {@code source_section}. The structural fields are nullable because not every
 * source format yields each piece of metadata (e.g. plain text has no page
 * number; HTML has no slide id).
 * <p>
 * No production callers in PR-1a — wiring lands in PR-1c. The type is added
 * here so the persistence overload in PR-1a can be unit-tested independently.
 */
public record WikiChunkDraft(
        String content,
        int startOffset,
        int endOffset,
        Integer pageNumber,
        Integer tokenCount,
        String headerBreadcrumb,
        String sourceSection
) {
}
