package vip.mate.tool.model3d;

import lombok.Builder;
import lombok.Data;

/**
 * Service-layer outcome of {@link Model3dGenerationService#submitGeneration}.
 * Mirrors {@link vip.mate.tool.video.VideoGenerationResult}.
 */
@Data
@Builder
public class Model3dGenerationResult {

    /** Internal task id that the agent can use to query status. */
    private String taskId;

    /** Provider that handled the submission. */
    private String providerName;

    /** Status string ("submitted" / "failed"). */
    private String status;

    /** Human-readable text returned to the LLM tool caller. */
    private String message;

    /** Whether submission succeeded. */
    private boolean submitted;

    public static Model3dGenerationResult success(String taskId, String providerName) {
        return Model3dGenerationResult.builder()
                .taskId(taskId)
                .providerName(providerName)
                .status("submitted")
                .submitted(true)
                // Format MUST keep `taskId=...` so the frontend reconnect detector
                // (useChat.ts TASK_ID_PATTERNS) can extract it from the tool result.
                .message("3D 模型生成任务已提交（taskId=" + taskId + ", provider=" + providerName + "）。预计 1-3 分钟完成，完成后会自动显示在对话中。")
                .build();
    }

    public static Model3dGenerationResult failure(String message) {
        return Model3dGenerationResult.builder()
                .submitted(false)
                .status("failed")
                .message(message)
                .build();
    }
}
