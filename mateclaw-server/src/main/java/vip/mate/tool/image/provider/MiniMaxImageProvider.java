package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MiniMax 图片生成 Provider — image-01 模型
 * <p>
 * 同步模式：返回 Base64 图片。
 * 复用视频生成中的 MiniMax API Key。
 * <p>
 * API: POST https://api.minimax.io/v1/image_generation
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniMaxImageProvider implements ImageGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.minimax.io";
    private static final String DEFAULT_MODEL = "image-01";

    @Override
    public String id() {
        return "minimax";
    }

    @Override
    public String label() {
        return "MiniMax Image";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 350;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of("1024x1024"))
                .aspectRatios(List.of("1:1", "16:9", "4:3", "3:2", "2:3", "3:4", "9:16"))
                .maxCount(9)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("image-01"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        // 复用视频生成的 MiniMax API Key
        return StringUtils.hasText(config.getMinimaxApiKey());
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = config.getMinimaxApiKey();
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", request.getPrompt());
            body.put("response_format", "url");
            body.put("n", request.getCount() != null ? request.getCount() : 1);

            if (request.getAspectRatio() != null) {
                body.put("aspect_ratio", request.getAspectRatio());
            }

            HttpResponse response = HttpRequest.post(BASE_URL + "/v1/image_generation")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(60_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            int statusCode = result.path("base_resp").path("status_code").asInt(-1);
            if (statusCode != 0) {
                String errMsg = result.path("base_resp").path("status_msg").asText("未知错误");
                log.warn("[MiniMax Image] Failed: {}", errMsg);
                return ImageSubmitResult.failure(id(), errMsg);
            }

            List<String> imageUrls = new ArrayList<>();

            // 尝试从 data.image_base64 提取
            JsonNode imageBase64Array = result.path("data").path("image_base64");
            if (imageBase64Array.isArray()) {
                for (JsonNode b64 : imageBase64Array) {
                    String base64Str = b64.asText();
                    if (StringUtils.hasText(base64Str)) {
                        imageUrls.add("data:image/png;base64," + base64Str);
                    }
                }
            }

            // 尝试从 data.image_urls 提取（response_format=url 时）
            JsonNode imageUrlsNode = result.path("data").path("image_urls");
            if (imageUrlsNode.isArray()) {
                for (JsonNode urlNode : imageUrlsNode) {
                    String url = urlNode.asText(null);
                    if (StringUtils.hasText(url)) {
                        imageUrls.add(url);
                    }
                }
            }

            if (imageUrls.isEmpty()) {
                return ImageSubmitResult.failure(id(), "MiniMax 未返回图片数据");
            }

            log.info("[MiniMax Image] Generated {} images (model={})", imageUrls.size(), model);
            return ImageSubmitResult.syncSuccess(id(), imageUrls);

        } catch (Exception e) {
            log.error("[MiniMax Image] Error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), "MiniMax Image 异常: " + e.getMessage());
        }
    }
}
