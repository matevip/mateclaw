package vip.mate.agent.chatmodel;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.chatmodel.ChatModelBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Strategy implementation for {@link ModelProtocol#DASHSCOPE_NATIVE}.
 *
 * <p>Owns all DashScope-specific construction logic (api + options) plus the
 * fallback-chain helpers for resolving API key / Base URL when the provider
 * row is incomplete. PR-0b moved this code out of {@code AgentGraphBuilder}
 * so the agent package no longer carries any DashScope schema knowledge.</p>
 *
 * <p>DashScopeChatModel is injected via ObjectProvider so that the builder
 * degrades gracefully when DashScope auto-configuration is disabled or the
 * dependency is absent, rather than failing the entire application context.</p>
 */
@Slf4j
@Component
public class AgentDashScopeChatModelBuilder implements ChatModelBuilder {

    private final ObjectProvider<DashScopeChatModel> dashScopeChatModelProvider;
    private final DashScopeConnectionProperties dashScopeConnectionProperties;
    private final ModelProviderService modelProviderService;

    public AgentDashScopeChatModelBuilder(ObjectProvider<DashScopeChatModel> dashScopeChatModelProvider,
                                          DashScopeConnectionProperties dashScopeConnectionProperties,
                                          ModelProviderService modelProviderService) {
        this.dashScopeChatModelProvider = dashScopeChatModelProvider;
        this.dashScopeConnectionProperties = dashScopeConnectionProperties;
        this.modelProviderService = modelProviderService;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.DASHSCOPE_NATIVE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        DashScopeChatModel defaultModel = dashScopeChatModelProvider.getIfAvailable();
        if (defaultModel == null) {
            throw new MateClawException("err.agent.dashscope_unavailable",
                    "DashScope 自动配置未激活（可能缺少依赖或被排除），无法构建 DashScope 模型");
        }
        DashScopeApi api = buildDashScopeApi(provider);
        DashScopeChatOptions options = buildDashScopeOptions(model, provider);
        return defaultModel.mutate()
                .dashScopeApi(api)
                .defaultOptions(options)
                .build();
    }

    /**
     * DashScope's built-in web search is on by default; only an explicit
     * {@code enableSearch=false} in provider kwargs disables it. Public so
     * {@code AgentGraphBuilder.build()} can surface the "built-in search
     * active" log once per agent.
     */
    public boolean isBuiltinSearchEnabled(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        Object kwargsSearch = kwargs.get("enableSearch");
        if (kwargsSearch != null) {
            return Boolean.TRUE.equals(kwargsSearch);
        }
        return true;
    }

    DashScopeChatOptions buildDashScopeOptions(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        DashScopeChatOptions.DashScopeChatOptionsBuilder builder = DashScopeChatOptions.builder();
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);

        if (StringUtils.hasText(runtimeModel.getModelName())) {
            builder.withModel(runtimeModel.getModelName());
        }
        if (runtimeModel.getTemperature() != null) {
            builder.withTemperature(runtimeModel.getTemperature());
        }
        if (runtimeModel.getMaxTokens() != null) {
            builder.withMaxToken(runtimeModel.getMaxTokens());
        }
        if (runtimeModel.getTopP() != null) {
            builder.withTopP(runtimeModel.getTopP());
        }
        if (isBuiltinSearchEnabled(runtimeModel, provider)) {
            builder.withEnableSearch(true);
            String strategy = runtimeModel.getSearchStrategy();
            if (!StringUtils.hasText(strategy)) {
                strategy = (String) kwargs.get("searchStrategy");
            }
            if (StringUtils.hasText(strategy)) {
                builder.withSearchOptions(DashScopeApiSpec.SearchOptions.builder()
                        .searchStrategy(strategy)
                        .enableSource(true)
                        .enableCitation(true)
                        .build());
            }
        }
        return builder.build();
    }

    DashScopeApi buildDashScopeApi(ModelProviderEntity provider) {
        DashScopeApi.Builder builder = DashScopeApi.builder();

        // API Key fallback chain: provider UI config → env / application.yml → default bean reflection
        String apiKey = provider != null ? provider.getApiKey() : null;
        if (!StringUtils.hasText(apiKey) || !modelProviderService.hasUsableApiKey(apiKey)) {
            apiKey = dashScopeConnectionProperties.getApiKey();
        }
        if (!StringUtils.hasText(apiKey) || !modelProviderService.hasUsableApiKey(apiKey)) {
            apiKey = readApiKeyFromDefaultChatModel();
        }
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.dashscope_key_missing",
                    "DashScope API Key 未配置，请在模型设置中填写 dashscope 的 API Key，或设置 DASHSCOPE_API_KEY 环境变量");
        }
        builder.apiKey(apiKey.trim());

        // Base URL fallback chain — same priority as API Key
        String baseUrl = provider != null ? provider.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = dashScopeConnectionProperties.getBaseUrl();
        }
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = readBaseUrlFromDefaultChatModel();
        }
        String normalizedBaseUrl = normalizeDashScopeBaseUrl(baseUrl);
        if (StringUtils.hasText(normalizedBaseUrl)) {
            builder.baseUrl(normalizedBaseUrl);
        }
        return builder.build();
    }

    /**
     * Strip the OpenAI compatible-mode path off any user-supplied URL
     * (common when migrating from compat-mode), trim trailing slash, and
     * return null when the result is the SDK default — letting Spring AI's
     * built-in default win avoids path-concat surprises.
     */
    private String normalizeDashScopeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String normalized = baseUrl.trim();
        int compatibleIndex = normalized.indexOf("/compatible-mode/");
        if (compatibleIndex >= 0) {
            normalized = normalized.substring(0, compatibleIndex);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if ("https://dashscope.aliyuncs.com".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    // ============================================================
    // Reflection helpers — read whatever the auto-configured default
    // DashScopeChatModel was built with, as a final fallback when no
    // explicit credentials reach us.
    // ============================================================

    private String readApiKeyFromDefaultChatModel() {
        try {
            DashScopeApi api = readDashScopeApiFromDefaultChatModel();
            if (api == null) return null;
            Field apiKeyField = DashScopeApi.class.getDeclaredField("apiKey");
            apiKeyField.setAccessible(true);
            Object apiKey = apiKeyField.get(api);
            if (apiKey instanceof org.springframework.ai.model.ApiKey key) {
                return key.getValue();
            }
        } catch (Exception e) {
            log.warn("Failed to read API key from default DashScopeChatModel: {}", e.getMessage());
        }
        return null;
    }

    private String readBaseUrlFromDefaultChatModel() {
        try {
            DashScopeApi api = readDashScopeApiFromDefaultChatModel();
            if (api == null) return null;
            Field baseUrlField = DashScopeApi.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            Object baseUrl = baseUrlField.get(api);
            return baseUrl instanceof String value ? value : null;
        } catch (Exception e) {
            log.warn("Failed to read baseUrl from default DashScopeChatModel: {}", e.getMessage());
            return null;
        }
    }

    private DashScopeApi readDashScopeApiFromDefaultChatModel() throws NoSuchFieldException, IllegalAccessException {
        DashScopeChatModel defaultModel = dashScopeChatModelProvider.getIfAvailable();
        if (defaultModel == null) {
            return null;
        }
        Field apiField = DashScopeChatModel.class.getDeclaredField("dashscopeApi");
        apiField.setAccessible(true);
        Object api = apiField.get(defaultModel);
        return api instanceof DashScopeApi dashScopeApi ? dashScopeApi : null;
    }
}
