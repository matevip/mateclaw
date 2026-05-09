package vip.mate.stt.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.stt.SttProvider;
import vip.mate.stt.SttRequest;
import vip.mate.stt.SttResult;
import vip.mate.stt.SttTransportConfig;
import vip.mate.stt.transport.OpenAiCompatibleSttTransport;
import vip.mate.system.model.SystemSettingsDTO;

/**
 * OpenAI Whisper / OpenAI-compatible STT provider — thin wrapper.
 *
 * <p>Issue #76: this used to bake the {@code id="openai"} credential row + the
 * {@code https://api.openai.com} base URL + Whisper-1 directly into the
 * transport call, so the only way to point STT at FunASR / SiliconFlow / Groq
 * was to hand-edit the OpenAI provider row's baseUrl (lossy + side-effects on
 * chat). After this refactor:
 *
 * <ul>
 *   <li>Wire protocol lives in {@link OpenAiCompatibleSttTransport}.</li>
 *   <li>Credential row is selected by {@code SystemSettingsDTO.sttOpenAiCompatProviderId}
 *       (defaults to {@code "openai"} for backwards compatibility).</li>
 *   <li>Model is selected by {@code SystemSettingsDTO.sttOpenAiCompatModel}
 *       (defaults to {@code "whisper-1"}).</li>
 * </ul>
 *
 * <p>The provider id stays {@code "openai"} because settings UI / fallback
 * registry / per-language ordering all key off it. Phase 2 of the refactor
 * will replace this single provider with a profile-driven registry; until
 * then, swapping the credential row is the path forward for new vendors.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiSttProvider implements SttProvider {

    private final ModelProviderService modelProviderService;
    private final OpenAiCompatibleSttTransport transport;

    private static final String LEGACY_DEFAULT_PROVIDER_ID = "openai";
    private static final String LEGACY_DEFAULT_MODEL = "whisper-1";

    @Override public String id() { return "openai"; }
    @Override public String label() { return "OpenAI / OpenAI-compatible (Whisper)"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 100; }

    /**
     * Whisper is the canonical English STT and noticeably weaker on Chinese
     * (it tends to produce simplified-character output even for traditional
     * input, and short Chinese clips frequently transcribe to gibberish).
     * Boost Whisper's priority for English/Japanese/Korean (where it leads),
     * and de-prioritise it for Chinese so DashScope (Paraformer) wins the
     * auto-pick.
     */
    @Override
    public int autoDetectOrder(String language) {
        if (language == null) return autoDetectOrder();
        String lang = language.toLowerCase();
        if (lang.startsWith("zh")) return 250;        // pushed below DashScope Paraformer
        if (lang.startsWith("en")
                || lang.startsWith("ja")
                || lang.startsWith("ko")) return 80;  // pulled above DashScope
        return autoDetectOrder();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            String providerId = resolveProviderId(config);
            return modelProviderService.isProviderConfigured(providerId);
        } catch (Exception e) {
            log.warn("[OpenAI STT] availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
        String providerId = resolveProviderId(config);
        ModelProviderEntity provider;
        try {
            provider = modelProviderService.getProviderConfig(providerId);
        } catch (MateClawException e) {
            return SttResult.failure("STT 凭证 provider 未找到: " + providerId);
        }

        String apiKey = provider.getApiKey();
        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? provider.getBaseUrl()
                : "https://api.openai.com";

        // Allow blank apiKey for self-hosted / no-auth setups (FunASR is the
        // typical case). The transport will only attach the Authorization
        // header when apiKey is present.
        boolean requiresKey = Boolean.TRUE.equals(provider.getRequireApiKey());
        if (requiresKey && (apiKey == null || apiKey.isBlank())) {
            return SttResult.failure("STT 凭证 provider 未配置 API Key: " + providerId);
        }

        String model = resolveModel(config);
        SttTransportConfig transportConfig = new SttTransportConfig(baseUrl, apiKey, model);
        return transport.transcribe(request, transportConfig);
    }

    private String resolveProviderId(SystemSettingsDTO config) {
        String configured = config != null ? config.getSttOpenAiCompatProviderId() : null;
        return StringUtils.hasText(configured) ? configured.trim() : LEGACY_DEFAULT_PROVIDER_ID;
    }

    private String resolveModel(SystemSettingsDTO config) {
        String configured = config != null ? config.getSttOpenAiCompatModel() : null;
        return StringUtils.hasText(configured) ? configured.trim() : LEGACY_DEFAULT_MODEL;
    }
}
