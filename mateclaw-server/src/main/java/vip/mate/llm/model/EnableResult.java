package vip.mate.llm.model;

/**
 * RFC-074: payload for {@code POST /models/{id}/enable} and {@code .../disable}.
 *
 * <p>{@code defaultSwitched} is true when the disable action invalidated the
 * current default model and the service auto-promoted a replacement. The
 * frontend uses this to fire a toast like "Switched default model to X" so
 * the user isn't surprised next time they send a message.</p>
 *
 * <p>{@code newDefaultProviderId} / {@code newDefaultModel} are populated
 * only when {@code defaultSwitched} is true; both null otherwise.</p>
 */
public record EnableResult(boolean defaultSwitched,
                           String newDefaultProviderId,
                           String newDefaultModel) {

    public static EnableResult unchanged() {
        return new EnableResult(false, null, null);
    }

    public static EnableResult switched(String providerId, String modelName) {
        return new EnableResult(true, providerId, modelName);
    }
}
