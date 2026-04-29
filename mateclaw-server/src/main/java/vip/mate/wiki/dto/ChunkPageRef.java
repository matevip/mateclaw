package vip.mate.wiki.dto;

/**
 * RFC-029: Lightweight chunk-to-page reference for shared-chunk signal computation.
 */
public record ChunkPageRef(Long chunkId, Long pageId) {}
