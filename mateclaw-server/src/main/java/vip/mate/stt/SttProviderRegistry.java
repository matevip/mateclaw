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
 * STT 提供商注册表.
 *
 * <p>Provider selection is two-stage:
 * <ol>
 *   <li>If the user pinned a specific provider in settings, that wins.</li>
 *   <li>Otherwise sort all available providers by
 *       {@link SttProvider#autoDetectOrder(String)} — the per-language
 *       hook lets Whisper win on English while Paraformer wins on Chinese.
 *       Falls back to the language-agnostic order when no language hint
 *       is supplied.</li>
 * </ol>
 *
 * <p>The registry is constructed once at startup and the unsorted provider
 * list is held — sorting happens on demand because the order depends on the
 * incoming language hint.
 */
@Slf4j
@Component
public class SttProviderRegistry {

    private final List<SttProvider> providers;
    private final Map<String, SttProvider> providerMap;

    public SttProviderRegistry(List<SttProvider> providers) {
        this.providers = List.copyOf(providers);
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(SttProvider::id, Function.identity()));
        log.info("注册 STT 提供商 {} 个: {}", providers.size(),
                providers.stream()
                        .map(p -> p.id() + "(default-order=" + p.autoDetectOrder() + ")")
                        .toList());
    }

    /** Backwards-compatible no-language overload. Prefer the (config, language) form. */
    public SttProvider resolve(SystemSettingsDTO config) {
        return resolve(config, null);
    }

    /**
     * Pick the primary provider given the user's settings + a language hint.
     * Returns null when nothing is available — callers should treat that as
     * "no API key configured anywhere" and surface the actionable error.
     */
    public SttProvider resolve(SystemSettingsDTO config, String language) {
        String configuredId = config.getSttProvider();
        if (configuredId != null && !configuredId.isBlank() && !"auto".equals(configuredId)) {
            SttProvider p = providerMap.get(configuredId);
            if (p != null && p.isAvailable(config)) return p;
            // Configured-but-unavailable falls through to auto so the user
            // still gets a result if any other provider has its key set.
        }
        for (SttProvider p : sortedByLanguage(language)) {
            if (p.isAvailable(config)) return p;
        }
        return null;
    }

    /** Backwards-compatible no-language overload. */
    public List<SttProvider> fallbackCandidates(SystemSettingsDTO config, String excludeId) {
        return fallbackCandidates(config, excludeId, null);
    }

    /**
     * Available providers other than {@code excludeId}, ordered by their
     * priority for the given language. The fallback list always uses the
     * language-aware order — if Whisper failed on Chinese, Paraformer is
     * the right next-best, not whatever happened to come next in the
     * default order.
     */
    public List<SttProvider> fallbackCandidates(SystemSettingsDTO config, String excludeId, String language) {
        return sortedByLanguage(language).stream()
                .filter(p -> !p.id().equals(excludeId))
                .filter(p -> p.isAvailable(config))
                .toList();
    }

    private List<SttProvider> sortedByLanguage(String language) {
        return providers.stream()
                .sorted(Comparator.comparingInt(p -> p.autoDetectOrder(language)))
                .toList();
    }
}
