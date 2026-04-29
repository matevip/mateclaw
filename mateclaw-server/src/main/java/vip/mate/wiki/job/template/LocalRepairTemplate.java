package vip.mate.wiki.job.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.job.*;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.service.WikiCitationService;
import vip.mate.wiki.service.WikiProcessingService;

import java.util.Map;

/**
 * RFC-031: Local repair template — regenerates a single target page
 * without affecting other pages derived from the same raw material.
 */
@Slf4j
@Component
public class LocalRepairTemplate extends WikiProcessingTemplate {

    private final WikiProcessingService legacyProcessingService;
    private final WikiCitationService citationService;
    private final ObjectMapper objectMapper;

    public LocalRepairTemplate(WikiModelRoutingService routingService,
                                WikiProcessingJobService jobService,
                                ApplicationEventPublisher eventPublisher,
                                WikiProperties wikiProperties,
                                WikiProcessingService legacyProcessingService,
                                WikiCitationService citationService,
                                ObjectMapper objectMapper) {
        super(routingService, jobService, eventPublisher, wikiProperties);
        this.legacyProcessingService = legacyProcessingService;
        this.citationService = citationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected WikiJobStep routingStep() { return WikiJobStep.CREATE_PAGE; }

    @Override
    protected WikiJobStage mainStage() { return WikiJobStage.PHASE_B_RUNNING; }

    @Override
    protected void doProcess(WikiProcessingJobEntity job, Long modelId) {
        Map<String, Object> meta = parseMetaJson(job.getMetaJson());
        Object pageIdObj = meta.get("targetPageId");
        if (pageIdObj == null) {
            throw new IllegalArgumentException("targetPageId missing from job metaJson, jobId=" + job.getId());
        }
        Long targetPageId = Long.valueOf(pageIdObj.toString());

        legacyProcessingService.repairSinglePage(targetPageId, modelId);
        citationService.buildCitations(targetPageId, job.getKbId());
    }

    private Map<String, Object> parseMetaJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[LocalRepair] Failed to parse metaJson: {}", e.getMessage());
            return Map.of();
        }
    }
}
