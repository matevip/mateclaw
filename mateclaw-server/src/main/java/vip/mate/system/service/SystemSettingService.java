package vip.mate.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.repository.SystemSettingMapper;

@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private static final String LANGUAGE_KEY = "language";
    private static final String STREAM_ENABLED_KEY = "streamEnabled";
    private static final String DEBUG_MODE_KEY = "debugMode";
    private static final String STATEGRAPH_ENABLED_KEY = "stateGraphEnabled";

    // 搜索服务配置 keys
    private static final String SEARCH_ENABLED_KEY = "searchEnabled";
    private static final String SEARCH_PROVIDER_KEY = "searchProvider";
    private static final String SEARCH_FALLBACK_ENABLED_KEY = "searchFallbackEnabled";
    private static final String SERPER_API_KEY_KEY = "serperApiKey";
    private static final String SERPER_BASE_URL_KEY = "serperBaseUrl";
    private static final String TAVILY_API_KEY_KEY = "tavilyApiKey";
    private static final String TAVILY_BASE_URL_KEY = "tavilyBaseUrl";
    private static final String DUCKDUCKGO_ENABLED_KEY = "duckduckgoEnabled";
    private static final String SEARXNG_BASE_URL_KEY = "searxngBaseUrl";

    private final SystemSettingMapper systemSettingMapper;

    public SystemSettingsDTO getSettings() {
        SystemSettingsDTO dto = new SystemSettingsDTO();
        dto.setLanguage(getValue(LANGUAGE_KEY, "zh-CN"));
        dto.setStreamEnabled(Boolean.parseBoolean(getValue(STREAM_ENABLED_KEY, "true")));
        dto.setDebugMode(Boolean.parseBoolean(getValue(DEBUG_MODE_KEY, "false")));
        dto.setStateGraphEnabled(Boolean.parseBoolean(getValue(STATEGRAPH_ENABLED_KEY, "false")));

        // 搜索服务配置
        dto.setSearchEnabled(Boolean.parseBoolean(getValue(SEARCH_ENABLED_KEY, "true")));
        dto.setSearchProvider(getValue(SEARCH_PROVIDER_KEY, "serper"));
        dto.setSearchFallbackEnabled(Boolean.parseBoolean(getValue(SEARCH_FALLBACK_ENABLED_KEY, "false")));
        dto.setSerperBaseUrl(getValue(SERPER_BASE_URL_KEY, "https://google.serper.dev/search"));
        dto.setTavilyBaseUrl(getValue(TAVILY_BASE_URL_KEY, "https://api.tavily.com/search"));
        // Keyless provider 配置
        dto.setDuckduckgoEnabled(Boolean.parseBoolean(getValue(DUCKDUCKGO_ENABLED_KEY, "true")));
        dto.setSearxngBaseUrl(getValue(SEARXNG_BASE_URL_KEY, ""));
        // API Key 脱敏回显
        dto.setSerperApiKeyMasked(maskApiKey(getValue(SERPER_API_KEY_KEY, "")));
        dto.setTavilyApiKeyMasked(maskApiKey(getValue(TAVILY_API_KEY_KEY, "")));
        return dto;
    }

    /**
     * 获取搜索配置（内部使用，包含明文 API Key）
     */
    public SystemSettingsDTO getSearchSettings() {
        SystemSettingsDTO dto = new SystemSettingsDTO();
        dto.setSearchEnabled(Boolean.parseBoolean(getValue(SEARCH_ENABLED_KEY, "true")));
        dto.setSearchProvider(getValue(SEARCH_PROVIDER_KEY, "serper"));
        dto.setSearchFallbackEnabled(Boolean.parseBoolean(getValue(SEARCH_FALLBACK_ENABLED_KEY, "false")));
        dto.setSerperApiKey(getValue(SERPER_API_KEY_KEY, ""));
        dto.setSerperBaseUrl(getValue(SERPER_BASE_URL_KEY, "https://google.serper.dev/search"));
        dto.setTavilyApiKey(getValue(TAVILY_API_KEY_KEY, ""));
        dto.setTavilyBaseUrl(getValue(TAVILY_BASE_URL_KEY, "https://api.tavily.com/search"));
        dto.setDuckduckgoEnabled(Boolean.parseBoolean(getValue(DUCKDUCKGO_ENABLED_KEY, "true")));
        dto.setSearxngBaseUrl(getValue(SEARXNG_BASE_URL_KEY, ""));
        return dto;
    }

    public SystemSettingsDTO saveSettings(SystemSettingsDTO dto) {
        saveValue(LANGUAGE_KEY, dto.getLanguage(), "当前界面语言");
        saveValue(STREAM_ENABLED_KEY, String.valueOf(Boolean.TRUE.equals(dto.getStreamEnabled())), "是否开启流式响应");
        saveValue(DEBUG_MODE_KEY, String.valueOf(Boolean.TRUE.equals(dto.getDebugMode())), "是否开启调试模式");
        saveValue(STATEGRAPH_ENABLED_KEY, String.valueOf(Boolean.TRUE.equals(dto.getStateGraphEnabled())), "启用 StateGraph 架构的 ReAct Agent");

        // 搜索服务配置
        if (dto.getSearchEnabled() != null) {
            saveValue(SEARCH_ENABLED_KEY, String.valueOf(dto.getSearchEnabled()), "是否启用搜索功能");
        }
        if (dto.getSearchProvider() != null) {
            saveValue(SEARCH_PROVIDER_KEY, dto.getSearchProvider(), "搜索服务提供商");
        }
        if (dto.getSearchFallbackEnabled() != null) {
            saveValue(SEARCH_FALLBACK_ENABLED_KEY, String.valueOf(dto.getSearchFallbackEnabled()), "搜索失败时是否回退到备用提供商");
        }
        // API Key 仅在非空时保存（前端不回传明文，避免覆盖为空）
        if (dto.getSerperApiKey() != null && !dto.getSerperApiKey().isBlank()) {
            saveValue(SERPER_API_KEY_KEY, dto.getSerperApiKey(), "Serper API Key");
        }
        if (dto.getSerperBaseUrl() != null) {
            saveValue(SERPER_BASE_URL_KEY, dto.getSerperBaseUrl(), "Serper 接口地址");
        }
        if (dto.getTavilyApiKey() != null && !dto.getTavilyApiKey().isBlank()) {
            saveValue(TAVILY_API_KEY_KEY, dto.getTavilyApiKey(), "Tavily API Key");
        }
        if (dto.getTavilyBaseUrl() != null) {
            saveValue(TAVILY_BASE_URL_KEY, dto.getTavilyBaseUrl(), "Tavily 接口地址");
        }
        // Keyless provider 配置
        if (dto.getDuckduckgoEnabled() != null) {
            saveValue(DUCKDUCKGO_ENABLED_KEY, String.valueOf(dto.getDuckduckgoEnabled()), "DuckDuckGo 免 Key 搜索（零配置兜底）");
        }
        if (dto.getSearxngBaseUrl() != null) {
            saveValue(SEARXNG_BASE_URL_KEY, dto.getSearxngBaseUrl(), "SearXNG 实例地址");
        }
        return getSettings();
    }

    public String getLanguage() {
        return getValue(LANGUAGE_KEY, "zh-CN");
    }

    public String saveLanguage(String language) {
        saveValue(LANGUAGE_KEY, language, "当前界面语言");
        return getLanguage();
    }

    public boolean isStateGraphEnabled() {
        return Boolean.parseBoolean(getValue(STATEGRAPH_ENABLED_KEY, "false"));
    }

    private String getValue(String key, String defaultValue) {
        SystemSettingEntity entity = systemSettingMapper.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
                .eq(SystemSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        return entity != null && entity.getSettingValue() != null ? entity.getSettingValue() : defaultValue;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    private void saveValue(String key, String value, String description) {
        SystemSettingEntity entity = systemSettingMapper.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
                .eq(SystemSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        if (entity == null) {
            entity = new SystemSettingEntity();
            entity.setSettingKey(key);
            entity.setDescription(description);
            entity.setSettingValue(value);
            systemSettingMapper.insert(entity);
            return;
        }
        entity.setSettingValue(value);
        entity.setDescription(description);
        systemSettingMapper.updateById(entity);
    }
}
