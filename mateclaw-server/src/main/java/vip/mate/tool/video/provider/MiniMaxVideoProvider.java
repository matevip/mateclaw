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
 * <p>
 * Region 切换：根据 {@link SystemSettingsDTO#getMinimaxRegion()} 选 host：
 * <ul>
 *   <li>{@code "global"} (默认) → {@code https://api.minimax.io}</li>
 *   <li>{@code "cn"} → {@code https://api.minimaxi.com} (mainland-CN
 *       lower-latency endpoint; required for accounts registered in CN).</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniMaxVideoProvider implements VideoGenerationProvider {

    private final ObjectMapper objectMapper;

    /** Global endpoint (used by accounts on api.minimax.io). */
    static final String BASE_URL_GLOBAL = "https://api.minimax.io";

    /** China endpoint (api.minimaxi.com — same JSON shape, different host). */
    static final String BASE_URL_CN = "https://api.minimaxi.com";

    /** Region value selecting the CN endpoint. Anything else → global. */
    static final String REGION_CN = "cn";

    private static final String DEFAULT_MODEL = "MiniMax-Hailuo-2.3";

    /**
     * Full MiniMax video model catalog (matches openclaw
     * {@code extensions/minimax/provider-models.ts}). Includes both T2V
     * (Hailuo family) and I2V (I2V-01-* family) entries.
     */
    private static final List<String> MODEL_CATALOG = List.of(
            // T2V (text-to-video)
            "MiniMax-Hailuo-2.3",
            "MiniMax-Hailuo-2.3-Fast",
            "MiniMax-Hailuo-02",
            // I2V (image-to-video)
            "I2V-01-Director",
            "I2V-01-live",
            "I2V-01"
    );

    /**
     * Resolve the MiniMax base URL from the system settings region. Package-private
     * for unit tests — the only branching point that needs verification.
     */
    static String resolveBaseUrl(SystemSettingsDTO config) {
        if (config != null && REGION_CN.equalsIgnoreCase(config.getMinimaxRegion())) {
            return BASE_URL_CN;
        }
        return BASE_URL_GLOBAL;
    }

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
                .models(MODEL_CATALOG)
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
            String baseUrl = resolveBaseUrl(config);
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

            HttpResponse response = HttpRequest.post(baseUrl + "/v1/video_generation")
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
                log.info("[MiniMax] Submitted task: {} (model={}, host={})", taskId, model, baseUrl);
                return VideoSubmitResult.success(taskId, id());
            } else {
                String errMsg = result.path("base_resp").path("status_msg").asText("未知错误");
                log.warn("[MiniMax] Submit failed (host={}): {}", baseUrl, errMsg);
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
            String baseUrl = resolveBaseUrl(config);

            HttpResponse response = HttpRequest.get(
                            baseUrl + "/v1/query/video_generation?task_id=" + providerTaskId)
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
                        videoUrl = resolveFileUrl(result.get("file_id").asText(), apiKey, baseUrl);
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
     * 通过 file_id 获取视频下载 URL。Region must match the host that produced
     * the file_id — otherwise the cross-host lookup 404s.
     */
    private String resolveFileUrl(String fileId, String apiKey, String baseUrl) {
        try {
            HttpResponse response = HttpRequest.get(
                            baseUrl + "/v1/files/retrieve?file_id=" + fileId)
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
