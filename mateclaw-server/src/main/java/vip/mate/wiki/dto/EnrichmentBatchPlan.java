package vip.mate.wiki.dto;

import java.util.Map;

/**
 * RFC-051 follow-up: multi-page enrich response.
 * <p>
 * Maps page slug → {@link EnrichmentPlan}. Slugs missing from the map are
 * treated as "LLM proposed nothing for that page" and skipped silently.
 * Each plan still goes through the full {@code WikiEnrichmentApplier}
 * round-trip validation, so a malformed plan for one page can't corrupt the
 * others — that page is just rejected and its peers move on.
 */
public record EnrichmentBatchPlan(Map<String, EnrichmentPlan> plans) {

    public EnrichmentBatchPlan {
        if (plans == null) plans = Map.of();
    }

    public boolean isEmpty() {
        return plans.isEmpty();
    }
}
