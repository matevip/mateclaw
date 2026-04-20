package vip.mate.memory.fact.extraction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite extractor — combines pattern + LLM extractors.
 * Deduplicates by sourceRef.
 *
 * @author MateClaw Team
 */
@Component
@RequiredArgsConstructor
public class CompositeEntityExtractor implements EntityExtractor {

    private final PatternEntityExtractor patternExtractor;
    private final LlmEntityExtractor llmExtractor;

    @Override
    public List<ExtractedFact> extract(Long agentId, String filename, String content) {
        List<ExtractedFact> results = new ArrayList<>(patternExtractor.extract(agentId, filename, content));

        // Add LLM-extracted facts that don't duplicate pattern ones
        List<ExtractedFact> llmFacts = llmExtractor.extract(agentId, filename, content);
        for (ExtractedFact f : llmFacts) {
            if (results.stream().noneMatch(r -> r.sourceRef().equals(f.sourceRef()))) {
                results.add(f);
            }
        }

        return results;
    }
}
