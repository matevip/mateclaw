package vip.mate.wiki.job.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

/**
 * RFC-030: Final fallback strategy — uses the system default model.
 * Cheap steps (ROUTE, ENRICH, SUMMARY) prefer a lighter/cheaper model;
 * strong steps (CREATE_PAGE, MERGE_PAGE) use the default model.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class GlobalDefaultStepModelStrategy implements WikiStepModelStrategy {

    private final ModelConfigService modelConfigService;

    @Override
    public boolean supports(WikiJobStep step) { return true; }

    @Override
    public Long selectModelId(WikiProcessingJobEntity job, WikiKnowledgeBaseEntity kb, WikiJobStep step) {
        // RFC-030: cheap steps (ROUTE, ENRICH, SUMMARY) should ideally use a lighter model,
        // but ModelConfigService has no "cheapest chat model" concept yet.
        // When per-step pricing metadata is added, this switch can differentiate.
        return modelConfigService.getDefaultModel().getId();
    }
}
