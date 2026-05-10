package vip.mate.llm.failover;

import vip.mate.llm.model.ModelProviderEntity;

import java.util.Map;

/**
 * Decide which fields a {@link ModelProviderEntity} needs to be considered
 * "configured", based on the row's columns rather than its protocol enum.
 *
 * <p>Why row-based: every OpenAI-compatible provider (OpenAI / Kimi / DeepSeek
 * cloud as well as llama.cpp / lmstudio / vllm / ollama local) shares the
 * single {@code OPENAI_COMPATIBLE} protocol value. A protocol-keyed lookup
 * cannot distinguish "cloud, needs api key" from "local, needs base url".
 * The discriminating signals all live on the row: {@code requireApiKey},
 * {@code isLocal}, {@code isCustom}, {@code authType}, {@code providerId}.
 *
 * <p>Hint key + args (no raw text) so the frontend renders via i18n
 * {@code t(key, args)} without leaking Chinese into the English locale.
 */
public final class ProviderRequirements {

    /** What this provider row needs to be considered configured. */
    public record Required(
            boolean needsApiKey,
            boolean needsBaseUrl,
            String hintKey,                 // i18n key, null when no hint applies
            Map<String, Object> hintArgs    // template params for vue-i18n; never raw text
    ) {}

    private static final Required NONE = new Required(false, false, null, Map.of());

    private ProviderRequirements() {}

    /**
     * Compute the required-fields verdict for a provider row.
     *
     * Decision tree:
     *   - authType == "oauth"        -> no api key, no base url (OAuth handled elsewhere)
     *   - requireApiKey == true       -> needs api key
     *   - isLocal || isCustom         -> needs base url (no sane SDK default)
     *   - cloud built-ins             -> SDK ships hard-coded base url; no base url needed
     *
     * Hint key picked from a small providerId-substring map for the most common
     * local providers; everything else falls back to a generic OpenAI-compatible
     * hint so the user always sees an actionable example URL.
     */
    public static Required of(ModelProviderEntity provider) {
        if (provider == null) return NONE;

        // OAuth providers store credentials elsewhere (DB column or disk). Neither
        // api key nor base url applies to the configured check.
        if ("oauth".equals(provider.getAuthType())) {
            return NONE;
        }

        boolean needsApiKey  = Boolean.TRUE.equals(provider.getRequireApiKey());
        boolean isLocal      = Boolean.TRUE.equals(provider.getIsLocal());
        boolean isCustom     = Boolean.TRUE.equals(provider.getIsCustom());
        boolean needsBaseUrl = isLocal || isCustom;

        if (!needsBaseUrl) {
            return new Required(needsApiKey, false, null, Map.of());
        }

        // Pick a hint by providerId substring. Order matters: more specific names
        // first so "lm-studio" doesn't accidentally match a generic prefix later.
        String pid = provider.getProviderId() == null ? "" : provider.getProviderId().toLowerCase();
        String hintKey;
        Map<String, Object> hintArgs;
        if (pid.contains("ollama")) {
            hintKey = "provider.hint.ollamaBaseUrlExample";
            hintArgs = Map.of("example", "http://127.0.0.1:11434");
        } else if (pid.contains("lmstudio") || pid.contains("lm-studio") || pid.contains("lm_studio")) {
            hintKey = "provider.hint.lmstudioBaseUrlExample";
            hintArgs = Map.of("example", "http://127.0.0.1:1234/v1");
        } else if (pid.contains("llamacpp") || pid.contains("llama-cpp") || pid.contains("llama_cpp")
                || pid.contains("llama.cpp")) {
            hintKey = "provider.hint.llamacppBaseUrlExample";
            hintArgs = Map.of("example", "http://127.0.0.1:8080/v1");
        } else if (pid.contains("vllm")) {
            hintKey = "provider.hint.vllmBaseUrlExample";
            hintArgs = Map.of("example", "http://127.0.0.1:8000/v1");
        } else {
            hintKey = "provider.hint.openaiCompatBaseUrlExample";
            hintArgs = Map.of("example", "http://127.0.0.1:8080/v1");
        }
        return new Required(needsApiKey, true, hintKey, hintArgs);
    }
}
