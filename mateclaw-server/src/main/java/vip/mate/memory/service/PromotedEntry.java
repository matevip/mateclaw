package vip.mate.memory.service;

/**
 * A candidate that was adopted into MEMORY.md during a dream.
 *
 * @author MateClaw Team
 */
public record PromotedEntry(
        Long recallId,
        String filename,
        String snippetPreview,
        double score
) {}
