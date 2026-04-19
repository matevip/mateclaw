package vip.mate.wiki.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.job.fallback.ModelFallbackHandler;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.job.strategy.WikiStepModelStrategy;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RFC-030: Model routing service — selects the right model for each
 * wiki processing step, with fallback chain support.
 */
@Slf4j
@Service
public class WikiModelRoutingService {

    private final List<WikiStepModelStrategy> strategies;
    private final ModelFallbackHandler fallbackChainHead;
    private final ProviderChatModelFactory chatModelFactory;
    private final ModelConfigService modelConfigService;
    private final WikiKnowledgeBaseService kbService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AvailableProviderPool providerPool;

    private static final RetryTemplate WIKI_NO_RETRY = RetryTemplate.builder()
            .maxAttempts(1).build();

    public WikiModelRoutingService(List<WikiStepModelStrategy> strategies,
                                    List<ModelFallbackHandler> fallbackHandlers,
                                    ProviderChatModelFactory chatModelFactory,
                                    ModelConfigService modelConfigService,
                                    WikiKnowledgeBaseService kbService,
                                    ObjectMapper objectMapper) {
        this.strategies = strategies;
        this.chatModelFactory = chatModelFactory;
        this.modelConfigService = modelConfigService;
        this.kbService = kbService;
        this.objectMapper = objectMapper;
        this.fallbackChainHead = buildChain(fallbackHandlers);
    }

    /**
     * Select the best model for a given job and step, respecting strategy
     * priority and provider pool availability.
     */
    public Long selectModelId(WikiProcessingJobEntity job, WikiJobStep step) {
        WikiKnowledgeBaseEntity kb = (job != null) ? kbService.getById(job.getKbId()) : null;
        for (WikiStepModelStrategy strategy : strategies) {
            if (strategy.supports(step)) {
                Long modelId = strategy.selectModelId(job, kb, step);
                if (modelId != null && isAvailable(modelId)) return modelId;
            }
        }
        throw new WikiModelUnavailableException("No available model for step: " + step);
    }

    /**
     * Select a fallback model after a failure.
     */
    public Long selectFallbackModel(WikiProcessingJobEntity job, WikiJobStep step, String errorCode) {
        return fallbackChainHead.handle(job, step, errorCode)
            .orElseThrow(() -> new WikiModelUnavailableException(
                "Exhausted all fallback models for step: " + step));
    }

    /**
     * Build a ChatModel instance for a given model ID.
     */
    public ChatModel buildChatModel(Long modelId) {
        ModelConfigEntity model = modelConfigService.getModel(modelId);
        return chatModelFactory.buildFor(model, WIKI_NO_RETRY);
    }

    /**
     * Build the fallback chain JSON for a job (called once during routing stage).
     */
    public String buildFallbackChainJson(Long kbId) {
        WikiKbConfig config = kbConfigOf(kbId);
        List<Long> chain;
        if (config != null && config.getFallbackModelIds() != null && !config.getFallbackModelIds().isEmpty()) {
            chain = config.getFallbackModelIds();
        } else {
            chain = List.of(modelConfigService.getDefaultModel().getId());
        }
        if (providerPool != null) {
            chain = chain.stream().filter(this::isAvailable).collect(Collectors.toList());
        }
        try {
            return objectMapper.writeValueAsString(chain);
        } catch (Exception e) {
            return "[]";
        }
    }

    private boolean isAvailable(Long modelId) {
        if (providerPool == null) return true;
        try {
            ModelConfigEntity m = modelConfigService.getModel(modelId);
            return providerPool.contains(m.getProvider());
        } catch (Exception e) {
            return false;
        }
    }

    private WikiKbConfig kbConfigOf(Long kbId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null || kb.getConfigContent() == null) return null;
        try {
            return objectMapper.readValue(kb.getConfigContent(), WikiKbConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static ModelFallbackHandler buildChain(List<ModelFallbackHandler> handlers) {
        if (handlers.isEmpty()) throw new IllegalStateException("No ModelFallbackHandler registered");
        for (int i = 0; i < handlers.size() - 1; i++) {
            handlers.get(i).setNext(handlers.get(i + 1));
        }
        return handlers.get(0);
    }
}
