package vip.mate.wiki.dto;

import java.util.List;

/**
 * RFC-029: Detailed breakdown of the relation between two pages.
 */
public record RelationExplanation(String slugA, String slugB, double totalScore,
                                   List<SignalScore> breakdown) {
    public static RelationExplanation notFound() {
        return new RelationExplanation(null, null, 0, List.of());
    }
}
