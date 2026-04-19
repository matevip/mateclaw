package vip.mate.wiki.job;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * RFC-030: Per-KB processing configuration POJO, deserialized from
 * {@link vip.mate.wiki.model.WikiKnowledgeBaseEntity#getConfigContent()}.
 */
@Data
public class WikiKbConfig {

    /** Per-step model overrides: "heavy_ingest.create_page" → modelId */
    private Map<String, Long> stepModels;

    /** Global fallback model chain for all steps in this KB */
    private List<Long> fallbackModelIds;
}
