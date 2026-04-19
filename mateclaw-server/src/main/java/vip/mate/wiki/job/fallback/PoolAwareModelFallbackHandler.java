package vip.mate.wiki.job.fallback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;

import java.util.List;
import java.util.Optional;

/**
 * RFC-030: Pool-aware fallback — walks the job's fallback chain,
 * skipping models whose provider is no longer in the AvailableProviderPool.
 */
@Slf4j
@Component
@Order(1)
public class PoolAwareModelFallbackHandler implements ModelFallbackHandler {

    @Autowired(required = false)
    private AvailableProviderPool providerPool;

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private ObjectMapper objectMapper;

    private ModelFallbackHandler next;

    @Override
    public Optional<Long> handle(WikiProcessingJobEntity job, WikiJobStep step, String errorCode) {
        List<Long> fallbackChain = parseFallbackChain(job.getFallbackChainJson());
        for (Long modelId : fallbackChain) {
            if (!modelId.equals(job.getCurrentModelId()) && isAvailable(modelId)) {
                return Optional.of(modelId);
            }
        }
        return next != null ? next.handle(job, step, errorCode) : Optional.empty();
    }

    private boolean isAvailable(Long modelId) {
        if (providerPool == null) return true;
        try {
            ModelConfigEntity model = modelConfigService.getModel(modelId);
            return providerPool.contains(model.getProvider());
        } catch (Exception e) {
            return false;
        }
    }

    private List<Long> parseFallbackChain(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void setNext(ModelFallbackHandler next) { this.next = next; }
}
