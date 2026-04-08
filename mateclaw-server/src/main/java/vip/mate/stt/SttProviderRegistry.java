package vip.mate.stt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * STT 提供商注册表
 */
@Slf4j
@Component
public class SttProviderRegistry {

    private final List<SttProvider> sortedProviders;
    private final Map<String, SttProvider> providerMap;

    public SttProviderRegistry(List<SttProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(SttProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(SttProvider::id, Function.identity()));
        log.info("注册 STT 提供商 {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    public SttProvider resolve(SystemSettingsDTO config) {
        String configuredId = config.getSttProvider();
        if (configuredId != null && !configuredId.isBlank() && !"auto".equals(configuredId)) {
            SttProvider p = providerMap.get(configuredId);
            if (p != null && p.isAvailable(config)) return p;
        }
        for (SttProvider p : sortedProviders) {
            if (p.isAvailable(config)) return p;
        }
        return null;
    }

    public List<SttProvider> fallbackCandidates(SystemSettingsDTO config, String excludeId) {
        return sortedProviders.stream()
                .filter(p -> !p.id().equals(excludeId))
                .filter(p -> p.isAvailable(config))
                .toList();
    }
}
