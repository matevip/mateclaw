package vip.mate.wiki.job.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiKbConfig;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

import java.util.Map;

/**
 * RFC-030: Highest-priority strategy — uses per-KB step model overrides
 * from the KB's configContent JSON.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class KbConfigStepModelStrategy implements WikiStepModelStrategy {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(WikiJobStep step) { return true; }

    @Override
    public Long selectModelId(WikiProcessingJobEntity job, WikiKnowledgeBaseEntity kb, WikiJobStep step) {
        if (kb == null || kb.getConfigContent() == null) return null;
        try {
            WikiKbConfig config = objectMapper.readValue(kb.getConfigContent(), WikiKbConfig.class);
            Map<String, Long> stepModels = config.getStepModels();
            if (stepModels == null) return null;
            String key = job.getJobType() + "." + step.name().toLowerCase();
            return stepModels.get(key);
        } catch (Exception e) {
            log.debug("[KbConfigStrategy] Failed to parse KB config: {}", e.getMessage());
            return null;
        }
    }
}
