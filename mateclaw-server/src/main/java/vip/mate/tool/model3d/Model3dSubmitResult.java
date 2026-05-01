package vip.mate.tool.model3d;

import lombok.Builder;
import lombok.Data;

/**
 * Provider-side submission result for a 3D-model generation job.
 * Mirrors {@link vip.mate.tool.video.VideoSubmitResult}.
 */
@Data
@Builder
public class Model3dSubmitResult {

    /** Provider-issued task id (used to poll status later). */
    private String providerTaskId;

    /** Provider id ({@code hunyuan-3d}, future expansions). */
    private String providerName;

    /** Whether the provider accepted the submission. */
    private boolean accepted;

    /** Error message, populated only when {@code accepted=false}. */
    private String errorMessage;

    public static Model3dSubmitResult success(String providerTaskId, String providerName) {
        return Model3dSubmitResult.builder()
                .providerTaskId(providerTaskId)
                .providerName(providerName)
                .accepted(true)
                .build();
    }

    public static Model3dSubmitResult failure(String providerName, String errorMessage) {
        return Model3dSubmitResult.builder()
                .providerName(providerName)
                .accepted(false)
                .errorMessage(errorMessage)
                .build();
    }
}
