package vip.mate.wiki.dto;

/**
 * RFC-029: Lightweight page projection without content.
 */
public record WikiPageLite(Long id, String slug, String title, String summary) {}
