package vip.mate.wiki.job.template;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.job.*;
import vip.mate.wiki.job.event.WikiJobCreatedEvent;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.service.WikiProcessingService;

/**
 * RFC-031: Heavy ingest template — delegates to the legacy processing
 * service for the two-phase digest pipeline, then optionally dispatches
 * a light enrichment job.
 */
@Component
public class HeavyIngestTemplate extends WikiProcessingTemplate {

    private final WikiProcessingService legacyProcessingService;

    public HeavyIngestTemplate(WikiModelRoutingService routingService,
                                WikiProcessingJobService jobService,
                                ApplicationEventPublisher eventPublisher,
                                WikiProperties wikiProperties,
                                WikiProcessingService legacyProcessingService) {
        super(routingService, jobService, eventPublisher, wikiProperties);
        this.legacyProcessingService = legacyProcessingService;
    }

    @Override
    protected WikiJobStep routingStep() { return WikiJobStep.CREATE_PAGE; }

    @Override
    protected WikiJobStage mainStage() { return WikiJobStage.PHASE_A_RUNNING; }

    @Override
    protected void doProcess(WikiProcessingJobEntity job, Long modelId) {
        // Transition period: delegate to existing processing pipeline as a whole.
        // TODO: RFC-031 future — split into processInChunksForJob(job, modelId),
        //  processChunkTwoPhaseForJob(job, modelId), scheduleEmbeddingAsync(rawId)
        //  for per-stage job transitions and per-step model routing.
        legacyProcessingService.processRawMaterial(job.getRawId());
    }

    @Override
    protected void onSuccess(WikiProcessingJobEntity job) {
        if (wikiProperties.isLightEnrichEnabled()) {
            WikiProcessingJobEntity enrichJob =
                jobService.createLightEnrich(job.getKbId(), job.getRawId());
            eventPublisher.publishEvent(new WikiJobCreatedEvent(enrichJob.getId()));
        }
    }
}
