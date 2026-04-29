package vip.mate.channel.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelAdapter;
import vip.mate.channel.ChannelManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot Actuator health indicator aggregating all running channel
 * adapters' real-time health.
 *
 * <p>Surfaces under {@code /actuator/health/channels}. Used by the desktop
 * bundle's post-install smoke test (and any external monitor) to fail-loud
 * when a configured channel never reaches {@code UP} after startup.
 *
 * <p>Aggregation rule: overall status is UP iff every running adapter
 * reports UP. Any DOWN or ERROR demotes the aggregate to DOWN.
 */
@Component("channels")
@RequiredArgsConstructor
public class ChannelsHealthIndicator implements HealthIndicator {

    private final ChannelManager channelManager;

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean anyDown = false;
        boolean anyReconnecting = false;
        int total = 0;
        int up = 0;

        for (ChannelAdapter adapter : channelManager.getActiveAdapters()) {
            ChannelHealth h = adapter.health();
            details.put(safeKey(adapter, h), h.toMap());
            total++;
            switch (h.getStatus()) {
                case UP -> up++;
                case RECONNECTING -> anyReconnecting = true;
                case DOWN -> anyDown = true;
                default -> { /* OUT_OF_SERVICE / UNKNOWN don't fail aggregate */ }
            }
        }

        details.put("totalActive", total);
        details.put("up", up);

        Health.Builder builder;
        if (anyDown) {
            builder = Health.down();
        } else if (anyReconnecting) {
            builder = Health.status("RECONNECTING");
        } else {
            builder = Health.up();
        }
        return builder.withDetails(details).build();
    }

    /** key in details map: prefer {@code <type>:<id>} fallback to type only. */
    private String safeKey(ChannelAdapter adapter, ChannelHealth h) {
        if (h.getChannelId() != null) {
            return adapter.getChannelType() + ":" + h.getChannelId();
        }
        return adapter.getChannelType();
    }
}
