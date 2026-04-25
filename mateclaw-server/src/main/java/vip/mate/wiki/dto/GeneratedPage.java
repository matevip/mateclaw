package vip.mate.wiki.dto;

import java.util.List;

/**
 * RFC-051 PR-6 (skeleton): structured create/merge output.
 * <p>
 * Replaces the legacy {@code ---FILE---} block protocol used by
 * {@code WikiBatchCreateParser}. {@code evidenceChunkIds} is optional and
 * intended for the on-demand compile path so per-page citations bind to
 * the chunks the prompt actually consumed.
 * <p>
 * Fields are ordered to mirror the existing prompt template so the
 * follow-up Spring-AI {@code BeanOutputConverter} swap is a one-shot
 * replace of the parser, not a prompt rewrite.
 */
public record GeneratedPage(
        String slug,
        String title,
        String summary,
        String pageType,
        String content,
        List<Long> evidenceChunkIds
) {
    public GeneratedPage {
        if (evidenceChunkIds == null) evidenceChunkIds = List.of();
    }
}
