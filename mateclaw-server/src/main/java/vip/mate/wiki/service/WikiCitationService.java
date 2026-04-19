package vip.mate.wiki.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiPageCitationEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.repository.WikiPageMapper;

import java.math.BigDecimal;
import java.util.List;

/**
 * RFC-029: Builds citation records linking pages to their source chunks.
 * Called asynchronously after page creation or update to avoid blocking
 * the main processing pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiCitationService {

    private final WikiPageMapper pageMapper;
    private final WikiChunkService chunkService;
    private final WikiPageCitationMapper citationMapper;
    private final ObjectMapper objectMapper;

    @Async
    public void buildCitationsAsync(Long pageId, Long kbId) {
        buildCitations(pageId, kbId);
    }

    /**
     * Rebuild all citation records for a page based on its sourceRawIds.
     * Soft-deletes existing citations first, then creates new ones for
     * all chunks belonging to the page's source raw materials.
     */
    public void buildCitations(Long pageId, Long kbId) {
        WikiPageEntity page = pageMapper.selectById(pageId);
        if (page == null) return;

        List<Long> rawIds = parseRawIds(page.getSourceRawIds());
        citationMapper.softDeleteByPageId(pageId);

        for (Long rawId : rawIds) {
            List<WikiChunkEntity> chunks = chunkService.listByRawId(rawId);
            for (WikiChunkEntity chunk : chunks) {
                WikiPageCitationEntity citation = new WikiPageCitationEntity();
                citation.setPageId(pageId);
                citation.setChunkId(chunk.getId());
                citation.setConfidence(BigDecimal.ONE);
                citation.setCreatedBy("system");
                citationMapper.insert(citation);
            }
        }

        log.debug("[WikiCitation] Built citations for pageId={}, kbId={}", pageId, kbId);
    }

    private List<Long> parseRawIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[WikiCitation] Failed to parse sourceRawIds: {}", e.getMessage());
            return List.of();
        }
    }
}
