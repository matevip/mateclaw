package vip.mate.memory.fact.extraction;

/**
 * A single fact extracted from canonical memory content.
 *
 * @param trust derived from UserFeedback metadata in the canonical section
 *              (0.5 base + helpful*0.1 - unhelpful*0.2, clamped [0,1])
 * @author MateClaw Team
 */
public record ExtractedFact(
        String sourceRef,
        String category,
        String subject,
        String predicate,
        String objectValue,
        double confidence,
        double trust,
        String extractedBy
) {}
