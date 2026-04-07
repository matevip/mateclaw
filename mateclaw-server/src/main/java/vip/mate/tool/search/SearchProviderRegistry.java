package vip.mate.tool.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索提供商注册表 — 收集所有 {@link SearchProvider} 实现，提供优先级排序与自动探测
 *
 * <p>借鉴 openclaw 的 provider auto-detect 机制：
 * <ol>
 *   <li>用户显式配置的 primary provider → 直接使用</li>
 *   <li>按 autoDetectOrder 遍历，优先选有 credential 的 provider</li>
 *   <li>如果没有有 credential 的 provider，回退到第一个可用的 keyless provider</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class SearchProviderRegistry {

    private final List<SearchProvider> sortedProviders;
    private final Map<String, SearchProvider> providerMap;

    public SearchProviderRegistry(List<SearchProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(SearchProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(SearchProvider::id, Function.identity()));
        log.info("注册搜索提供商 {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    /** 按 ID 获取指定 provider */
    public SearchProvider getById(String id) {
        return providerMap.get(id);
    }

    /** 获取按 autoDetectOrder 排序的全部 provider 列表 */
    public List<SearchProvider> allSorted() {
        return sortedProviders;
    }

    /**
     * 根据当前配置，解析应使用的 provider。
     *
     * <p>解析策略（借鉴 openclaw resolveWebSearchProviderId）：
     * <ol>
     *   <li>如果用户配置了 primary provider 且该 provider 可用 → 选中</li>
     *   <li>否则按 autoDetectOrder 遍历，跳过 keyless，先找有 credential 的</li>
     *   <li>如果没找到 → 回退到第一个可用的 keyless provider</li>
     * </ol>
     *
     * @return 选中的 provider，或 null（完全无可用 provider）
     */
    public ResolvedProvider resolve(SystemSettingsDTO config) {
        // 1. 用户显式配置的 primary provider
        String configuredId = config.getSearchProvider();
        if (configuredId != null && !configuredId.isBlank()) {
            SearchProvider configured = providerMap.get(configuredId);
            if (configured != null && configured.isAvailable(config)) {
                return new ResolvedProvider(configured, "configured");
            }
        }

        // 2. 按优先级遍历，先找有 credential 的
        SearchProvider keylessFallback = null;
        for (SearchProvider p : sortedProviders) {
            if (!p.requiresCredential()) {
                // 记住第一个可用的 keyless provider
                if (keylessFallback == null && p.isAvailable(config)) {
                    keylessFallback = p;
                }
                continue;
            }
            if (p.isAvailable(config)) {
                return new ResolvedProvider(p, "auto-detect");
            }
        }

        // 3. 回退到 keyless
        if (keylessFallback != null) {
            return new ResolvedProvider(keylessFallback, "keyless-fallback");
        }

        return null;
    }

    /**
     * 解析结果
     */
    public record ResolvedProvider(SearchProvider provider, String source) {
    }
}
