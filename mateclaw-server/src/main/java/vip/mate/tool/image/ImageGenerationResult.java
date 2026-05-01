package vip.mate.tool.image;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 图片生成服务提交结果（面向 Tool 层）
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class ImageGenerationResult {

    /** 内部任务 ID（异步模式，供 Agent 查询状态用） */
    private String taskId;

    /** 处理该任务的 provider 名称 */
    private String providerName;

    /** 是否成功提交（异步）或生成完成（同步） */
    private boolean submitted;

    /** 同步模式：图片是否已生成完毕 */
    private boolean completed;

    /** 同步模式：生成的图片本地 serving URL 列表 */
    private List<String> imageUrls;

    /** 面向 Agent 的说明文本 */
    private String message;

    public static ImageGenerationResult asyncSuccess(String taskId, String providerName) {
        return ImageGenerationResult.builder()
                .taskId(taskId)
                .providerName(providerName)
                .submitted(true)
                .completed(false)
                // Format MUST keep `taskId=...` so the frontend reconnect detector
                // (useChat.ts TASK_ID_PATTERN) can extract it from the tool result.
                .message("图片生成任务已提交（taskId=" + taskId + ", provider=" + providerName + "）。预计 30 秒 - 2 分钟完成，完成后会自动显示在对话中。")
                .build();
    }

    public static ImageGenerationResult syncSuccess(String providerName, List<String> imageUrls) {
        return ImageGenerationResult.builder()
                .providerName(providerName)
                .submitted(true)
                .completed(true)
                .imageUrls(imageUrls)
                .message("图片已生成完毕，共 " + imageUrls.size() + " 张。")
                .build();
    }

    public static ImageGenerationResult failure(String message) {
        return ImageGenerationResult.builder()
                .submitted(false)
                .completed(false)
                .message(message)
                .build();
    }
}
