package vip.mate.wiki.dto;

import java.math.BigDecimal;

/**
 * RFC-029: Citation record joined with the chunk's raw material ID.
 */
public record PageCitationWithRaw(Long id, Long pageId, Long chunkId, Long rawId,
                                   Integer paragraphIdx, String anchorText, BigDecimal confidence,
                                   String rawTitle, Integer chunkOrdinal,
                                   Integer startOffset, Integer endOffset,
                                   String snippet) {}
