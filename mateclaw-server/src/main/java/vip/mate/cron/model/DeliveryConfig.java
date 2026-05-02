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
        @Nullable String userId,
        /**
         * RFC-03 Lane C1 — when {@code TRUE}, {@code CronDeliveryListener}
         * skips strategy resolution entirely and the run completes with
         * {@code delivery_status='NONE'}. Tools still execute, the run row
         * is still persisted, audit + token-usage all work — only the
         * agent's narrative reply is suppressed from the channel.
         *
         * <p>Use cases: noon health-check cron that just
         * pokes a database and writes structured output, project-weekly
         * report jobs that drop a file into a knowledge base, internal
         * pipelines that don't need an IM-visible "I did the thing"
         * trailing message.
         *
         * <p>{@code Boolean} (not {@code boolean}) so JSON deserialization
         * of pre-V75 rows leaves the field {@code null} — the listener
         * treats {@code null} and {@code FALSE} identically (deliver as
         * usual), preserving every existing job's behavior.
         */
        @Nullable Boolean suppressAgentReply
) {

    /** 3-arg legacy constructor preserved so older deserialized rows still work. */
    public DeliveryConfig(@Nullable String targetId,
                          @Nullable String threadId,
                          @Nullable String accountId) {
        this(targetId, threadId, accountId, null, null);
    }

    /** 4-arg legacy constructor — pre-RFC-03 callers that already carry userId. */
    public DeliveryConfig(@Nullable String targetId,
                          @Nullable String threadId,
                          @Nullable String accountId,
                          @Nullable String userId) {
        this(targetId, threadId, accountId, userId, null);
    }

    /** Convert from the {@link ChannelTarget} carried on a {@code ChatOrigin}. */
    public static DeliveryConfig from(@Nullable ChannelTarget t) {
        if (t == null) return null;
        return new DeliveryConfig(t.targetId(), t.threadId(), t.accountId(), null, null);
    }

    /** Convert from {@link ChannelTarget} + the requester's senderId. */
    public static DeliveryConfig from(@Nullable ChannelTarget t, @Nullable String userId) {
        if (t == null) return null;
        return new DeliveryConfig(t.targetId(), t.threadId(), t.accountId(), userId, null);
    }

    /** Convert back to a {@link ChannelTarget} for ChatOrigin reconstruction. */
    public ChannelTarget toChannelTarget() {
        return new ChannelTarget(targetId, threadId, accountId);
    }

    /**
     * RFC-03 Lane C1 — convenience predicate so listeners can short-circuit
     * delivery resolution without unwrapping the {@code Boolean}. Treats
     * {@code null} as {@code false} (the historical default).
     */
    public boolean isAgentReplySuppressed() {
        return Boolean.TRUE.equals(suppressAgentReply);
    }
}
