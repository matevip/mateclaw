package vip.mate.wiki.relation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.wiki.dto.PageCitationWithRaw;
import vip.mate.wiki.repository.WikiPageCitationMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RFC-029: Shared-chunk signal — pages that share the same source chunk
 * are strongly related (weight = 5.0).
 */
@Component
@RequiredArgsConstructor
public class SharedChunkSignal implements RelationSignalStrategy {

    private final WikiPageCitationMapper citationMapper;

    @Override
    public String signalName() { return "shared_chunk"; }

    @Override
    public double weight() { return 5.0; }

    @Override
    public Map<Long, Double> score(Long seedPageId, Long kbId) {
        List<Long> seedChunkIds = citationMapper.listWithRawByPageId(seedPageId)
            .stream().map(PageCitationWithRaw::chunkId).distinct().toList();
        if (seedChunkIds.isEmpty()) return Map.of();

        Map<Long, Long> pageCount = new HashMap<>();
        citationMapper.listByChunkIds(seedChunkIds).stream()
            .filter(ref -> !ref.pageId().equals(seedPageId))
            .forEach(ref -> pageCount.merge(ref.pageId(), 1L, Long::sum));

        return pageCount.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                                      e -> e.getValue() * weight()));
    }
}
