package vip.mate.stt;

import vip.mate.system.model.SystemSettingsDTO;

/**
 * STT 语音识别提供商接口.
 *
 * <p>Auto-detect ordering uses ascending priority (low number = preferred).
 * Most providers can use the default {@link #autoDetectOrder()} value, but
 * providers with strong language bias should override
 * {@link #autoDetectOrder(String)} so the registry picks the right primary
 * for the user's locale: OpenAI Whisper is the canonical English path,
 * DashScope Paraformer is the canonical Chinese path. Mixing them up at
 * dispatch time costs accuracy AND latency (the wrong primary tends to
 * produce garbage that the fallback can't easily compensate for).
 */
public interface SttProvider {
    String id();
    String label();
    boolean requiresCredential();
    int autoDetectOrder();
    boolean isAvailable(SystemSettingsDTO config);

    /**
     * Per-language priority hook. Default implementation returns the
     * language-agnostic {@link #autoDetectOrder()} value, so existing
     * providers stay backwards-compatible. Override when the provider has
     * a known language strength — e.g. OpenAI Whisper returns a smaller
     * number for {@code "en"} than for {@code "zh"} to win the auto-pick
     * for English users.
     *
     * @param language IETF / ISO-639 language hint, possibly {@code null}.
     *                 Implementations should match conservatively (prefix
     *                 match on {@code zh}, {@code en}, etc.) and gracefully
     *                 fall back to {@link #autoDetectOrder()} on unknown.
     */
    default int autoDetectOrder(String language) {
        return autoDetectOrder();
    }

    /**
     * 转写音频
     */
    SttResult transcribe(SttRequest request, SystemSettingsDTO config);
}
