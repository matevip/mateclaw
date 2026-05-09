package vip.mate.stt.transport;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.stt.AudioMimeTypes;
import vip.mate.stt.SttRequest;
import vip.mate.stt.SttResult;
import vip.mate.stt.SttTransport;
import vip.mate.stt.SttTransportConfig;

/**
 * Issue #76: protocol family transport for the OpenAI Whisper-shaped HTTP
 * audio endpoint. Identical request format covers OpenAI itself, FunASR with
 * the openai-compat shim, SiliconFlow, Groq Whisper, Together, Volcano,
 * and roughly every other paid + self-hosted ASR vendor available today.
 *
 * <p>Wire shape:
 * <ul>
 *   <li>{@code POST {baseUrl}/v1/audio/transcriptions}
 *       (or {@code {baseUrl}/audio/transcriptions} when baseUrl already
 *       carries a {@code /vN} suffix)</li>
 *   <li>multipart/form-data with {@code model} field + {@code file} field
 *       carrying the audio bytes named after the detected mime type
 *       (Hutool infers the multipart Content-Type from the extension —
 *       {@link AudioMimeTypes#resolveFileName} is what makes that work).</li>
 *   <li>Optional {@code Authorization: Bearer <api_key>} when the caller
 *       supplies one. Self-hosted FunASR commonly skips auth entirely.</li>
 * </ul>
 *
 * <p>Response: {@code { "text": "..." }} — the only field we read.
 *
 * <p>The transport intentionally does NOT touch {@code ModelProviderService}
 * or {@code SystemSettingsDTO}: the caller resolves credentials and hands
 * them in via {@link SttTransportConfig}. This keeps the transport reusable
 * across any number of credential rows and trivially unit-testable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleSttTransport implements SttTransport {

    public static final String API_MODE = "openai_compatible_audio";

    private final ObjectMapper objectMapper;

    @Override
    public String apiMode() {
        return API_MODE;
    }

    @Override
    public SttResult transcribe(SttRequest request, SttTransportConfig config) {
        try {
            String baseUrl = normalizeBaseUrl(config.baseUrl());
            if (baseUrl == null) {
                return SttResult.failure("STT 端点 base URL 未配置");
            }
            String url = baseUrl + resolveAudioPath(baseUrl);
            String model = effectiveModel(request, config);
            // AudioMimeTypes ensures the filename extension matches the
            // actual bytes (audio.wav, audio.mp3, etc.), which Hutool then
            // uses to infer the multipart Content-Type. Don't pass
            // contentType to .form() explicitly — Hutool has no
            // form(String,byte[],String,String) overload, and the wrong
            // dispatch crashes with ClassCastException on byte[] → Object[].
            String fileName = AudioMimeTypes.resolveFileName(request.getFileName(), request.getContentType());

            HttpRequest http = HttpRequest.post(url)
                    .form("model", model)
                    .form("file", request.getAudioData(), fileName)
                    .timeout(60_000);
            String apiKey = config.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                http.header("Authorization", "Bearer " + apiKey.trim());
            }

            HttpResponse response = http.execute();
            if (response.getStatus() == 200) {
                JsonNode result = objectMapper.readTree(response.body());
                String text = result.path("text").asText("");
                log.info("[OpenAI-compat STT] Transcribed {} chars (model={}, baseUrl={})",
                        text.length(), model, baseUrl);
                return SttResult.success(text);
            }
            log.warn("[OpenAI-compat STT] Failed: HTTP {} - {}", response.getStatus(), response.body());
            return SttResult.failure("STT 失败: HTTP " + response.getStatus());
        } catch (Exception e) {
            log.error("[OpenAI-compat STT] Error: {}", e.getMessage(), e);
            return SttResult.failure("STT 异常: " + e.getMessage());
        }
    }

    /**
     * Pick the audio path to append. If baseUrl already ends in a {@code /vN}
     * version segment (lmstudio-style), append only {@code /audio/transcriptions}.
     * Otherwise append {@code /v1/audio/transcriptions}. Mirrors the resolver
     * pattern used by the chat-models probe so user-set baseUrls behave
     * consistently across endpoints.
     */
    static String resolveAudioPath(String baseUrl) {
        if (baseUrl != null && baseUrl.matches(".*/v\\d{1,2}$")) {
            return "/audio/transcriptions";
        }
        return "/v1/audio/transcriptions";
    }

    static String normalizeBaseUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String effectiveModel(SttRequest request, SttTransportConfig config) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        if (config.model() != null && !config.model().isBlank()) {
            return config.model();
        }
        return "whisper-1";
    }
}
