package vip.mate.memory.fact.extraction;

/**
 * A single fact extracted from canonical memory content.
 *
 * @author MateClaw Team
 */
public record ExtractedFact(
        String sourceRef,
        String category,
        String subject,
        String predicate,
        String objectValue,
        double confidence,
        String extractedBy
) {}
