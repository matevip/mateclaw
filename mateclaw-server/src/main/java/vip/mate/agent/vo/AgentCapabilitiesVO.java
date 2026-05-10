package vip.mate.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Lightweight capability snapshot for an agent — answers two questions
 * the chat console needs synchronously while the user is composing a message:
 *
 * <ul>
 *   <li>What modalities does the agent's primary model support? (drives the
 *       attachment routing hint above the input box.)</li>
 *   <li>Are sidecar models configured at the system level? (drives the
 *       "configure a vision model" CTA when a user attaches an image to an
 *       agent whose primary model can't process it.)</li>
 * </ul>
 *
 * <p>Returned by {@code GET /api/v1/agents/{id}/capabilities}. Computed on
 * each request — cheap because everything is cached service-side and we only
 * read at most three rows. Not persisted on {@code mate_agent}; sidecar
 * configuration is system-wide and {@code modelCapabilities} is derived from
 * {@code mate_model_config.modalities}.
 */
@Data
@Builder
@AllArgsConstructor
public class AgentCapabilitiesVO {
    private Long agentId;
    private String modelName;
    private String providerId;
    /** Resolved modality set: any of {@code TEXT / VISION / VIDEO / AUDIO}. */
    private List<String> modalities;
    /** System-level vision sidecar model id, null when not configured. */
    private Long defaultVisionModelId;
    /** Display name of the configured vision sidecar (provider/modelName), null when not configured. */
    private String defaultVisionModelLabel;
    /** System-level video sidecar model id (reserved in v1; never wired). */
    private Long defaultVideoModelId;
    private String defaultVideoModelLabel;
}
