package vip.mate.tool.music;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MusicProviderRegistry {

    private final List<MusicGenerationProvider> sortedProviders;
    private final Map<String, MusicGenerationProvider> providerMap;

    public MusicProviderRegistry(List<MusicGenerationProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(MusicGenerationProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(MusicGenerationProvider::id, Function.identity()));
        log.info("注册音乐生成 Provider {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    public MusicGenerationProvider resolve(SystemSettingsDTO config) {
        String configuredId = config.getMusicProvider();
        if (configuredId != null && !configuredId.isBlank() && !"auto".equals(configuredId)) {
            MusicGenerationProvider p = providerMap.get(configuredId);
            if (p != null && p.isAvailable(config)) return p;
        }
        for (MusicGenerationProvider p : sortedProviders) {
            if (p.isAvailable(config)) return p;
        }
        return null;
    }

    public List<MusicGenerationProvider> fallbackCandidates(SystemSettingsDTO config, String excludeId) {
        return sortedProviders.stream().filter(p -> !p.id().equals(excludeId)).filter(p -> p.isAvailable(config)).toList();
    }
}
