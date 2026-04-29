package vip.mate.wiki.job.fallback;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;

import java.util.Optional;

/**
 * RFC-030: Final fallback — returns the system default model.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class GlobalFallbackModelHandler implements ModelFallbackHandler {

    private final ModelConfigService modelConfigService;

    private ModelFallbackHandler next;

    @Override
    public Optional<Long> handle(WikiProcessingJobEntity job, WikiJobStep step, String errorCode) {
        try {
            return Optional.of(modelConfigService.getDefaultModel().getId());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void setNext(ModelFallbackHandler next) { this.next = next; }
}
