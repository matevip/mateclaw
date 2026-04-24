package vip.mate.llm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfoDTO {
    private String id;
    private String name;

    /**
     * Discovery probe result. true = passed runtime-protocol ping test,
     * false = ping failed (listed by provider but unusable at runtime,
     * e.g. DashScope compatible-mode may list models the native SDK rejects).
     * null = not probed (probe disabled or still pending).
     */
    private Boolean probeOk;

    /** Reason text when probeOk=false (short, suitable for UI badge tooltip) */
    private String probeError;

    /**
     * RFC-049 PR-1-UI: whether this model's {@code ModelFamily} accepts the
     * {@code reasoning_effort} parameter. Derived from the model name on the
     * server side so the frontend can gray out the "thinking depth" selector
     * for chat-type models that don't support thinking — a product contract,
     * not a runtime toggle.
     *
     * <p>{@code true} only for the OpenAI reasoning family (gpt-5*, o1*, o3*, o4*).
     */
    private boolean supportsReasoningEffort;

    public ModelInfoDTO(String id, String name) {
        this.id = id;
        this.name = name;
        // RFC-049 PR-1-UI: derived from id (the model name) so every construction
        // site populates the capability consistently.
        this.supportsReasoningEffort = ModelFamily.detect(id).supportsReasoningEffort();
    }
}
