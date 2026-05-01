package vip.mate.tool.model3d;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 3D-model provider registry. Mirrors {@link vip.mate.tool.music.MusicProviderRegistry}
 * — Spring auto-discovers all {@link Model3dGenerationProvider} beans, sorts by
 * {@link Model3dGenerationProvider#autoDetectOrder()}, exposes resolution and
 * fallback iteration to the service layer.
 */
@Slf4j
@Component
public class Model3dProviderRegistry {

    private final List<Model3dGenerationProvider> sortedProviders;
    private final Map<String, Model3dGenerationProvider> providerMap;

    public Model3dProviderRegistry(List<Model3dGenerationProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(Model3dGenerationProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(Model3dGenerationProvider::id, Function.identity()));
        log.info("注册 3D 模型生成 Provider {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    public Model3dGenerationProvider getById(String id) {
        return providerMap.get(id);
    }

    public Model3dGenerationProvider resolve(SystemSettingsDTO config, Model3dCapability mode) {
        String configuredId = config.getModel3dProvider();
        if (configuredId != null && !configuredId.isBlank() && !"auto".equals(configuredId)) {
            Model3dGenerationProvider p = providerMap.get(configuredId);
            if (p != null && p.isAvailable(config) && supportsMode(p, mode)) return p;
        }
        for (Model3dGenerationProvider p : sortedProviders) {
            if (p.isAvailable(config) && supportsMode(p, mode)) return p;
        }
        return null;
    }

    public List<Model3dGenerationProvider> fallbackCandidates(SystemSettingsDTO config,
                                                               Model3dCapability mode,
                                                               String excludeId) {
        return sortedProviders.stream()
                .filter(p -> !p.id().equals(excludeId))
                .filter(p -> p.isAvailable(config))
                .filter(p -> supportsMode(p, mode))
                .toList();
    }

    private static boolean supportsMode(Model3dGenerationProvider p, Model3dCapability mode) {
        if (mode == null) return true;
        return p.capabilities() != null && p.capabilities().contains(mode);
    }
}
