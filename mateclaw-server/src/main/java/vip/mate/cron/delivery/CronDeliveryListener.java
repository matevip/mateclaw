package vip.mate.cron.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vip.mate.audit.service.AuditEventService;

import java.util.List;
import java.util.Optional;

/**
 * RFC-063r §2.7.3: Domain-Event listener that resolves the right
 * {@link CronResultDelivery} strategy and runs it asynchronously after the
 * cron run's T2 transaction commits.
 *
 * <p>Why {@code AFTER_COMMIT} + {@code @Async}:
 * <ul>
 *   <li>{@code AFTER_COMMIT} guarantees the listener can re-read the run
 *       row from a fresh DB connection — eliminates the read-your-writes
 *       trap from the same-tx delivery path.</li>
 *   <li>{@code @Async("cronDeliveryExecutor")} unbinds delivery from the
 *       Spring event-dispatcher thread so a slow IM API never stalls other
 *       listeners. The pool uses {@code AbortPolicy} + audit so an overflow
 *       surfaces immediately rather than degrading silently.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronDeliveryListener {

    /** Spring auto-collects every {@link CronResultDelivery} bean ordered by {@code @Order}. */
    private final List<CronResultDelivery> deliveries;
    private final AuditEventService audit;

    @Async("cronDeliveryExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCompleted(CronJobCompletedEvent ev) {
        Optional<CronResultDelivery> strategy = deliveries.stream()
                .filter(d -> d.supports(ev.job()))
                .findFirst();
        if (strategy.isEmpty()) {
            // RFC §2.6 / §2.8.1: web-origin runs (or any job without a
            // matching strategy) leave delivery_status=NONE and exit silently.
            return;
        }
        try {
            strategy.get().deliver(ev.job(), ev.result(), ev.run());
        } catch (Exception e) {
            // RFC §2.7.3: always-best-effort — failures audit but never flip
            // the run's main status. Stale-pending cleanup tightens the
            // delivery_status state machine if the row gets stuck.
            log.warn("[CronDelivery] Delivery failed for job {}: {}",
                    ev.job() != null ? ev.job().getId() : null, e.getMessage());
            try {
                audit.record("DELIVERY_FAILED", "CRON_JOB",
                        ev.job() != null ? String.valueOf(ev.job().getId()) : "unknown",
                        e.getMessage(), null);
            } catch (Exception auditError) {
                log.warn("[CronDelivery] Audit recording failed: {}", auditError.getMessage());
            }
        }
    }

    /**
     * RFC-063r §2.7.3: dedicated executor for cron delivery. Defined here so
     * the listener and its pool live in the same module without an extra
     * {@code @Configuration} class.
     */
    @org.springframework.context.annotation.Bean(name = "cronDeliveryExecutor")
    public ThreadPoolTaskExecutor cronDeliveryExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("cron-delivery-");
        ex.setRejectedExecutionHandler((r, executor) -> {
            // RFC §2.7.3: AbortPolicy + audit (NOT CallerRunsPolicy) — the
            // caller is the Spring event-dispatcher thread; blocking it would
            // stall every other listener.
            log.error("[CronDelivery] queue overflow ({}); dropping task — review pool sizing",
                    executor.getQueue().size());
            try {
                audit.record("DELIVERY_QUEUE_OVERFLOW", "CRON_DELIVERY", "system",
                        "active=" + executor.getActiveCount() + ",queue=" + executor.getQueue().size(),
                        null);
            } catch (Exception ignored) {
                // Audit must never block the rejection path itself.
            }
        });
        ex.initialize();
        return ex;
    }
}
