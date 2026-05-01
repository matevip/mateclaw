package vip.mate.tool.video;

import lombok.Builder;
import lombok.Data;

/**
 * 视频生成服务提交结果（面向 Tool 层）
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class VideoGenerationResult {

    /** 内部任务 ID（供 Agent 查询状态用） */
    private String taskId;

    /** 处理该任务的 provider 名称 */
    private String providerName;

    /** 状态描述 */
    private String status;

    /** 面向 Agent 的说明文本 */
    private String message;

    /** 是否成功提交 */
    private boolean submitted;

    public static VideoGenerationResult success(String taskId, String providerName) {
        return VideoGenerationResult.builder()
                .taskId(taskId)
                .providerName(providerName)
                .status("submitted")
                .submitted(true)
                // Format MUST keep `taskId=...` so the frontend reconnect detector
                // (useChat.ts TASK_ID_PATTERN) can extract it from the tool result.
                .message("视频生成任务已提交（taskId=" + taskId + ", provider=" + providerName + "）。预计 1-5 分钟完成，完成后会自动显示在对话中。")
                .build();
    }

    public static VideoGenerationResult failure(String message) {
        return VideoGenerationResult.builder()
                .submitted(false)
                .status("failed")
                .message(message)
                .build();
    }
}
