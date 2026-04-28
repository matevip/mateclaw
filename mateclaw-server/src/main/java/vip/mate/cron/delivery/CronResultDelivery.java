package vip.mate.cron.delivery;

import org.springframework.ai.chat.messages.AssistantMessage;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;

/**
 * RFC-063r §2.6: pluggable strategy for delivering a cron job's assistant
 * result back to its originating context (channel, future webhook, future
 * SSE bridge, etc.).
 *
 * <p>{@link AbstractCronResultDelivery} provides the SQL-CAS idempotency +
 * state-machine update; concrete strategies only implement
 * {@link AbstractCronResultDelivery#doDeliver}.
 */
public interface CronResultDelivery {

    /**
     * Whether this strategy applies to the given job. The first matching
     * strategy (per {@code @Order}) wins; web-origin jobs match nothing,
     * leaving {@code delivery_status='NONE'}.
     */
    boolean supports(CronJobEntity job);

    /**
     * Run the delivery, internally claiming the run row via SQL CAS and
     * updating {@code delivery_status} on success / failure. Implementations
     * should override
     * {@link AbstractCronResultDelivery#doDeliver(CronJobEntity, AssistantMessage, CronJobRunEntity)}
     * rather than this method directly.
     */
    DeliveryOutcome deliver(CronJobEntity job, AssistantMessage result, CronJobRunEntity run);
}
