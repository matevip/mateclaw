package vip.mate.cron.delivery;

import org.springframework.ai.chat.messages.AssistantMessage;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;

/**
 * RFC-063r §2.7.3: domain event fired by
 * {@code CronJobLifecycleService.finishRunAndPublish} after T2 commits.
 * Listeners run with {@code @TransactionalEventListener(AFTER_COMMIT)}, so
 * the run row is guaranteed visible from a fresh DB connection.
 */
public record CronJobCompletedEvent(
        CronJobEntity job,
        AssistantMessage result,
        CronJobRunEntity run) {
}
