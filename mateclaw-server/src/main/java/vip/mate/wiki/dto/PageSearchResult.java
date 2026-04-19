package vip.mate.wiki.dto;

import java.util.List;

/**
 * RFC-032: Enhanced search result with snippet, match metadata, and relevance reason.
 */
public record PageSearchResult(
    String slug,
    String title,
    String summary,
    String snippet,
    List<String> matchedBy,
    String reason,
    double score
) {}
