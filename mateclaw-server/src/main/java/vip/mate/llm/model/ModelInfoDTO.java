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

    public ModelInfoDTO(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
