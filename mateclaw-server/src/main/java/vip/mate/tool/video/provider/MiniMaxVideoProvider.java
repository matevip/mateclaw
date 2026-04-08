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
 * MiniMax (Hailuo / 海螺) 视频生成 Provider
 * <p>
 * API 文档: https://platform.minimaxi.com/document/video-generation
 * 鉴权: Bearer Token
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniMaxVideoProvider implements VideoGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.minimax.io";
    private static final String DEFAULT_MODEL = "MiniMax-Hailuo-2.3";

    @Override
    public String id() {
        return "minimax";
    }

    @Override
    public String label() {
        return "MiniMax (Hailuo)";
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
    public Set<VideoCapability> capabilities() {
        return Set.of(VideoCapability.GENERATE, VideoCapability.IMAGE_TO_VIDEO);
    }

    @Override
    public VideoProviderCapabilities detailedCapabilities() {
        return VideoProviderCapabilities.builder()
                .modes(capabilities())
                .aspectRatios(List.of("16:9", "9:16", "1:1"))
                .supportedDurations(List.of(6, 10))
                .maxDurationSeconds(10)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("MiniMax-Hailuo-2.3", "MiniMax-Hailuo-2.3-Fast", "I2V-01-live"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return StringUtils.hasText(config.getMinimaxApiKey());
    }

    @Override
    public VideoSubmitResult submit(VideoGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = config.getMinimaxApiKey();
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", request.getPrompt());

            if (request.getMode() == VideoCapability.IMAGE_TO_VIDEO && request.getImageUrl() != null) {
                body.put("first_frame_image", request.getImageUrl());
            }
            if (request.getDurationSeconds() != null) {
                body.put("duration", request.getDurationSeconds());
            }

            HttpResponse response = HttpRequest.post(BASE_URL + "/v1/video_generation")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            // MiniMax 返回 { task_id, base_resp: { status_code, status_msg } }
            int statusCode = result.path("base_resp").path("status_code").asInt(-1);
            if (statusCode == 0 && result.has("task_id")) {
                String taskId = result.get("task_id").asText();
                log.info("[MiniMax] Submitted task: {} (model={})", taskId, model);
                return VideoSubmitResult.success(taskId, id());
            } else {
                String errMsg = result.path("base_resp").path("status_msg").asText("未知错误");
                log.warn("[MiniMax] Submit failed: {}", errMsg);
                return VideoSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[MiniMax] Submit error: {}", e.getMessage(), e);
            return VideoSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        try {
            String apiKey = config.getMinimaxApiKey();

            HttpResponse response = HttpRequest.get(
                            BASE_URL + "/v1/query/video_generation?task_id=" + providerTaskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            String status = result.path("status").asText();

            return switch (status) {
                case "Success" -> {
                    // 优先取 video_url，备选 file_id
                    String videoUrl = result.has("video_url") ? result.get("video_url").asText(null) : null;
                    if (videoUrl == null && result.has("file_id")) {
                        videoUrl = resolveFileUrl(result.get("file_id").asText(), apiKey);
                    }
                    yield TaskPollResult.succeeded(videoUrl, null, result.toString());
                }
                case "Fail" -> {
                    String errMsg = result.path("base_resp").path("status_msg").asText("任务失败");
                    yield TaskPollResult.failed(errMsg);
                }
                case "Processing" -> TaskPollResult.running(null);
                default -> TaskPollResult.pending(null); // Preparing
            };
        } catch (Exception e) {
            log.error("[MiniMax] Poll error for task {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }

    /**
     * 通过 file_id 获取视频下载 URL
     */
    private String resolveFileUrl(String fileId, String apiKey) {
        try {
            HttpResponse response = HttpRequest.get(
                            BASE_URL + "/v1/files/retrieve?file_id=" + fileId)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(10_000)
                    .execute();
            JsonNode result = objectMapper.readTree(response.body());
            JsonNode file = result.path("file");
            return file.has("download_url") ? file.get("download_url").asText(null) : null;
        } catch (Exception e) {
            log.warn("[MiniMax] Failed to resolve file URL for {}: {}", fileId, e.getMessage());
            return null;
        }
    }
}
