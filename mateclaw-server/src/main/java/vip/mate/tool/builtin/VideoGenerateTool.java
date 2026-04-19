package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.task.AsyncTaskService;
import vip.mate.task.model.AsyncTaskInfo;
import vip.mate.tool.video.*;

import java.util.List;
import java.util.StringJoiner;

/**
 * 视频生成工具 — Agent 可调用的 @Tool，提交异步视频生成任务
 * <p>
 * 借鉴 OpenClaw 的 video-generate-tool.ts 设计，支持 action=generate/list/status，
 * 以及 session 级重复提交防护。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoGenerateTool {

    private final VideoGenerationService videoGenerationService;
    private final VideoProviderRegistry providerRegistry;
    private final SystemSettingService systemSettingService;
    private final AsyncTaskService asyncTaskService;

    @vip.mate.tool.ConcurrencyUnsafe("creates async tasks and persists generated artifacts; provider rate limits also forbid parallel calls")
    @Tool(description = "视频生成工具，支持以下 action：\n"
            + "- generate（默认）：生成视频。提供 prompt 描述视频内容，可选 aspectRatio/duration/imageUrl/model\n"
            + "- list：列出所有可用的视频 Provider 及其支持的模型和能力\n"
            + "- status：查看当前会话中正在进行的视频生成任务状态\n"
            + "视频生成是异步过程（1-5 分钟），完成后自动显示在对话中。")
    public String video_generate(
            @ToolParam(description = "操作类型: generate（生成视频）、list（列出可用 Provider）、status（查看任务状态），默认 generate", required = false) String action,
            @ToolParam(description = "视频内容描述，尽量详细（generate 时必填）", required = false) String prompt,
            @ToolParam(description = "画面比例: 16:9 / 9:16 / 1:1，默认 16:9", required = false) String aspectRatio,
            @ToolParam(description = "视频时长（秒），如 5 或 10，默认 5", required = false) Integer duration,
            @ToolParam(description = "参考图片 URL（图生视频模式）", required = false) String imageUrl,
            @ToolParam(description = "指定模型名称（可选）", required = false) String model,
            @ToolParam(description = "查询指定任务 ID 的状态（status 模式时使用）", required = false) String taskId
    ) {
        // 路由 action
        String normalizedAction = (action == null || action.isBlank()) ? "generate" : action.trim().toLowerCase();

        return switch (normalizedAction) {
            case "list" -> handleListAction();
            case "status" -> handleStatusAction(taskId);
            default -> handleGenerateAction(prompt, aspectRatio, duration, imageUrl, model);
        };
    }

    // ==================== action=list ====================

    private String handleListAction() {
        SystemSettingsDTO config = systemSettingService.getAllSettings();
        List<VideoGenerationProvider> providers = providerRegistry.allSorted();

        if (providers.isEmpty()) {
            return "当前没有注册的视频生成 Provider。";
        }

        StringJoiner sb = new StringJoiner("\n\n");
        sb.add("## 可用的视频生成 Provider\n");

        for (VideoGenerationProvider p : providers) {
            boolean available = p.isAvailable(config);
            VideoProviderCapabilities caps = p.detailedCapabilities();

            StringJoiner entry = new StringJoiner("\n");
            entry.add("### " + p.label() + " (" + p.id() + ") " + (available ? "[已配置]" : "[未配置]"));

            if (caps != null) {
                entry.add("- 模式: " + caps.getModes());
                if (caps.getModels() != null && !caps.getModels().isEmpty()) {
                    entry.add("- 模型: " + String.join(", ", caps.getModels()));
                }
                entry.add("- 画面比例: " + String.join(", ", caps.getAspectRatios()));
                entry.add("- 支持时长: " + caps.getSupportedDurations() + " 秒");
            }
            sb.add(entry.toString());
        }
        return sb.toString();
    }

    // ==================== action=status ====================

    private String handleStatusAction(String taskId) {
        String conversationId = ToolExecutionContext.conversationId();

        // 指定 taskId 查询
        if (taskId != null && !taskId.isBlank()) {
            AsyncTaskInfo info = videoGenerationService.checkTaskStatus(taskId);
            if (info == null) {
                return "未找到任务 ID: " + taskId;
            }
            return formatTaskStatus(info);
        }

        // 查询当前会话的所有活跃任务
        if (conversationId == null) {
            return "无法获取当前会话信息";
        }
        List<AsyncTaskInfo> activeTasks = asyncTaskService.listActiveTasks(conversationId);
        if (activeTasks.isEmpty()) {
            return "当前会话没有进行中的视频生成任务。";
        }

        StringJoiner sb = new StringJoiner("\n");
        sb.add("当前会话有 " + activeTasks.size() + " 个进行中的任务：");
        for (AsyncTaskInfo task : activeTasks) {
            sb.add("- 任务 " + task.getTaskId() + ": " + formatTaskStatus(task));
        }
        return sb.toString();
    }

    // ==================== action=generate ====================

    private String handleGenerateAction(String prompt, String aspectRatio, Integer duration,
                                         String imageUrl, String model) {
        String conversationId = ToolExecutionContext.conversationId();
        String username = ToolExecutionContext.username();

        if (conversationId == null || conversationId.isBlank()) {
            return "错误：无法获取当前会话信息，请重试";
        }

        if (prompt == null || prompt.isBlank()) {
            return "错误：prompt 为必填参数，请描述你想要生成的视频内容";
        }

        // Session 级重复提交防护（借鉴 OpenClaw 的 duplicateGuard）
        List<AsyncTaskInfo> activeTasks = asyncTaskService.listActiveTasks(conversationId);
        long videoTasks = activeTasks.stream()
                .filter(t -> "video_generation".equals(t.getTaskType()))
                .count();
        if (videoTasks > 0) {
            AsyncTaskInfo existing = activeTasks.stream()
                    .filter(t -> "video_generation".equals(t.getTaskType()))
                    .findFirst().orElse(null);
            return "当前会话已有一个视频生成任务正在进行中（任务 ID: "
                    + (existing != null ? existing.getTaskId() : "unknown")
                    + "）。请等待完成后再提交新任务，或使用 action=status 查看进度。";
        }

        VideoGenerationRequest request = VideoGenerationRequest.builder()
                .prompt(prompt)
                .aspectRatio(aspectRatio)
                .durationSeconds(duration)
                .imageUrl(imageUrl)
                .model(model)
                .build();

        VideoGenerationResult result = videoGenerationService.submitGeneration(
                request, conversationId, username != null ? username : "system");

        if (result.isSubmitted()) {
            return result.getMessage();
        } else {
            return "视频生成失败：" + result.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    private String formatTaskStatus(AsyncTaskInfo info) {
        return switch (info.getStatus()) {
            case "pending" -> "排队中，请稍候...";
            case "running" -> {
                String progressStr = info.getProgress() != null && info.getProgress() > 0
                        ? "（进度: " + info.getProgress() + "%）" : "";
                yield "生成中" + progressStr + "（" + info.getProviderName() + "）";
            }
            case "succeeded" -> "已完成，视频已显示在对话中";
            case "failed" -> "失败：" + (info.getErrorMessage() != null ? info.getErrorMessage() : "未知错误");
            default -> "状态: " + info.getStatus();
        };
    }
}
