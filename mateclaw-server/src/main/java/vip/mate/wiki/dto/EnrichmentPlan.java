package vip.mate.wiki.dto;

import java.util.List;

/**
 * RFC-051 PR-5b: replacement-plan output of {@code WikiLinkEnrichmentService}.
 * <p>
 * The LLM is asked to return a list of surgical wrap operations rather than
 * a full rewritten page body. Each {@link EnrichmentReplacement} describes
 * "wrap the Nth occurrence of {@code original} with {@code replacement}".
 * Java validates and applies them, guaranteeing no non-link prose changes.
 */
public record EnrichmentPlan(List<EnrichmentReplacement> replacements) {

    public EnrichmentPlan {
        if (replacements == null) replacements = List.of();
    }

    public boolean isEmpty() {
        return replacements.isEmpty();
    }
}
