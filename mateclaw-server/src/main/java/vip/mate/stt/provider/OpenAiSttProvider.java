package vip.mate.stt.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.stt.SttProvider;
import vip.mate.stt.SttRequest;
import vip.mate.stt.SttResult;
import vip.mate.system.model.SystemSettingsDTO;

/**
 * OpenAI STT Provider — Whisper / gpt-4o-mini-transcribe
 * <p>
 * 复用模型管理中的 OpenAI API Key。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiSttProvider implements SttProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_MODEL = "whisper-1";

    @Override public String id() { return "openai"; }
    @Override public String label() { return "OpenAI Whisper"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 100; }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try { return modelProviderService.isProviderConfigured("openai"); }
        catch (Exception e) { return false; }
    }

    @Override
    public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = modelProviderService.getProviderConfig("openai").getApiKey();
            String baseUrl = modelProviderService.getProviderConfig("openai").getBaseUrl();
            if (apiKey == null) return SttResult.failure("OpenAI API Key 未配置");

            String url = (baseUrl != null ? baseUrl : "https://api.openai.com") + "/v1/audio/transcriptions";
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;
            String fileName = request.getFileName() != null ? request.getFileName() : "audio.ogg";

            HttpResponse response = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .form("model", model)
                    .form("file", request.getAudioData(), request.getContentType(), fileName)
                    .timeout(60_000)
                    .execute();

            if (response.getStatus() == 200) {
                JsonNode result = objectMapper.readTree(response.body());
                String text = result.path("text").asText("");
                log.info("[OpenAI STT] Transcribed {} chars (model={})", text.length(), model);
                return SttResult.success(text);
            } else {
                log.warn("[OpenAI STT] Failed: HTTP {} - {}", response.getStatus(), response.body());
                return SttResult.failure("OpenAI STT 失败: HTTP " + response.getStatus());
            }
        } catch (Exception e) {
            log.error("[OpenAI STT] Error: {}", e.getMessage(), e);
            return SttResult.failure("OpenAI STT 异常: " + e.getMessage());
        }
    }
}
