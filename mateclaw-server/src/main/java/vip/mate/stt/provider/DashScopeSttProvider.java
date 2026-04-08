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
 * DashScope STT Provider — Paraformer（OpenAI 兼容接口）
 * <p>
 * 复用模型管理中的 DashScope API Key。中文识别效果优秀。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeSttProvider implements SttProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "paraformer-v2";

    @Override public String id() { return "dashscope"; }
    @Override public String label() { return "DashScope (Paraformer)"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 150; }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try { return modelProviderService.isProviderConfigured("dashscope"); }
        catch (Exception e) { return false; }
    }

    @Override
    public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = modelProviderService.getProviderConfig("dashscope").getApiKey();
            if (apiKey == null) return SttResult.failure("DashScope API Key 未配置");

            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;
            String fileName = request.getFileName() != null ? request.getFileName() : "audio.ogg";

            HttpResponse response = HttpRequest.post(BASE_URL + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + apiKey)
                    .form("model", model)
                    .form("file", request.getAudioData(), request.getContentType(), fileName)
                    .timeout(60_000)
                    .execute();

            if (response.getStatus() == 200) {
                JsonNode result = objectMapper.readTree(response.body());
                String text = result.path("text").asText("");
                log.info("[DashScope STT] Transcribed {} chars (model={})", text.length(), model);
                return SttResult.success(text);
            } else {
                log.warn("[DashScope STT] Failed: HTTP {} - {}", response.getStatus(), response.body());
                return SttResult.failure("DashScope STT 失败: HTTP " + response.getStatus());
            }
        } catch (Exception e) {
            log.error("[DashScope STT] Error: {}", e.getMessage(), e);
            return SttResult.failure("DashScope STT 异常: " + e.getMessage());
        }
    }
}
