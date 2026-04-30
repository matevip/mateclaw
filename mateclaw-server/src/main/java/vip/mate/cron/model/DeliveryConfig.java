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
        @Nullable String accountId,
        /**
         * The IM senderId of the user who created this cron job. Used by
         * {@code CronConversationResolver} to find that user's existing
         * channel session — matching by {@code targetId} alone is fragile
         * because adapters that use a {@code replyToken} (DingTalk
         * sessionWebhook etc.) store the token as session.targetId while
         * the cron tool captures the message {@code chatId/senderId} as
         * deliveryConfig.targetId. The two never match, the lookup always
         * misses, and IM cron output never reaches the channel mirror
         * conversation. Carrying senderId fixes that without coupling the
         * resolver to per-channel reply-target conventions.
         * <p>Nullable for backwards compat with rows written before this
         * field was added (V62 baseline).
         */
        @Nullable String userId
) {

    /** 3-arg legacy constructor preserved so older deserialized rows still work. */
    public DeliveryConfig(@Nullable String targetId,
                          @Nullable String threadId,
                          @Nullable String accountId) {
        this(targetId, threadId, accountId, null);
    }

    /** Convert from the {@link ChannelTarget} carried on a {@code ChatOrigin}. */
    public static DeliveryConfig from(@Nullable ChannelTarget t) {
        if (t == null) return null;
        return new DeliveryConfig(t.targetId(), t.threadId(), t.accountId(), null);
    }

    /** Convert from {@link ChannelTarget} + the requester's senderId. */
    public static DeliveryConfig from(@Nullable ChannelTarget t, @Nullable String userId) {
        if (t == null) return null;
        return new DeliveryConfig(t.targetId(), t.threadId(), t.accountId(), userId);
    }

    /** Convert back to a {@link ChannelTarget} for ChatOrigin reconstruction. */
    public ChannelTarget toChannelTarget() {
        return new ChannelTarget(targetId, threadId, accountId);
    }
}
