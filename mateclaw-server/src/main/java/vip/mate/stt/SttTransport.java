package vip.mate.stt;

/**
 * Issue #76: protocol-family abstraction for STT.
 *
 * <p>The original {@link SttProvider} bundled "which vendor is this" with
 * "how does its wire protocol work", forcing every new vendor to ship a
 * dedicated Java class even when the wire protocol is identical to an
 * existing one. {@code SttTransport} is the protocol-only half: it knows how
 * to send a request and parse a response, but doesn't care whether the
 * endpoint is OpenAI cloud, FunASR self-hosted, SiliconFlow, Groq, or
 * Together — anything that speaks the same protocol can plug in.
 *
 * <p>Two transports cover ~99% of the market today:
 * <ul>
 *   <li>OpenAI Whisper compatible HTTP multipart (this transport)</li>
 *   <li>DashScope realtime WebSocket (kept inline in
 *       {@code DashScopeSttProvider} for now — its own transport class
 *       can be carved out the same way when a second WebSocket-based
 *       vendor lands)</li>
 * </ul>
 *
 * <p>Identity (display name, baseUrl defaults, language bias, ...) is
 * declared by a future {@code SttProviderProfile} layer (Phase 2 of the
 * refactor). Phase 1 keeps {@link SttProvider} as the public SPI but
 * delegates the wire work to a transport so swapping the credential row
 * doesn't require changing the provider class.
 */
public interface SttTransport {

    /**
     * Stable id of the protocol family this transport speaks. Profiles
     * pick a transport by matching against this — e.g.
     * {@code "openai_compatible_audio"} for any OpenAI Whisper-shaped
     * endpoint.
     */
    String apiMode();

    /**
     * Run a transcription against the resolved endpoint. Returns a typed
     * success/failure result; transport implementations must NOT throw —
     * caller relies on the failure path to keep the {@link SttProvider}
     * fallback chain alive.
     */
    SttResult transcribe(SttRequest request, SttTransportConfig config);
}
