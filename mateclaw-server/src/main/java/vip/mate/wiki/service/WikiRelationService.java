package vip.mate.wiki.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.dto.*;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.relation.RelationSignalStrategy;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.repository.WikiPageMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RFC-029: Wiki relation service — computes multi-signal structural
 * relevance between pages using all registered {@link RelationSignalStrategy} beans.
 */
@Slf4j
@Service
public class WikiRelationService {

    private final List<RelationSignalStrategy> signals;
    private final WikiPageMapper pageMapper;
    private final WikiPageService pageService;
    private final WikiPageCitationMapper citationMapper;

    public WikiRelationService(List<RelationSignalStrategy> signals,
                                WikiPageMapper pageMapper,
                                WikiPageService pageService,
                                WikiPageCitationMapper citationMapper) {
        this.signals = signals;
        this.pageMapper = pageMapper;
        this.pageService = pageService;
        this.citationMapper = citationMapper;
    }

    /**
     * Find pages related to a seed page, ranked by multi-signal score.
     */
    public List<RelatedPageResult> relatedPages(Long kbId, String seedSlug, int topK) {
        WikiPageEntity seed = pageService.getBySlug(kbId, seedSlug);
        if (seed == null) return List.of();

        Map<Long, Double> totalScores = new HashMap<>();
        Map<Long, List<String>> signalHits = new HashMap<>();

        for (RelationSignalStrategy signal : signals) {
            try {
                signal.score(seed.getId(), kbId).forEach((pid, s) -> {
                    totalScores.merge(pid, s, Double::sum);
                    signalHits.computeIfAbsent(pid, k -> new ArrayList<>()).add(signal.signalName());
                });
            } catch (Exception e) {
                log.warn("[WikiRelation] Signal '{}' failed for seed={}: {}",
                        signal.signalName(), seedSlug, e.getMessage());
            }
        }

        List<Long> topIds = totalScores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(topK)
            .map(Map.Entry::getKey)
            .toList();

        if (topIds.isEmpty()) return List.of();
        Map<Long, WikiPageLite> liteMap = pageMapper.selectBatchLite(topIds)
            .stream().collect(Collectors.toMap(WikiPageLite::id, l -> l));

        return topIds.stream()
            .filter(liteMap::containsKey)
            .map(pid -> new RelatedPageResult(
                liteMap.get(pid).slug(),
                liteMap.get(pid).title(),
                liteMap.get(pid).summary(),
                totalScores.get(pid),
                signalHits.getOrDefault(pid, List.of())))
            .toList();
    }

    /**
     * Explain the relation between two pages with a per-signal breakdown.
     */
    public RelationExplanation explain(Long kbId, String slugA, String slugB) {
        WikiPageEntity a = pageService.getBySlug(kbId, slugA);
        WikiPageEntity b = pageService.getBySlug(kbId, slugB);
        if (a == null || b == null) return RelationExplanation.notFound();

        List<SignalScore> breakdown = new ArrayList<>();
        double total = 0;
        for (RelationSignalStrategy signal : signals) {
            try {
                Double score = signal.score(a.getId(), kbId).get(b.getId());
                if (score != null && score > 0) {
                    breakdown.add(new SignalScore(signal.signalName(), signal.weight(), score));
                    total += score;
                }
            } catch (Exception e) {
                log.warn("[WikiRelation] Signal '{}' failed for explain {}<->{}: {}",
                        signal.signalName(), slugA, slugB, e.getMessage());
            }
        }
        return new RelationExplanation(slugA, slugB, total, breakdown);
    }

    /**
     * Find all pages derived from a given raw material.
     */
    public List<WikiPageLite> pagesByRawId(Long rawId) {
        List<Long> pageIds = citationMapper.listPageIdsByRawId(rawId);
        if (pageIds.isEmpty()) return List.of();
        return pageMapper.selectBatchLite(pageIds);
    }

    /**
     * Find all pages that cite a given chunk.
     */
    public List<WikiPageLite> pagesByChunkId(Long chunkId) {
        List<Long> pageIds = citationMapper.listPageIdsByChunkId(chunkId);
        if (pageIds.isEmpty()) return List.of();
        return pageMapper.selectBatchLite(pageIds);
    }
}
