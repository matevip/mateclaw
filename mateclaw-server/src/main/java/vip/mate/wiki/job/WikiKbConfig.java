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

    /**
     * RFC-051: ingest pipeline mode for this KB. {@code "lazy"} skips page
     * generation on upload (chunk + embed only); {@code "eager"} runs the
     * legacy heavy ingest pipeline. {@code null} means caller should apply
     * its own default. PR-1a only adds the field; the lazy branch lands
     * in PR-1b.
     */
    private String ingestMode;

    /**
     * RFC-051: KB-level default chat model. Used by routing as the
     * intermediate fallback between {@link #stepModels} and the system
     * default. {@code null} means the caller should fall through to the
     * system default. The frontend already writes this field; before
     * PR-1a it had no Java field to deserialize into and was silently
     * dropped.
     */
    private Long wikiDefaultModelId;

    /** Per-step model overrides: "heavy_ingest.create_page" → modelId */
    private Map<String, Long> stepModels;

    /** Global fallback model chain for all steps in this KB */
    private List<Long> fallbackModelIds;
}
