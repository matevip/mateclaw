package vip.mate.agent.chatmodel;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.agent.ThinkingLevelHolder;
import vip.mate.exception.MateClawException;
import vip.mate.llm.cache.AnthropicCacheOptionsFactory;
import vip.mate.llm.chatmodel.ChatModelBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Strategy implementation for {@link ModelProtocol#ANTHROPIC_MESSAGES}.
 *
 * <p>Owns the full Anthropic construction logic — API client + chat options
 * including the extended-thinking budget mapping (low/medium/high/max →
 * 4k/8k/16k/32k thinking tokens) and prompt-cache options. PR-0b moved this
 * out of {@code AgentGraphBuilder}.</p>
 */
@Slf4j
@Component
public class AgentAnthropicChatModelBuilder implements ChatModelBuilder {

    private final ModelProviderService modelProviderService;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
    private final AnthropicCacheOptionsFactory anthropicCacheOptionsFactory;

    public AgentAnthropicChatModelBuilder(
            ModelProviderService modelProviderService,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            AnthropicCacheOptionsFactory anthropicCacheOptionsFactory) {
        this.modelProviderService = modelProviderService;
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.observationRegistryProvider = observationRegistryProvider;
        this.anthropicCacheOptionsFactory = anthropicCacheOptionsFactory;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.ANTHROPIC_MESSAGES;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        AnthropicApi api = buildAnthropicApi(provider);
        AnthropicChatOptions options = buildAnthropicOptions(model);
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .retryTemplate(retry)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    AnthropicApi buildAnthropicApi(ModelProviderEntity provider) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.anthropic_not_configured",
                    "Anthropic Provider 未完成配置，请在模型设置中填写有效的 API Key 和 Base URL");
        }
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.anthropic_key_invalid",
                    "Anthropic API Key 未配置或无效: " + provider.getProviderId());
        }
        String baseUrl = provider.getBaseUrl();
        RestClient.Builder restClientBuilder = applyHttpTimeouts(
                restClientBuilderProvider.getIfAvailable(RestClient::builder));
        WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder);

        AnthropicApi.Builder builder = AnthropicApi.builder()
                .apiKey(apiKey.trim())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }

    AnthropicChatOptions buildAnthropicOptions(ModelConfigEntity runtimeModel) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        if (StringUtils.hasText(runtimeModel.getModelName())) {
            builder.model(runtimeModel.getModelName());
        }

        // Extended thinking — request-level depth from ThinkingLevelHolder
        String thinkingLevel = ThinkingLevelHolder.get();
        boolean thinkingEnabled = thinkingLevel != null && !"off".equalsIgnoreCase(thinkingLevel);

        if (thinkingEnabled) {
            // Anthropic thinking mode constraints: temperature MUST be 1, top_p forbidden,
            // max_tokens must accommodate budget_tokens + a buffer for the actual answer.
            int budgetTokens = switch (thinkingLevel.toLowerCase()) {
                case "low" -> 4096;
                case "medium" -> 8192;
                case "high" -> 16384;
                case "max" -> 32768;
                default -> 16384;
            };
            builder.thinking(AnthropicApi.ThinkingType.ENABLED, budgetTokens);
            builder.maxTokens(Math.max(budgetTokens + 4096,
                    runtimeModel.getMaxTokens() != null ? runtimeModel.getMaxTokens() : 8192));
            builder.temperature(1.0);
        } else {
            // Non-thinking: Anthropic refuses to accept temperature AND top_p simultaneously.
            if (runtimeModel.getTemperature() != null) {
                builder.temperature(runtimeModel.getTemperature());
            } else if (runtimeModel.getTopP() != null) {
                builder.topP(runtimeModel.getTopP());
            }
            // RFC-025: Anthropic rejects non-positive maxTokens — clamp here so a bad config
            // surfaces as a logged warning instead of an opaque API 400 mid-conversation.
            Integer configuredMax = runtimeModel.getMaxTokens();
            if (configuredMax != null && configuredMax > 0) {
                builder.maxTokens(configuredMax);
            } else {
                if (configuredMax != null) {
                    log.warn("Ignoring non-positive Anthropic maxTokens={} for model {}; falling back to 4096",
                            configuredMax, runtimeModel.getModelName());
                }
                builder.maxTokens(4096);
            }
        }
        // RFC-014: prompt cache (system / tools / conversation history) — Spring AI 1.1.4+ first-class.
        builder.cacheOptions(anthropicCacheOptionsFactory.build());

        return builder.internalToolExecutionEnabled(false).build();
    }

    /**
     * Apply 10s connect / 180s read timeouts. The 180s read covers the case
     * where nginx caps the gateway at 60s but a real long thinking response
     * needs more — the upper retry layer takes over once we time out.
     */
    private RestClient.Builder applyHttpTimeouts(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofSeconds(180));
        return builder.requestFactory(rf);
    }
}
