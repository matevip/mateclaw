package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.tool.image.*;

import java.util.List;
import java.util.Set;

/**
 * DashScope 图片生成 Provider — 支持通义万相 Wanx 系列
 * <p>
 * 异步模式：提交后返回 taskId，需轮询获取结果。
 * 复用已有的 DashScope LLM provider 的 API Key。
 * API 文档: https://help.aliyun.com/zh/model-studio/developer-reference/text-to-image
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeImageProvider implements ImageGenerationProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    private static final String DEFAULT_MODEL = "wanx2.1-t2i-turbo";

    @Override
    public String id() {
        return "dashscope";
    }

    @Override
    public String label() {
        return "DashScope (通义万相)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 100;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of("1024x1024", "720x1280", "1280x720"))
                .aspectRatios(List.of("1:1", "16:9", "9:16"))
                .maxCount(4)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("wanx2.1-t2i-turbo", "wanx-v1"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured("dashscope");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null) {
            return ImageSubmitResult.failure(id(), "DashScope API Key 未配置");
        }

        try {
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);

            ObjectNode input = body.putObject("input");
            input.put("prompt", request.getPrompt());

            ObjectNode parameters = body.putObject("parameters");
            // request.size already normalized by ImageGenerationService to one of supportedSizes.
            // DashScope API uses '*' separator instead of 'x'.
            String size = request.getSize();
            if (size != null && !size.isBlank()) {
                parameters.put("size", size.replace("x", "*"));
            }
            int count = request.getCount() != null ? Math.min(request.getCount(), 4) : 1;
            parameters.put("n", count);

            HttpResponse response = HttpRequest.post(BASE_URL + "/services/aigc/text2image/image-synthesis")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .body(body.toString())
                    .timeout(30_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (response.getStatus() == 200 && result.has("output")) {
                String taskId = result.path("output").path("task_id").asText();
                log.info("[DashScope Image] Submitted task: {} (model={})", taskId, model);
                return ImageSubmitResult.asyncSuccess(taskId, id());
            } else {
                String errMsg = result.has("message") ? result.get("message").asText()
                        : "HTTP " + response.getStatus();
                log.warn("[DashScope Image] Submit failed: {}", errMsg);
                return ImageSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[DashScope Image] Submit error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null) {
            return TaskPollResult.failed("DashScope API Key 未配置");
        }

        try {
            HttpResponse response = HttpRequest.get(BASE_URL + "/tasks/" + providerTaskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            JsonNode output = result.path("output");
            String taskStatus = output.path("task_status").asText();

            return switch (taskStatus) {
                case "SUCCEEDED" -> {
                    String imageUrl = extractImageUrl(output);
                    yield TaskPollResult.imageSucceeded(imageUrl, output.toString());
                }
                case "FAILED" -> {
                    String errMsg = output.has("message") ? output.get("message").asText() : "任务失败";
                    yield TaskPollResult.failed(errMsg);
                }
                case "RUNNING" -> TaskPollResult.running(null);
                default -> TaskPollResult.pending(null);
            };
        } catch (Exception e) {
            log.error("[DashScope Image] Poll error for task {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }

    private String getDashScopeApiKey() {
        try {
            var providerEntity = modelProviderService.getProviderConfig("dashscope");
            return providerEntity.getApiKey();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractImageUrl(JsonNode output) {
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            return results.get(0).path("url").asText(null);
        }
        return null;
    }
}
