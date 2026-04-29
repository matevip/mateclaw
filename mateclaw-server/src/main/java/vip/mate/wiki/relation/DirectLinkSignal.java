package vip.mate.wiki.relation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.service.WikiPageService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-029: Direct-link signal — pages connected via [[wikilinks]]
 * are related (weight = 2.0). Checks both outgoing and incoming links.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectLinkSignal implements RelationSignalStrategy {

    private final WikiPageMapper pageMapper;
    private final WikiPageService pageService;
    private final ObjectMapper objectMapper;

    @Override
    public String signalName() { return "direct_link"; }

    @Override
    public double weight() { return 2.0; }

    @Override
    public Map<Long, Double> score(Long seedPageId, Long kbId) {
        WikiPageEntity seed = pageMapper.selectById(seedPageId);
        if (seed == null) return Map.of();

        Map<Long, Double> scores = new HashMap<>();

        // Outgoing links: slugs this page links to
        List<String> outgoing = parseStringList(seed.getOutgoingLinks());
        for (String slug : outgoing) {
            WikiPageEntity target = pageService.getBySlug(kbId, slug);
            if (target != null) {
                scores.put(target.getId(), weight());
            }
        }

        // Incoming links: pages whose outgoingLinks contain seed's slug
        List<WikiPageEntity> inbound = pageService.getBacklinks(kbId, seed.getSlug());
        for (WikiPageEntity p : inbound) {
            scores.merge(p.getId(), weight(), Double::sum);
        }

        scores.remove(seedPageId);
        return scores;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DirectLinkSignal] Failed to parse outgoingLinks: {}", e.getMessage());
            return List.of();
        }
    }
}
