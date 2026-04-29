package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.task.AsyncTaskService;
import vip.mate.task.model.AsyncTaskInfo;
import vip.mate.tool.image.*;

import java.util.List;
import java.util.StringJoiner;

/**
 * 图片生成工具 — Agent 可调用的 @Tool，提交图片生成任务
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenerateTool {

    private final ImageGenerationService imageGenerationService;
    private final ImageProviderRegistry providerRegistry;
    private final SystemSettingService systemSettingService;
    private final AsyncTaskService asyncTaskService;

    @vip.mate.tool.ConcurrencyUnsafe("creates async tasks and persists generated artifacts; provider rate limits also forbid parallel calls")
    @Tool(description = "Image generation tool. Supports actions: generate (default), list (show available providers), "
            + "status (check task status). Some providers are async (30s-2min), results auto-displayed in conversation.")
    public String image_generate(
            @ToolParam(description = "Action type: generate, list, status. Default: generate", required = false) String action,
            @ToolParam(description = "Image content description, be detailed (required for generate)", required = false) String prompt,
            @ToolParam(description = "Image size: 1024x1024 / 1024x1792 / 1792x1024", required = false) String size,
            @ToolParam(description = "Aspect ratio: 1:1 / 16:9 / 9:16, default 1:1", required = false) String aspectRatio,
            @ToolParam(description = "Generation count (1-4), default 1", required = false) Integer count,
            @ToolParam(description = "Model name (optional)", required = false) String model,
            @ToolParam(description = "Task ID to check status (for status action)", required = false) String taskId,
            // RFC-063r §2.5: ToolContext is hidden from the LLM by JsonSchemaGenerator.
            @Nullable ToolContext ctx
    ) {
        String normalizedAction = (action == null || action.isBlank()) ? "generate" : action.trim().toLowerCase();

        return switch (normalizedAction) {
            case "list" -> handleListAction();
            case "status" -> handleStatusAction(taskId, ctx);
            default -> handleGenerateAction(prompt, size, aspectRatio, count, model, ctx);
        };
    }

    // ==================== action=list ====================

    private String handleListAction() {
        SystemSettingsDTO config = systemSettingService.getAllSettings();
        List<ImageGenerationProvider> providers = providerRegistry.allSorted();

        if (providers.isEmpty()) {
            return "当前没有注册的图片生成 Provider。";
        }

        StringJoiner sb = new StringJoiner("\n\n");
        sb.add("## 可用的图片生成 Provider\n");

        for (ImageGenerationProvider p : providers) {
            boolean available = p.isAvailable(config);
            ImageProviderCapabilities caps = p.detailedCapabilities();

            StringJoiner entry = new StringJoiner("\n");
            entry.add("### " + p.label() + " (" + p.id() + ") " + (available ? "[已配置]" : "[未配置]"));

            if (caps != null) {
                if (caps.getModels() != null && !caps.getModels().isEmpty()) {
                    entry.add("- 模型: " + String.join(", ", caps.getModels()));
                }
                entry.add("- 支持尺寸: " + String.join(", ", caps.getSupportedSizes()));
                entry.add("- 最大数量: " + caps.getMaxCount());
            }
            sb.add(entry.toString());
        }
        return sb.toString();
    }

    // ==================== action=status ====================

    private String handleStatusAction(String taskId, @Nullable ToolContext ctx) {
        String conversationId = ToolExecutionContext.conversationId(ctx);

        if (taskId != null && !taskId.isBlank()) {
            AsyncTaskInfo info = imageGenerationService.checkTaskStatus(taskId);
            if (info == null) {
                return "未找到任务 ID: " + taskId;
            }
            return formatTaskStatus(info);
        }

        if (conversationId == null) {
            return "无法获取当前会话信息";
        }
        List<AsyncTaskInfo> activeTasks = asyncTaskService.listActiveTasks(conversationId);
        List<AsyncTaskInfo> imageTasks = activeTasks.stream()
                .filter(t -> "image_generation".equals(t.getTaskType()))
                .toList();
        if (imageTasks.isEmpty()) {
            return "当前会话没有进行中的图片生成任务。";
        }

        StringJoiner sb = new StringJoiner("\n");
        sb.add("当前会话有 " + imageTasks.size() + " 个进行中的任务：");
        for (AsyncTaskInfo task : imageTasks) {
            sb.add("- 任务 " + task.getTaskId() + ": " + formatTaskStatus(task));
        }
        return sb.toString();
    }

    // ==================== action=generate ====================

    private String handleGenerateAction(String prompt, String size, String aspectRatio,
                                         Integer count, String model, @Nullable ToolContext ctx) {
        String conversationId = ToolExecutionContext.conversationId(ctx);
        String username = ToolExecutionContext.username(ctx);

        if (conversationId == null || conversationId.isBlank()) {
            return "错误：无法获取当前会话信息，请重试";
        }

        if (prompt == null || prompt.isBlank()) {
            return "错误：prompt 为必填参数，请描述你想要生成的图片内容";
        }

        ImageGenerationRequest request = ImageGenerationRequest.builder()
                .prompt(prompt)
                .size(size)
                .aspectRatio(aspectRatio != null ? aspectRatio : "1:1")
                .count(count != null ? count : 1)
                .model(model)
                .build();

        ImageGenerationResult result = imageGenerationService.submitGeneration(
                request, conversationId, username != null ? username : "system");

        if (result.isCompleted()) {
            // 同步模式：图片已生成
            return result.getMessage();
        } else if (result.isSubmitted()) {
            // 异步模式：已提交
            return result.getMessage();
        } else {
            return "图片生成失败：" + result.getMessage();
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
            case "succeeded" -> "已完成，图片已显示在对话中";
            case "failed" -> "失败：" + (info.getErrorMessage() != null ? info.getErrorMessage() : "未知错误");
            default -> "状态: " + info.getStatus();
        };
    }
}
