package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 智谱 CogView 图片生成 Provider — 支持 CogView-4 / CogView-3-Flash
 * <p>
 * 同步模式：直接返回图片 URL。
 * CogView-3-Flash 模型免费。
 * API 文档: https://open.bigmodel.cn/dev/api/image-generate/cogview
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZhipuImageProvider implements ImageGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String DEFAULT_MODEL = "cogview-3-flash";

    @Override
    public String id() {
        return "zhipu-cogview";
    }

    @Override
    public String label() {
        return "智谱 CogView";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 150;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of("1024x1024", "768x1344", "1344x768",
                        "864x1152", "1152x864", "1440x720", "720x1440"))
                .aspectRatios(List.of("1:1", "16:9", "9:16", "4:3", "3:4"))
                .maxCount(1)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("cogview-4", "cogview-3-flash", "cogview-3-plus"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return config.getZhipuApiKey() != null && !config.getZhipuApiKey().isBlank();
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = config.getZhipuApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ImageSubmitResult.failure(id(), "智谱 API Key 未配置");
        }

        try {
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;

            String baseUrl = config.getZhipuBaseUrl() != null && !config.getZhipuBaseUrl().isBlank()
                    ? config.getZhipuBaseUrl() : "https://open.bigmodel.cn";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", request.getPrompt());

            // request.size already normalized by ImageGenerationService to one of supportedSizes.
            String size = request.getSize();
            if (size != null && !size.isBlank()) {
                body.put("size", size);
            }

            HttpResponse response = HttpRequest.post(baseUrl + "/api/paas/v4/images/generations")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(60_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (response.getStatus() == 200 && result.has("data")) {
                List<String> imageUrls = new ArrayList<>();
                for (JsonNode item : result.get("data")) {
                    String imageUrl = item.has("url") ? item.get("url").asText() : null;
                    if (imageUrl != null) {
                        imageUrls.add(imageUrl);
                    }
                }
                if (imageUrls.isEmpty()) {
                    return ImageSubmitResult.failure(id(), "API 返回成功但未包含图片 URL");
                }
                log.info("[Zhipu Image] Generated {} image(s) (model={})", imageUrls.size(), model);
                return ImageSubmitResult.syncSuccess(id(), imageUrls);
            } else {
                String errMsg = result.has("error")
                        ? result.path("error").path("message").asText("Unknown error")
                        : "HTTP " + response.getStatus();
                log.warn("[Zhipu Image] Submit failed: {}", errMsg);
                return ImageSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[Zhipu Image] Submit error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), e.getMessage());
        }
    }

}
