package vip.mate.stt;

/**
 * Issue #76: resolved endpoint config passed to an {@link SttTransport}.
 *
 * <p>Decoupling the transport from {@code ModelProviderService} lookups makes
 * tests trivial (no Spring context) and lets the same transport serve any
 * credential row — OpenAI cloud, FunASR self-hosted, SiliconFlow, Groq, etc.
 *
 * @param baseUrl  fully-qualified provider base URL ({@code https://api.openai.com}
 *                 or {@code http://10.0.0.5:9999/v1}). Trailing slash optional;
 *                 transports normalize it.
 * @param apiKey   bearer token. May be blank when the provider doesn't require
 *                 authentication (some self-hosted FunASR deployments).
 * @param model    the model id sent in the multipart "model" field
 *                 (whisper-1 / paraformer-large / FunAudioLLM-Whisper / ...).
 */
public record SttTransportConfig(String baseUrl, String apiKey, String model) {
}
