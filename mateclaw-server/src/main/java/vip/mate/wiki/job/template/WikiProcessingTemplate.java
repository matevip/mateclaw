package vip.mate.wiki.job.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.job.*;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;

/**
 * RFC-031: Template method base class for all wiki processing job types.
 * The {@link #execute} skeleton handles routing, error classification,
 * and fallback; subclasses implement the actual processing in {@link #doProcess}.
 */
@Slf4j
public abstract class WikiProcessingTemplate {

    protected final WikiModelRoutingService routingService;
    protected final WikiProcessingJobService jobService;
    protected final ApplicationEventPublisher eventPublisher;
    protected final WikiProperties wikiProperties;

    protected WikiProcessingTemplate(WikiModelRoutingService routingService,
                                      WikiProcessingJobService jobService,
                                      ApplicationEventPublisher eventPublisher,
                                      WikiProperties wikiProperties) {
        this.routingService = routingService;
        this.jobService = jobService;
        this.eventPublisher = eventPublisher;
        this.wikiProperties = wikiProperties;
    }

    /**
     * Non-overridable skeleton: route → process → handle errors.
     */
    public final void execute(WikiProcessingJobEntity job) {
        try {
            job = jobService.transition(job.getId(), WikiJobStage.ROUTING);
            Long modelId = routingService.selectModelId(job, routingStep());
            job.setCurrentModelId(modelId);

            job = jobService.transition(job.getId(), mainStage());
            try {
                doProcess(job, modelId);
                jobService.transition(job.getId(), WikiJobStage.COMPLETED);
                onSuccess(job);
            } catch (WikiHardModelException e) {
                handleHardError(job, e);
            } catch (WikiSoftModelException e) {
                handleSoftError(job, e);
            } catch (Exception e) {
                jobService.recordSoftError(job.getId(), "UNKNOWN", e.getMessage());
            }
        } catch (WikiModelUnavailableException e) {
            log.error("[WikiTemplate] No model available for job {}: {}", job.getId(), e.getMessage());
            jobService.recordHardError(job.getId(), "MODEL_NOT_FOUND", e.getMessage());
        }
    }

    /** The logical step used for model selection during routing. */
    protected abstract WikiJobStep routingStep();

    /** The primary stage to transition to before processing starts. */
    protected abstract WikiJobStage mainStage();

    /** Subclass implements the actual processing logic. */
    protected abstract void doProcess(WikiProcessingJobEntity job, Long modelId);

    /** Hook called after successful completion (optional override). */
    protected void onSuccess(WikiProcessingJobEntity job) {}

    private void handleHardError(WikiProcessingJobEntity job, WikiHardModelException e) {
        try {
            Long fallbackModelId = routingService.selectFallbackModel(
                job, routingStep(), e.getErrorCode());
            job.setCurrentModelId(fallbackModelId);
            doProcess(job, fallbackModelId);
            jobService.transition(job.getId(), WikiJobStage.COMPLETED);
            onSuccess(job);
        } catch (Exception fallbackEx) {
            jobService.recordHardError(job.getId(), e.getErrorCode(), e.getMessage());
        }
    }

    private void handleSoftError(WikiProcessingJobEntity job, WikiSoftModelException e) {
        jobService.recordSoftError(job.getId(), e.getErrorCode(), e.getMessage());
    }
}
