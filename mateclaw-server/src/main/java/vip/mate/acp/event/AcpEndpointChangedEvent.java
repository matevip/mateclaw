package vip.mate.acp.event;

/**
 * Lifecycle event for ACP endpoint rows.
 *
 * <p>Published by {@code AcpEndpointService} whenever a row is created,
 * updated, toggled, or deleted. Listened to by
 * {@code AcpSkillBridge} so it can re-sync the auto-bridged virtual
 * skill cards and their wrapper tool registrations without a full
 * application restart.
 *
 * <p>Mirrors the {@code SkillWorkspaceEvent} pattern — a small immutable
 * record carrying just enough context for listeners to fan out.
 */
public record AcpEndpointChangedEvent(Long endpointId, String name, Type type) {

    public enum Type {
        /** Row inserted. */
        CREATED,
        /** Row attributes updated (command/args/env/etc.). */
        UPDATED,
        /** {@code enabled} flag flipped. */
        TOGGLED,
        /** Row deleted. */
        DELETED
    }
}
