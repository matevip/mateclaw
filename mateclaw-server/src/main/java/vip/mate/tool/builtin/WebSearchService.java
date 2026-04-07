package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.tool.search.SearchProvider;
import vip.mate.tool.search.SearchProviderRegistry;
import vip.mate.tool.search.SearchProviderRegistry.ResolvedProvider;
import vip.mate.tool.search.SearchResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索服务：通过 {@link SearchProviderRegistry} 实现 provider chain 路由与 keyless fallback
 *
 * <p>调度策略：
 * <ol>
 *   <li>用户配置的 primary provider（有 key）</li>
 *   <li>自动探测其他有 key 的 provider</li>
 *   <li>Keyless fallback（DuckDuckGo / SearXNG）</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchService {

    private final SystemSettingService systemSettingService;
    private final SearchProviderRegistry providerRegistry;

    /**
     * 执行搜索，根据系统设置动态选择 provider（含 keyless fallback）
     */
    public String search(String query) {
        SystemSettingsDTO config = systemSettingService.getSearchSettings();

        if (!Boolean.TRUE.equals(config.getSearchEnabled())) {
            return "搜索功能已关闭，请在系统设置中启用。";
        }

        // 通过 provider registry 解析最佳 provider
        ResolvedProvider resolved = providerRegistry.resolve(config);
        log.info("搜索 provider 解析: {}", resolved != null
                ? resolved.provider().id() + " (source=" + resolved.source() + ")"
                : "无可用 provider");

        if (resolved != null) {
            String result = tryProvider(resolved.provider(), query, config);
            if (result != null) {
                log.info("搜索成功 (provider={}, source={})", resolved.provider().id(), resolved.source());
                return result;
            }
        }

        // 首选 provider 失败或不可用，遍历 fallback chain
        if (Boolean.TRUE.equals(config.getSearchFallbackEnabled()) || resolved == null) {
            for (SearchProvider p : providerRegistry.allSorted()) {
                if (resolved != null && p.id().equals(resolved.provider().id())) continue;
                if (!p.isAvailable(config)) continue;

                String result = tryProvider(p, query, config);
                if (result != null) {
                    log.info("搜索 fallback 成功 (provider={})", p.id());
                    return result;
                }
            }
        }

        return "搜索暂时不可用。建议在系统设置中配置 Serper 或 Tavily API Key 以获得更好的搜索体验。";
    }

    private String tryProvider(SearchProvider provider, String query, SystemSettingsDTO config) {
        try {
            List<SearchResult> results = provider.search(query, config);
            if (results == null || results.isEmpty()) {
                log.debug("Provider {} 返回空结果", provider.id());
                return null;
            }
            return formatResults(results, provider.id());
        } catch (Exception e) {
            log.warn("Provider {} 搜索失败: {}", provider.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 将结构化搜索结果格式化为 Markdown（供 LLM 消费）
     */
    private String formatResults(List<SearchResult> results, String providerId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search results (via ").append(providerId).append("):\n\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i).toMarkdown()).append("\n");
        }
        return sb.toString();
    }
}
