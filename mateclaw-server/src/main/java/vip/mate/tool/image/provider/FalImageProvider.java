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
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.tool.image.*;

import java.util.List;
import java.util.Set;

/**
 * fal.ai 图片生成 Provider — 支持 Flux 系列模型
 * <p>
 * 异步队列模式：提交到 queue，轮询获取结果。
 * API 文档: https://fal.ai/models/fal-ai/flux/dev/api
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FalImageProvider implements ImageGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String DEFAULT_MODEL = "fal-ai/flux/dev";
    private static final String QUEUE_BASE = "https://queue.fal.run";

    @Override
    public String id() {
        return "fal";
    }

    @Override
    public String label() {
        return "fal.ai (Flux)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 300;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of("1024x1024", "1024x1536", "1536x1024"))
                .aspectRatios(List.of("1:1", "16:9", "9:16", "4:3", "3:4"))
                .maxCount(4)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("fal-ai/flux/dev", "fal-ai/flux/schnell", "fal-ai/flux-pro/v1.1"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return config.getFalApiKey() != null && !config.getFalApiKey().isBlank();
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = config.getFalApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ImageSubmitResult.failure(id(), "fal.ai API Key 未配置");
        }

        try {
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("prompt", request.getPrompt());

            // request.size already normalized by ImageGenerationService to one of supportedSizes.
            // fal.ai expects image_size as a {width, height} object.
            String size = request.getSize();
            ObjectNode imageSize = body.putObject("image_size");
            String[] parts = size.split("x");
            imageSize.put("width", Integer.parseInt(parts[0]));
            imageSize.put("height", Integer.parseInt(parts[1]));

            int count = request.getCount() != null ? Math.min(request.getCount(), 4) : 1;
            body.put("num_images", count);

            String url = QUEUE_BASE + "/" + model;

            HttpResponse response = HttpRequest.post(url)
                    .header("Authorization", "Key " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (response.getStatus() == 200 && result.has("request_id")) {
                String requestId = result.get("request_id").asText();
                // 复合 taskId: model|requestId
                String compositeId = model + "|" + requestId;
                log.info("[Fal Image] Submitted task: {} (model={})", requestId, model);
                return ImageSubmitResult.asyncSuccess(compositeId, id());
            } else {
                String errMsg = result.has("detail") ? result.get("detail").asText()
                        : "HTTP " + response.getStatus();
                log.warn("[Fal Image] Submit failed: {}", errMsg);
                return ImageSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[Fal Image] Submit error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        String apiKey = config.getFalApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return TaskPollResult.failed("fal.ai API Key 未配置");
        }

        try {
            // 解析复合 ID: model|requestId
            String[] parts = providerTaskId.split("\\|", 2);
            if (parts.length != 2) {
                return TaskPollResult.failed("Invalid fal task ID format");
            }
            String model = parts[0];
            String requestId = parts[1];

            // 先检查状态
            String statusUrl = QUEUE_BASE + "/" + model + "/requests/" + requestId + "/status";
            HttpResponse statusResp = HttpRequest.get(statusUrl)
                    .header("Authorization", "Key " + apiKey)
                    .timeout(15_000)
                    .execute();

            JsonNode statusResult = objectMapper.readTree(statusResp.body());
            String status = statusResult.has("status") ? statusResult.get("status").asText() : "UNKNOWN";

            return switch (status) {
                case "COMPLETED" -> {
                    // 获取结果
                    String resultUrl = QUEUE_BASE + "/" + model + "/requests/" + requestId;
                    HttpResponse resultResp = HttpRequest.get(resultUrl)
                            .header("Authorization", "Key " + apiKey)
                            .timeout(15_000)
                            .execute();
                    JsonNode resultData = objectMapper.readTree(resultResp.body());
                    String imageUrl = extractImageUrl(resultData);
                    yield TaskPollResult.imageSucceeded(imageUrl, resultData.toString());
                }
                case "FAILED" -> {
                    String errMsg = statusResult.has("error") ? statusResult.get("error").asText() : "任务失败";
                    yield TaskPollResult.failed(errMsg);
                }
                case "IN_PROGRESS" -> TaskPollResult.running(null);
                default -> TaskPollResult.pending(null);
            };
        } catch (Exception e) {
            log.error("[Fal Image] Poll error for task {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }

    private String extractImageUrl(JsonNode result) {
        JsonNode images = result.path("images");
        if (images.isArray() && !images.isEmpty()) {
            return images.get(0).path("url").asText(null);
        }
        return null;
    }
}
