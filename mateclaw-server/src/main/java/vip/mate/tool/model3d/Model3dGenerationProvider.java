package vip.mate.tool.model3d;

import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.task.AsyncTaskService.TaskPollResult;

import java.util.Set;

/**
 * 3D-model generation provider. Mirrors
 * {@link vip.mate.tool.video.VideoGenerationProvider} — submit returns a task id,
 * checkStatus polls for terminal state.
 */
public interface Model3dGenerationProvider {

    /** Unique provider id, e.g. {@code "hunyuan-3d"}. */
    String id();

    /** Display name. */
    String label();

    /** Whether the provider needs an API key. */
    boolean requiresCredential();

    /** Auto-detect ranking — lower wins when no provider is explicitly chosen. */
    int autoDetectOrder();

    /** Modes the provider supports. */
    Set<Model3dCapability> capabilities();

    /** Detailed capability declaration (formats, models, etc.). */
    Model3dProviderCapabilities detailedCapabilities();

    /** Whether the provider is currently usable (credentials configured, etc.). */
    boolean isAvailable(SystemSettingsDTO config);

    /**
     * Submit a 3D-model generation job (async, non-blocking).
     *
     * @param request unified request
     * @param config  system configuration
     * @return submit result with provider task id
     */
    Model3dSubmitResult submit(Model3dGenerationRequest request, SystemSettingsDTO config);

    /**
     * Poll the provider for the current job state.
     *
     * @param providerTaskId provider-issued task id from {@link #submit}
     * @param config         system configuration
     * @return poll result; {@code null} signals "no change yet, retry later"
     */
    TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config);
}
