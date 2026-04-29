package vip.mate.cron.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.lang.Nullable;
import vip.mate.agent.context.ChannelTarget;

/**
 * RFC-063r §2.9: per-cron-job delivery configuration. Persisted as a JSON
 * column on {@code mate_cron_job.delivery_config} via MyBatis Plus
 * {@code JacksonTypeHandler}. Mirrors {@link ChannelTarget} but lives in the
 * cron module so {@code CronJobEntity} doesn't need to depend on the channel
 * value object directly.
 *
 * <p>{@link JsonIgnoreProperties#ignoreUnknown()} keeps deserialization
 * forward-compatible when older rows are read after a column-add upgrade.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryConfig(
        @Nullable String targetId,
        @Nullable String threadId,
        @Nullable String accountId
) {

    /** Convert from the {@link ChannelTarget} carried on a {@code ChatOrigin}. */
    public static DeliveryConfig from(@Nullable ChannelTarget t) {
        if (t == null) return null;
        return new DeliveryConfig(t.targetId(), t.threadId(), t.accountId());
    }

    /** Convert back to a {@link ChannelTarget} for ChatOrigin reconstruction. */
    public ChannelTarget toChannelTarget() {
        return new ChannelTarget(targetId, threadId, accountId);
    }
}
