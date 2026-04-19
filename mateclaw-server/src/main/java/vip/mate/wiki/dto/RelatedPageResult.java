package vip.mate.wiki.dto;

import java.util.List;

/**
 * RFC-029: A related page with aggregated relation score and contributing signals.
 */
public record RelatedPageResult(String slug, String title, String summary,
                                 double score, List<String> signals) {}
