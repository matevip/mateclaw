package vip.mate.tool.video.provider;

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
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.tool.video.*;

import java.util.List;
import java.util.Set;

/**
 * Runway 视频生成 Provider — gen4.5 / gen4_turbo / gen3a_turbo
 * <p>
 * API 文档: https://docs.runwayml.com/
 * 鉴权: Bearer Token
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunwayVideoProvider implements VideoGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.dev.runwayml.com";
    private static final String API_VERSION = "2024-11-06";
    private static final String DEFAULT_MODEL = "gen4_turbo";

    @Override
    public String id() {
        return "runway";
    }

    @Override
    public String label() {
        return "Runway";
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
    public Set<VideoCapability> capabilities() {
        return Set.of(VideoCapability.GENERATE, VideoCapability.IMAGE_TO_VIDEO);
    }

    @Override
    public VideoProviderCapabilities detailedCapabilities() {
        return VideoProviderCapabilities.builder()
                .modes(capabilities())
                .aspectRatios(List.of("16:9", "9:16"))
                .supportedDurations(List.of(5, 10))
                .maxDurationSeconds(10)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("gen4.5", "gen4_turbo", "gen3a_turbo"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return StringUtils.hasText(config.getRunwayApiKey());
    }

    @Override
    public VideoSubmitResult submit(VideoGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = config.getRunwayApiKey();
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;

            // 根据模式选择端点
            String endpoint = request.getMode() == VideoCapability.IMAGE_TO_VIDEO
                    ? "/v1/image_to_video" : "/v1/text_to_video";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("promptText", request.getPrompt());

            if (request.getMode() == VideoCapability.IMAGE_TO_VIDEO && request.getImageUrl() != null) {
                body.put("promptImage", request.getImageUrl());
            }
            if (request.getAspectRatio() != null) {
                // Runway 用 "1280:720" 格式
                String ratio = request.getAspectRatio().equals("16:9") ? "1280:720"
                        : request.getAspectRatio().equals("9:16") ? "720:1280"
                        : "1280:720";
                body.put("ratio", ratio);
            }
            if (request.getDurationSeconds() != null) {
                body.put("duration", request.getDurationSeconds());
            }

            HttpResponse response = HttpRequest.post(BASE_URL + endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Runway-Version", API_VERSION)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (response.getStatus() == 200 && result.has("id")) {
                String taskId = result.get("id").asText();
                log.info("[Runway] Submitted task: {} (model={})", taskId, model);
                return VideoSubmitResult.success(taskId, id());
            } else {
                String errMsg = result.has("error") ? result.get("error").asText()
                        : "HTTP " + response.getStatus();
                log.warn("[Runway] Submit failed: {}", errMsg);
                return VideoSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[Runway] Submit error: {}", e.getMessage(), e);
            return VideoSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        try {
            String apiKey = config.getRunwayApiKey();

            HttpResponse response = HttpRequest.get(BASE_URL + "/v1/tasks/" + providerTaskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Runway-Version", API_VERSION)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            String status = result.path("status").asText();

            return switch (status) {
                case "SUCCEEDED" -> {
                    // output 是视频 URL 数组
                    JsonNode output = result.path("output");
                    String videoUrl = null;
                    if (output.isArray() && !output.isEmpty()) {
                        videoUrl = output.get(0).asText();
                    }
                    yield TaskPollResult.succeeded(videoUrl, null, result.toString());
                }
                case "FAILED", "CANCELLED" -> {
                    String failure = result.has("failure") ? result.get("failure").asText() : "任务失败";
                    yield TaskPollResult.failed(failure);
                }
                case "RUNNING" -> TaskPollResult.running(null);
                case "THROTTLED" -> TaskPollResult.running(null); // 被限流，等同于运行中
                default -> TaskPollResult.pending(null); // PENDING
            };
        } catch (Exception e) {
            log.error("[Runway] Poll error for task {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }
}
