package vip.mate.wiki.job.fallback;

import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;

import java.util.Optional;

/**
 * RFC-030: Chain of responsibility for model fallback selection.
 */
public interface ModelFallbackHandler {

    Optional<Long> handle(WikiProcessingJobEntity job, WikiJobStep step, String errorCode);

    void setNext(ModelFallbackHandler next);
}
