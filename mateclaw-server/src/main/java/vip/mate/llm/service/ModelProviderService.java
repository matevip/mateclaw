package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.model.*;
import vip.mate.llm.repository.ModelProviderMapper;

import org.springframework.ai.chat.model.ChatModel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModelProviderService {

    private final ModelProviderMapper modelProviderMapper;
    private final ModelConfigService modelConfigService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Plugin-registered ChatModel instances: providerId -> ChatModel */
    private final Map<String, ChatModel> pluginChatModels = new ConcurrentHashMap<>();

    /**
     * Register a ChatModel from a plugin.
     */
    public void registerPluginChatModel(String providerId, ChatModel chatModel) {
        pluginChatModels.put(providerId, chatModel);
    }

    /**
     * Unregister a plugin ChatModel.
     */
    public void unregisterPluginChatModel(String providerId) {
        pluginChatModels.remove(providerId);
    }

    /**
     * Get a plugin-registered ChatModel.
     *
     * @return the ChatModel, or null if not registered by a plugin
     */
    public ChatModel getPluginChatModel(String providerId) {
        return pluginChatModels.get(providerId);
    }

    public List<ProviderInfoDTO> listProviders() {
        List<ModelProviderEntity> providers = modelProviderMapper.selectList(new LambdaQueryWrapper<ModelProviderEntity>()
                .orderByDesc(ModelProviderEntity::getIsLocal)
                .orderByAsc(ModelProviderEntity::getIsCustom)
                .orderByAsc(ModelProviderEntity::getName));
        Map<String, List<ModelConfigEntity>> modelsByProvider = modelConfigService.listModels().stream()
                .collect(Collectors.groupingBy(ModelConfigEntity::getProvider));

        return providers.stream().map(provider -> toProviderInfo(provider, modelsByProvider.get(provider.getProviderId()))).toList();
    }

    public ProviderInfoDTO updateProviderConfig(String providerId, ProviderConfigRequest request) {
        ModelProviderEntity provider = getProvider(providerId);
        if (StringUtils.hasText(request.getApiKey())) {
            provider.setApiKey(request.getApiKey().trim());
        }
        provider.setBaseUrl(request.getBaseUrl());
        provider.setChatModel(ModelProtocol.resolveChatModel(request.getProtocol(), request.getChatModel()));
        provider.setGenerateKwargs(writeJson(request.getGenerateKwargs()));
        // RFC-009 P3.5: only update fallback priority when the caller explicitly
        // sends a value. null leaves it untouched (existing chain unchanged).
        if (request.getFallbackPriority() != null) {
            int p = Math.max(0, request.getFallbackPriority());
            provider.setFallbackPriority(p);
        }
        modelProviderMapper.updateById(provider);
        tryAutoActivateModel(providerId, provider);
        eventPublisher.publishEvent(new ModelConfigChangedEvent("provider-config-updated"));
        return toProviderInfo(provider, modelConfigService.listModelsByProvider(providerId));
    }

    public ProviderInfoDTO createCustomProvider(CreateCustomProviderRequest request) {
        if (!StringUtils.hasText(request.getId()) || !StringUtils.hasText(request.getName())) {
            throw new MateClawException("err.llm.provider_fields_required", "Provider id 和名称不能为空");
        }
        if (modelProviderMapper.selectById(request.getId()) != null) {
            throw new MateClawException("err.llm.provider_exists", "Provider 已存在: " + request.getId());
        }
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId(request.getId());
        provider.setName(request.getName());
        provider.setApiKeyPrefix(request.getApiKeyPrefix());
        provider.setChatModel(ModelProtocol.resolveChatModel(request.getProtocol(), request.getChatModel()));
        provider.setBaseUrl(request.getDefaultBaseUrl());
        provider.setGenerateKwargs("{}");
        provider.setIsCustom(true);
        provider.setIsLocal(false);
        provider.setSupportModelDiscovery(false);
        provider.setSupportConnectionCheck(false);
        provider.setFreezeUrl(false);
        provider.setRequireApiKey(true);
        modelProviderMapper.insert(provider);

        if (request.getModels() != null) {
            for (ModelInfoDTO model : request.getModels()) {
                modelConfigService.addModelToProvider(request.getId(), model.getId(), model.getName(), false);
            }
        }
        eventPublisher.publishEvent(new ModelConfigChangedEvent("provider-created"));
        return toProviderInfo(provider, modelConfigService.listModelsByProvider(request.getId()));
    }

    public void deleteCustomProvider(String providerId) {
        ModelProviderEntity provider = getProvider(providerId);
        if (!Boolean.TRUE.equals(provider.getIsCustom())) {
            throw new MateClawException("err.llm.provider_builtin_readonly", "内置 Provider 不支持删除");
        }
        modelConfigService.deleteModelsByProvider(providerId);
        modelProviderMapper.deleteById(providerId);
        eventPublisher.publishEvent(new ModelConfigChangedEvent("provider-deleted"));
    }

    public ProviderInfoDTO addModel(String providerId, AddProviderModelRequest request) {
        getProvider(providerId);
        // Defense-in-depth: the manual "Add model" form must apply the same
        // protocol-level safety as auto-discovery — otherwise users can freely
        // type an unknown model id (e.g. "qwen3.6-plus") that DashScope native
        // rejects at runtime with the opaque "[InvalidParameter] url error".
        String modelId = request.getId();
        if (modelId != null && !modelId.isBlank()) {
            ModelDiscoveryService.assertModelIdAcceptable(providerId, this.getProvider(providerId), modelId);
        }
        modelConfigService.addModelToProvider(providerId, modelId, request.getName(), false);
        return toProviderInfo(getProvider(providerId), modelConfigService.listModelsByProvider(providerId));
    }

    public ProviderInfoDTO removeModel(String providerId, String modelId) {
        getProvider(providerId);
        modelConfigService.removeModelFromProvider(providerId, modelId);
        return toProviderInfo(getProvider(providerId), modelConfigService.listModelsByProvider(providerId));
    }

    public ModelProviderEntity getProviderConfig(String providerId) {
        return getProvider(providerId);
    }

    /**
     * RFC-009: ordered list of providers that participate in the multi-model
     * failover chain. Filters by {@code fallback_priority > 0} and sorts
     * ascending, so priority 1 is tried first after the primary model
     * exhausts retries. An empty list disables fallover entirely.
     */
    public List<ModelProviderEntity> listFallbackChain() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ModelProviderEntity> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.gt("fallback_priority", 0);
        qw.orderByAsc("fallback_priority");
        return modelProviderMapper.selectList(qw);
    }

    public boolean isProviderConfigured(String providerId) {
        return isProviderConfigured(getProvider(providerId));
    }

    public boolean isProviderAvailable(String providerId) {
        ModelProviderEntity provider = getProvider(providerId);
        return isProviderConfigured(provider) && hasModels(providerId);
    }

    public String getProviderUnavailableReason(String providerId) {
        ModelProviderEntity provider = getProvider(providerId);
        if (!isProviderConfigured(provider)) {
            if (Boolean.TRUE.equals(provider.getRequireApiKey())) {
                return "Provider 未配置有效的 API Key";
            }
            if (Boolean.TRUE.equals(provider.getIsCustom()) || !Boolean.TRUE.equals(provider.getIsLocal())) {
                return "Provider 未配置 Base URL";
            }
            return "Provider 未完成配置";
        }
        if (!hasModels(providerId)) {
            return "Provider 下没有可用模型";
        }
        return null;
    }

    private void tryAutoActivateModel(String providerId, ModelProviderEntity provider) {
        if (!isProviderConfigured(provider)) {
            return;
        }
        List<ModelConfigEntity> providerModels = modelConfigService.listModelsByProvider(providerId);
        if (providerModels.isEmpty()) {
            return;
        }
        boolean shouldAutoActivate = false;
        try {
            ModelConfigEntity currentDefault = modelConfigService.getDefaultModel();
            ModelProviderEntity defaultProvider = modelProviderMapper.selectById(currentDefault.getProvider());
            if (!isProviderConfigured(defaultProvider)) {
                shouldAutoActivate = true;
            }
        } catch (MateClawException e) {
            shouldAutoActivate = true;
        }
        if (shouldAutoActivate) {
            ModelConfigEntity firstModel = providerModels.get(0);
            modelConfigService.setDefaultModel(providerId, firstModel.getModelName());
        }
    }

    private ModelProviderEntity getProvider(String providerId) {
        ModelProviderEntity provider = modelProviderMapper.selectById(providerId);
        if (provider == null) {
            throw new MateClawException("err.llm.provider_not_found", "Provider 不存在: " + providerId);
        }
        return provider;
    }

    private ProviderInfoDTO toProviderInfo(ModelProviderEntity provider, List<ModelConfigEntity> models) {
        ProviderInfoDTO dto = new ProviderInfoDTO();
        dto.setId(provider.getProviderId());
        dto.setName(provider.getName());
        dto.setProtocol(ModelProtocol.fromChatModel(provider.getChatModel()).getId());
        dto.setApiKeyPrefix(provider.getApiKeyPrefix());
        dto.setChatModel(provider.getChatModel());
        dto.setIsCustom(Boolean.TRUE.equals(provider.getIsCustom()));
        dto.setIsLocal(Boolean.TRUE.equals(provider.getIsLocal()));
        dto.setSupportModelDiscovery(Boolean.TRUE.equals(provider.getSupportModelDiscovery()));
        dto.setSupportConnectionCheck(Boolean.TRUE.equals(provider.getSupportConnectionCheck()));
        dto.setFreezeUrl(Boolean.TRUE.equals(provider.getFreezeUrl()));
        dto.setRequireApiKey(Boolean.TRUE.equals(provider.getRequireApiKey()));
        boolean configured = isProviderConfigured(provider);
        boolean available = configured && models != null && !models.isEmpty();
        dto.setConfigured(configured);
        dto.setAvailable(available);
        dto.setApiKey(maskApiKey(provider.getApiKey()));
        dto.setBaseUrl(provider.getBaseUrl());
        dto.setGenerateKwargs(readJson(provider.getGenerateKwargs()));
        dto.setAuthType(provider.getAuthType() != null ? provider.getAuthType() : "api_key");
        dto.setOauthConnected(StringUtils.hasText(provider.getOauthAccessToken()));
        dto.setOauthExpiresAt(provider.getOauthExpiresAt());
        dto.setFallbackPriority(provider.getFallbackPriority() != null ? provider.getFallbackPriority() : 0);
        List<ModelInfoDTO> builtinModels = new ArrayList<>();
        List<ModelInfoDTO> extraModels = new ArrayList<>();
        if (models != null) {
            for (ModelConfigEntity model : models) {
                // RFC-049 PR-1-UI: ModelInfoDTO(id, name) derives supportsReasoningEffort
                // from id via ModelFamily — no extra wiring needed here.
                ModelInfoDTO info = new ModelInfoDTO(model.getModelName(), model.getName());
                if (Boolean.TRUE.equals(model.getBuiltin())) {
                    builtinModels.add(info);
                } else {
                    extraModels.add(info);
                }
            }
        }
        dto.setModels(builtinModels);
        dto.setExtraModels(extraModels);
        return dto;
    }

    private boolean hasModels(String providerId) {
        return !modelConfigService.listModelsByProvider(providerId).isEmpty();
    }

    private boolean isProviderConfigured(ModelProviderEntity provider) {
        if (provider == null) {
            return false;
        }
        if (Boolean.TRUE.equals(provider.getIsLocal())) {
            return true;
        }

        // OAuth 认证的 provider：检查 OAuth token 是否存在
        if ("oauth".equals(provider.getAuthType())) {
            return StringUtils.hasText(provider.getOauthAccessToken());
        }

        boolean hasBaseUrl = StringUtils.hasText(provider.getBaseUrl());
        boolean hasApiKey = hasUsableApiKey(provider.getApiKey());

        if (Boolean.TRUE.equals(provider.getIsCustom())) {
            return hasBaseUrl && (!Boolean.TRUE.equals(provider.getRequireApiKey()) || hasApiKey);
        }
        if (Boolean.FALSE.equals(provider.getRequireApiKey())) {
            return hasBaseUrl;
        }
        return hasApiKey;
    }

    public boolean hasUsableApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return false;
        }
        String normalized = apiKey.trim();
        return !normalized.contains("*")
                && !"your-dashscope-api-key-here".equalsIgnoreCase(normalized)
                && !"your-api-key-here".equalsIgnoreCase(normalized);
    }

    public Map<String, Object> readProviderGenerateKwargs(ModelProviderEntity provider) {
        return readJson(provider != null ? provider.getGenerateKwargs() : null);
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "********";
        }
        return apiKey.substring(0, 4) + "********" + apiKey.substring(apiKey.length() - 4);
    }

    private Map<String, Object> readJson(String value) {
        if (!StringUtils.hasText(value)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
