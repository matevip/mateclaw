package vip.mate.wiki.relation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.repository.WikiPageMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-029: Shared-raw signal — pages derived from the same raw material
 * are related (weight = 3.0).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SharedRawSignal implements RelationSignalStrategy {

    private final WikiPageCitationMapper citationMapper;
    private final WikiPageMapper pageMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String signalName() { return "shared_raw"; }

    @Override
    public double weight() { return 3.0; }

    @Override
    public Map<Long, Double> score(Long seedPageId, Long kbId) {
        WikiPageEntity seed = pageMapper.selectById(seedPageId);
        if (seed == null) return Map.of();

        List<Long> rawIds = parseRawIds(seed.getSourceRawIds());
        if (rawIds.isEmpty()) return Map.of();

        Map<Long, Double> scores = new HashMap<>();
        for (Long rawId : rawIds) {
            citationMapper.listPageIdsByRawId(rawId).stream()
                .filter(pid -> !pid.equals(seedPageId))
                .forEach(pid -> scores.merge(pid, weight(), Double::sum));
        }
        return scores;
    }

    private List<Long> parseRawIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[SharedRawSignal] Failed to parse sourceRawIds: {}", e.getMessage());
            return List.of();
        }
    }
}
