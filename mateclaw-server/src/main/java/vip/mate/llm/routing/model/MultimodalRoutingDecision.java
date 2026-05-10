package vip.mate.llm.routing.model;

import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService.Modality;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of routing a single user turn that may carry image / video / audio attachments.
 *
 * <p>The decision is purely descriptive — execution (loading captions, mutating
 * the user message) happens in the caller. Treat instances as immutable; the
 * static factories cover the only valid shapes.
 */
public record MultimodalRoutingDecision(
        Strategy strategy,
        ModelConfigEntity sidecarModel,
        Set<Modality> requiredModalities,
        Set<Modality> primaryMissing,
        List<SkippedAttachment> skipped
) {

    public enum Strategy {
        /** Primary model handles the turn directly (or no attachments at all). */
        NONE,
        /** A separate vision/video model captions attachments; primary stays. */
        SIDECAR,
        /** Reserved: switch the whole turn to a multimodal model. v1 does not emit. */
        NATIVE
    }

    public record SkippedAttachment(String type, String fileName, String reason) {}

    public static MultimodalRoutingDecision none() {
        return new MultimodalRoutingDecision(
                Strategy.NONE, null, Set.of(), Set.of(), List.of());
    }

    public static MultimodalRoutingDecision noneWithSkipped(
            Set<Modality> required,
            Set<Modality> missing,
            List<SkippedAttachment> skipped) {
        return new MultimodalRoutingDecision(
                Strategy.NONE, null, required, missing, skipped);
    }

    public static MultimodalRoutingDecision sidecar(
            ModelConfigEntity sidecarModel,
            Set<Modality> required,
            Set<Modality> missing) {
        return new MultimodalRoutingDecision(
                Strategy.SIDECAR, sidecarModel, required, missing, List.of());
    }

    /**
     * Serialize to a flat map for emission as a graph event payload.
     * Only includes keys present in this decision so the resulting
     * {@code metadata.routing} JSON stays compact for the chat UI.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy", strategy.name().toLowerCase());
        if (sidecarModel != null) {
            m.put("sidecarModelId", sidecarModel.getId());
            m.put("sidecarModel", sidecarModel.getModelName());
            m.put("sidecarProvider", sidecarModel.getProvider());
        }
        if (!requiredModalities.isEmpty()) {
            m.put("requiredModalities", requiredModalities.stream().map(Enum::name).toList());
        }
        if (!primaryMissing.isEmpty()) {
            m.put("primaryMissing", primaryMissing.stream().map(Enum::name).toList());
        }
        if (!skipped.isEmpty()) {
            m.put("skipped", skipped.stream().map(s -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", s.type());
                if (s.fileName() != null) entry.put("fileName", s.fileName());
                entry.put("reason", s.reason());
                return entry;
            }).toList());
        }
        return Collections.unmodifiableMap(m);
    }
}
