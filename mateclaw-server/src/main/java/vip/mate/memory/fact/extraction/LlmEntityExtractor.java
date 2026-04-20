package vip.mate.memory.fact.extraction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.MemoryProperties;

import java.util.List;

/**
 * LLM-based fact extractor — uses the LLM to identify entities and relationships.
 * Only active when fact.llm-extraction-enabled=true; otherwise returns empty.
 * <p>
 * Phase 3 L1: placeholder that delegates to pattern extractor.
 * Full LLM implementation in Phase 3 L2+.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmEntityExtractor implements EntityExtractor {

    private final MemoryProperties properties;

    @Override
    public List<ExtractedFact> extract(Long agentId, String filename, String content) {
        if (!properties.getFact().isLlmExtractionEnabled()) {
            return List.of(); // LLM extraction disabled
        }
        // TODO: Phase 3 L2+ — call LLM with entity extraction prompt
        log.debug("[FactExtract] LLM extraction not yet implemented, returning empty for agent={}", agentId);
        return List.of();
    }
}
