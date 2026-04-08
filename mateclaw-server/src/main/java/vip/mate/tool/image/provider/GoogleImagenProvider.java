package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.*;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Google Imagen 图片生成 Provider — 使用 Gemini API 的图片生成能力
 * <p>
 * 同步模式：直接返回 Base64 图片数据。
 * 复用已有的 Google/Gemini LLM provider 的 API Key。
 * <p>
 * API: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleImagenProvider implements ImageGenerationProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String DEFAULT_MODEL = "gemini-2.0-flash-preview-image-generation";

    @Override
    public String id() {
        return "google-imagen";
    }

    @Override
    public String label() {
        return "Google Imagen";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 250;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of("1024x1024", "1024x1536", "1536x1024", "1024x1792", "1792x1024"))
                .aspectRatios(List.of("1:1", "3:4", "4:3", "9:16", "16:9"))
                .maxCount(4)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("gemini-2.0-flash-preview-image-generation", "imagen-4.0-generate-preview", "imagen-4.0-ultra-generate-preview"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured("google");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = getApiKey();
            if (apiKey == null) {
                return ImageSubmitResult.failure(id(), "Google API Key 未配置");
            }

            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;

            // 构建请求体
            ObjectNode body = objectMapper.createObjectNode();

            // contents
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            content.put("role", "user");
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", request.getPrompt());

            // generationConfig
            ObjectNode genConfig = body.putObject("generationConfig");
            ArrayNode modalities = genConfig.putArray("responseModalities");
            modalities.add("TEXT");
            modalities.add("IMAGE");

            if (request.getAspectRatio() != null) {
                ObjectNode imageConfig = genConfig.putObject("imageConfig");
                imageConfig.put("aspectRatio", request.getAspectRatio());
            }

            String url = BASE_URL + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(60_000)
                    .execute();

            if (response.getStatus() != 200) {
                String errBody = response.body();
                log.warn("[Google Imagen] Failed: HTTP {} - {}", response.getStatus(), errBody);
                return ImageSubmitResult.failure(id(), "Google Imagen 失败: HTTP " + response.getStatus());
            }

            JsonNode result = objectMapper.readTree(response.body());
            List<String> imageUrls = extractImagesFromResponse(result);

            if (imageUrls.isEmpty()) {
                return ImageSubmitResult.failure(id(), "Google Imagen 未返回图片");
            }

            log.info("[Google Imagen] Generated {} images (model={})", imageUrls.size(), model);
            return ImageSubmitResult.syncSuccess(id(), imageUrls);

        } catch (Exception e) {
            log.error("[Google Imagen] Error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), "Google Imagen 异常: " + e.getMessage());
        }
    }

    /**
     * 从 Gemini 响应中提取 Base64 图片，转换为 data URI
     */
    private List<String> extractImagesFromResponse(JsonNode result) {
        List<String> images = new ArrayList<>();

        JsonNode candidates = result.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        // 尝试 inlineData 或 inline_data
                        JsonNode inlineData = part.has("inlineData") ? part.get("inlineData")
                                : part.path("inline_data");
                        if (inlineData.has("data")) {
                            String mimeType = inlineData.has("mimeType")
                                    ? inlineData.get("mimeType").asText("image/png")
                                    : inlineData.path("mime_type").asText("image/png");
                            String base64Data = inlineData.get("data").asText();
                            // 返回 data URI 格式
                            images.add("data:" + mimeType + ";base64," + base64Data);
                        }
                    }
                }
            }
        }
        return images;
    }

    private String getApiKey() {
        try {
            return modelProviderService.getProviderConfig("google").getApiKey();
        } catch (Exception e) {
            return null;
        }
    }
}
