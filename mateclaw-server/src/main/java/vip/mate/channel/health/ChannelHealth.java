package vip.mate.channel.health;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-channel health snapshot.
 *
 * <p>Mirrors Spring Boot's {@code Health} object semantically (status +
 * details) but kept channel-domain-typed so we don't leak {@code actuator}
 * types into the channel API and so we can serialize directly into the
 * {@code /api/v1/channels/{id}/health} JSON response.
 *
 * <p>Status semantics:
 * <ul>
 *   <li>{@code UP}            — adapter started AND underlying transport
 *       is reachable (e.g. DingTalk Stream WebSocket connected, last
 *       activity within staleness threshold).</li>
 *   <li>{@code RECONNECTING}  — adapter is currently retrying after a
 *       transient failure; will return to UP if it recovers.</li>
 *   <li>{@code DOWN}          — adapter started but the transport is
 *       unhealthy / authentication failed / max retries exhausted.</li>
 *   <li>{@code OUT_OF_SERVICE}— adapter is not started (channel disabled
 *       or stopped administratively).</li>
 *   <li>{@code UNKNOWN}       — adapter starting up; transport state not
 *       yet determined.</li>
 * </ul>
 *
 * <p>This is the "single source of truth" the frontend "已连接" green dot
 * should bind to — not the {@code mate_channel.enabled} DB flag, which
 * only records the user's intent, not transport reality.
 */
@Getter
@RequiredArgsConstructor
public class ChannelHealth {

    public enum Status { UP, RECONNECTING, DOWN, OUT_OF_SERVICE, UNKNOWN }

    private final String channelType;
    private final Long channelId;
    private final Status status;
    private final String detail;
    private final Instant lastEventAt;
    private final Map<String, Object> extra;

    public static ChannelHealth up(String type, Long id, Instant lastEvent) {
        return new ChannelHealth(type, id, Status.UP, "active", lastEvent, Map.of());
    }

    public static ChannelHealth outOfService(String type, Long id) {
        return new ChannelHealth(type, id, Status.OUT_OF_SERVICE, "not started", null, Map.of());
    }

    public static ChannelHealth unknown(String type, Long id) {
        return new ChannelHealth(type, id, Status.UNKNOWN, "initializing", null, Map.of());
    }

    public static ChannelHealth down(String type, Long id, String reason, Instant lastEvent) {
        return new ChannelHealth(type, id, Status.DOWN, reason, lastEvent, Map.of());
    }

    public static ChannelHealth reconnecting(String type, Long id, String reason, Instant lastEvent) {
        return new ChannelHealth(type, id, Status.RECONNECTING, reason, lastEvent, Map.of());
    }

    /** Serialize to a JSON-friendly map for the REST endpoint. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channelType", channelType);
        m.put("channelId", channelId);
        m.put("status", status.name());
        if (detail != null) m.put("detail", detail);
        if (lastEventAt != null) m.put("lastEventAt", lastEventAt.toString());
        if (extra != null && !extra.isEmpty()) m.putAll(extra);
        return m;
    }
}
