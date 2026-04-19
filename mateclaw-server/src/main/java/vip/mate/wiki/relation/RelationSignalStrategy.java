package vip.mate.wiki.relation;

import java.util.Map;

/**
 * RFC-029: Strategy interface for computing a single relation signal
 * between a seed page and other pages in the knowledge base.
 * <p>
 * Each implementation returns a map of pageId → raw score for pages
 * that have a positive signal. Pages not in the map score 0.
 * The seed page itself is filtered out by {@link vip.mate.wiki.service.WikiRelationService}.
 */
public interface RelationSignalStrategy {

    String signalName();

    double weight();

    /**
     * Compute scores for pages related to the given seed page.
     *
     * @param seedPageId the seed page ID
     * @param kbId       the knowledge base ID
     * @return pageId → weighted score (only positive entries)
     */
    Map<Long, Double> score(Long seedPageId, Long kbId);
}
