package vip.mate.tool.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.task.AsyncTaskService;
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.task.model.AsyncTaskInfo;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.nio.file.Path;
import java.util.List;

/**
 * 视频生成服务 — 统一入口，处理 provider 选择、参数归一化、fallback、异步提交
 * <p>
 * 对应 OpenClaw 的 video-generation/runtime.ts
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationService {

    private final SystemSettingService systemSettingService;
    private final VideoProviderRegistry providerRegistry;
    private final AsyncTaskService asyncTaskService;
    private final ConversationService conversationService;
    private final VideoFileDownloader fileDownloader;
    private final ObjectMapper objectMapper;

    private static final String TASK_TYPE = "video_generation";

    /**
     * 提交视频生成任务（异步）
     */
    public VideoGenerationResult submitGeneration(VideoGenerationRequest request,
                                                    String conversationId,
                                                    String createdBy) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        // 1. 检查视频功能是否启用
        if (!Boolean.TRUE.equals(config.getVideoEnabled())) {
            return VideoGenerationResult.failure("视频生成功能未启用，请在系统设置中开启");
        }

        // 2. 模式推断
        if (request.getMode() == null) {
            request.setMode(inferMode(request));
        }

        // 3. 参数归一化
        normalizeRequest(request);

        // 4. Provider 选择
        VideoProviderRegistry.ResolvedProvider resolved =
                providerRegistry.resolve(config, request.getMode());
        if (resolved == null) {
            return VideoGenerationResult.failure(
                    "没有可用的视频生成 Provider，请在系统设置中配置（支持 DashScope、智谱、fal.ai、可灵）");
        }

        // 5. 提交（含 fallback）
        return submitWithFallback(request, config, resolved, conversationId, createdBy);
    }

    /**
     * 查询任务状态
     */
    public AsyncTaskInfo checkTaskStatus(String taskId) {
        return asyncTaskService.getTaskInfo(taskId);
    }

    // ==================== 内部逻辑 ====================

    private VideoGenerationResult submitWithFallback(VideoGenerationRequest request,
                                                       SystemSettingsDTO config,
                                                       VideoProviderRegistry.ResolvedProvider primary,
                                                       String conversationId,
                                                       String createdBy) {
        // 尝试 primary（先按 provider 能力归一化参数）
        normalizeForProvider(request, primary.provider());
        VideoSubmitResult submitResult = primary.provider().submit(request, config);
        if (submitResult.isAccepted()) {
            return createAsyncTask(submitResult, request, conversationId, createdBy, config);
        }

        // Fallback（收集所有尝试的错误信息）
        List<String> attemptErrors = new java.util.ArrayList<>();
        attemptErrors.add(primary.provider().id() + ": " + submitResult.getErrorMessage());

        if (Boolean.TRUE.equals(config.getVideoFallbackEnabled())) {
            List<VideoGenerationProvider> fallbacks =
                    providerRegistry.fallbackCandidates(config, request.getMode(), primary.provider().id());
            for (VideoGenerationProvider fb : fallbacks) {
                log.info("[VideoGen] Trying fallback provider: {}", fb.id());
                normalizeForProvider(request, fb);
                submitResult = fb.submit(request, config);
                if (submitResult.isAccepted()) {
                    return createAsyncTask(submitResult, request, conversationId, createdBy, config);
                }
                attemptErrors.add(fb.id() + ": " + submitResult.getErrorMessage());
                log.warn("[VideoGen] Fallback provider {} failed: {}", fb.id(), submitResult.getErrorMessage());
            }
        }

        return VideoGenerationResult.failure(
                "所有 Provider 均提交失败\n" + String.join("\n", attemptErrors));
    }

    private VideoGenerationResult createAsyncTask(VideoSubmitResult submitResult,
                                                    VideoGenerationRequest request,
                                                    String conversationId,
                                                    String createdBy,
                                                    SystemSettingsDTO config) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            AsyncTaskEntity task = asyncTaskService.createTask(
                    TASK_TYPE, conversationId, null,
                    submitResult.getProviderName(),
                    submitResult.getProviderTaskId(),
                    requestJson, createdBy);

            // 获取 provider 引用用于轮询
            VideoGenerationProvider provider = providerRegistry.getById(submitResult.getProviderName());
            if (provider == null) {
                return VideoGenerationResult.failure("Provider 不存在: " + submitResult.getProviderName());
            }

            // 启动轮询（每次轮询重新获取配置，避免 API Key 轮换后使用过期凭证）
            asyncTaskService.startPolling(
                    task.getTaskId(),
                    providerTaskId -> provider.checkStatus(providerTaskId, systemSettingService.getAllSettings()),
                    (completedTask, pollResult) -> handleCompletion(completedTask, pollResult)
            );

            return VideoGenerationResult.success(task.getTaskId(), submitResult.getProviderName());
        } catch (Exception e) {
            log.error("[VideoGen] Failed to create async task: {}", e.getMessage(), e);
            return VideoGenerationResult.failure("创建任务失败: " + e.getMessage());
        }
    }

    /**
     * 任务完成时的回写逻辑：下载视频 → 保存消息 → 广播 SSE
     */
    private void handleCompletion(AsyncTaskEntity task, TaskPollResult result) {
        // The conversation may have been deleted while the poller was running.
        // Gate every post-completion side effect — file write, message save,
        // success/failure broadcast — so we never write to a tombstoned
        // conversation regardless of which sub-branch we'd take.
        if (asyncTaskService.isConversationCanceled(task.getConversationId())) {
            log.info("[VideoGen] Task {} (success={}) aborted: conversation {} was deleted",
                    task.getTaskId(), result.succeeded(), task.getConversationId());
            return;
        }

        if (result.succeeded()) {
            try {
                String videoUrl = result.videoUrl();
                if (videoUrl == null) {
                    log.warn("[VideoGen] Task {} succeeded but no video URL", task.getTaskId());
                    asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                            false, null, "视频生成成功但未返回视频 URL");
                    return;
                }

                // 下载视频到本地
                Path localPath = fileDownloader.download(videoUrl, task.getConversationId(), task.getTaskId());
                String servingUrl = fileDownloader.toServingUrl(task.getConversationId(), localPath);

                // 保存 assistant 消息（含 video content part）
                MessageContentPart videoPart = MessageContentPart.video(null, localPath.getFileName().toString());
                videoPart.setFileUrl(servingUrl);
                videoPart.setContentType("video/mp4");

                conversationService.saveMessage(
                        task.getConversationId(), "assistant",
                        "视频已生成完毕",
                        List.of(videoPart), "completed");

                // SSE 广播
                asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                        true, servingUrl, null);

                log.info("[VideoGen] Task {} completed, video saved: {}", task.getTaskId(), servingUrl);
            } catch (Exception e) {
                log.error("[VideoGen] Completion handling failed for task {}: {}",
                        task.getTaskId(), e.getMessage(), e);
                if (asyncTaskService.isConversationCanceled(task.getConversationId())) {
                    log.info("[VideoGen] Skipping failure broadcast for deleted conversation {}",
                            task.getConversationId());
                    return;
                }
                asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                        false, null, "视频下载或保存失败: " + e.getMessage());
            }
        } else {
            // 失败
            asyncTaskService.broadcastTaskEvent(task, "async_task_completed",
                    false, null, result.errorMessage());
            log.warn("[VideoGen] Task {} failed: {}", task.getTaskId(), result.errorMessage());
        }
    }

    private VideoCapability inferMode(VideoGenerationRequest request) {
        if (request.getVideoUrl() != null && !request.getVideoUrl().isBlank()) {
            return VideoCapability.VIDEO_TO_VIDEO;
        }
        if (request.getImageUrl() != null && !request.getImageUrl().isBlank()) {
            return VideoCapability.IMAGE_TO_VIDEO;
        }
        return VideoCapability.GENERATE;
    }

    private void normalizeRequest(VideoGenerationRequest request) {
        if (request.getAspectRatio() == null || request.getAspectRatio().isBlank()) {
            request.setAspectRatio("16:9");
        }
        if (request.getDurationSeconds() == null) {
            request.setDurationSeconds(5);
        }
    }

    /**
     * 根据 provider 能力做参数就近归一化（借鉴 OpenClaw 的 resolveVideoGenerationOverrides）
     */
    private void normalizeForProvider(VideoGenerationRequest request, VideoGenerationProvider provider) {
        VideoProviderCapabilities caps = provider.detailedCapabilities();
        if (caps == null) return;

        // aspectRatio 就近匹配
        request.setAspectRatio(caps.normalizeAspectRatio(request.getAspectRatio()));

        // duration 就近匹配
        if (request.getDurationSeconds() != null) {
            request.setDurationSeconds(caps.normalizeDuration(request.getDurationSeconds()));
        }
    }
}
