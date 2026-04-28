package vip.mate.cron.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.cron.delivery.CronJobCompletedEvent;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.workspace.conversation.ConversationService;

import java.time.LocalDateTime;

/**
 * RFC-063r §2.7.2: three-segment transactional support for {@link CronJobRunner}.
 *
 * <p>Each method runs in its own short {@code REQUIRES_NEW} transaction so
 * the long LLM call between T1 and T2 never holds a DB connection.
 *
 * <ul>
 *   <li>{@code T1} — {@link #startRun}: insert run row + persist user message.</li>
 *   <li>{@code T-fail} — {@link #markRunFailed}: terminal state when the LLM
 *       call throws.</li>
 *   <li>{@code T2} — {@link #finishRunAndPublish}: persist assistant
 *       message + dispatch the conversation-completed event (memory pipeline)
 *       + the cron-job-completed event (delivery pipeline).</li>
 * </ul>
 *
 * <p>Lives in a separate {@code @Service} bean so cross-bean invocation from
 * {@link CronJobRunner} routes through the Spring AOP proxy (RFC §5.2 hard
 * rule). The lifecycle service is deliberately the only place
 * {@code @Transactional} appears in the cron-execution path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CronJobLifecycleService {

    private final CronJobRunMapper runMapper;
    private final ConversationService conversationService;
    private final ConversationCompletionPublisher completionPublisher;
    private final ApplicationEventPublisher events;

    /**
     * T1 — short transaction: persist a run row in {@code running} state,
     * persist the user message that triggered the run, and commit. Returns
     * the persisted entity so callers can observe its assigned id without a
     * second SELECT.
     *
     * @param triggerType {@code scheduled} (cron tick) or {@code manual} (runNow)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CronJobRunEntity startRun(CronJobEntity job, String userMessage, String triggerType) {
        CronJobRunEntity run = new CronJobRunEntity();
        run.setCronJobId(job.getId());
        run.setConversationId("cron:" + job.getId());
        run.setStatus("running");
        run.setTriggerType(triggerType != null ? triggerType : "scheduled");
        run.setStartedAt(LocalDateTime.now());
        run.setDeliveryStatus("NONE");
        runMapper.insert(run);

        // Persist the user message before the LLM call so history reads
        // see a coherent (user → assistant) ordering even if the agent
        // throws mid-run.
        if (userMessage != null && !userMessage.isBlank()) {
            conversationService.saveMessage(run.getConversationId(), "user", userMessage);
        }
        return run;
    }

    /**
     * T-fail — short transaction: flag the run row as failed when the agent
     * throws. Always-best-effort policy: delivery_status stays NONE; nothing
     * is published.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunFailed(CronJobRunEntity run, Throwable error) {
        String message = error != null && error.getMessage() != null ? error.getMessage() : "unknown error";
        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getStatus, "failed")
                .set(CronJobRunEntity::getFinishedAt, LocalDateTime.now())
                .set(CronJobRunEntity::getErrorMessage, StrUtil.maxLength(message, 1000)));
    }

    /**
     * T2 — short transaction: persist the assistant reply, mark the run
     * succeeded, then publish the two domain events. The
     * {@code @TransactionalEventListener(AFTER_COMMIT)} listeners only run
     * once this method's tx commits, so cross-connection reads in the
     * delivery / memory pipelines always see the final state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRunAndPublish(CronJobEntity job, CronJobRunEntity run,
                                    String userMessage, AssistantMessage result) {
        String convId = "cron:" + job.getId();
        String text = result != null && result.getText() != null ? result.getText() : "";

        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getStatus, "succeeded")
                .set(CronJobRunEntity::getFinishedAt, LocalDateTime.now()));

        conversationService.saveMessage(convId, "assistant", text);

        // Memory pipeline (existing behavior preserved — was inline in the
        // old executeJob; now lives behind the same publisher used by the
        // web / channel paths so cron is no longer special-cased).
        try {
            completionPublisher.publish(job.getAgentId(), convId, userMessage, text, "cron");
        } catch (Exception e) {
            // Memory failures must not break delivery — log + carry on.
            log.warn("[CronLifecycle] completionPublisher failed for job {}: {}", job.getId(), e.getMessage());
        }

        // Delivery pipeline (RFC-063r §2.7.3) — fired here so listeners only
        // run after T2 commits.
        events.publishEvent(new CronJobCompletedEvent(job, result != null ? result : new AssistantMessage(""), run));
    }
}
