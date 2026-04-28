package vip.mate.cron.delivery;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.DeliveryOptions;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.model.DeliveryConfig;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.util.Map;

/**
 * RFC-063r §2.6: deliver a cron job's assistant result back to its
 * originating IM channel via {@link ChannelManager#sendToChannel}.
 *
 * <p>{@link #supports} returns true only when both {@code channelId} and a
 * non-null {@code deliveryConfig.targetId()} are present — web-origin jobs
 * (no channelId) and partial bindings fall through and the run stays in
 * {@code delivery_status='NONE'}, matching the always-best-effort policy in
 * RFC §2.7.3.
 */
@Component
@Order(10)
public class ChannelCronResultDelivery extends AbstractCronResultDelivery {

    private final ChannelManager channelManager;

    public ChannelCronResultDelivery(CronJobRunMapper runMapper,
                                     ChannelManager channelManager) {
        super(runMapper);
        this.channelManager = channelManager;
    }

    @Override
    public boolean supports(CronJobEntity job) {
        if (job == null || job.getChannelId() == null) return false;
        DeliveryConfig dc = job.getDeliveryConfig();
        return dc != null && dc.targetId() != null && !dc.targetId().isBlank();
    }

    @Override
    protected DeliveryOutcome doDeliver(CronJobEntity job, AssistantMessage result, CronJobRunEntity run) {
        DeliveryConfig dc = job.getDeliveryConfig();
        String rendered = renderForChannel(result, job.getChannelId());
        // RFC-063r §2.10: forward thread / account hints via DeliveryOptions.
        // Adapters that don't override the 4-arg proactiveSend default ignore
        // the hints — preserves pre-RFC behavior for non-threading platforms.
        DeliveryOptions options = new DeliveryOptions(dc.threadId(), dc.accountId(), Map.of());
        channelManager.sendToChannel(job.getChannelId(), dc.targetId(), rendered, options);
        return DeliveryOutcome.delivered(dc.targetId());
    }
}
