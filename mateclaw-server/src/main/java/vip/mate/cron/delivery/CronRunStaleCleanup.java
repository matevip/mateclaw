package vip.mate.cron.delivery;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * RFC-063r §2.8.2: periodic sweep that recovers two stuck states with one
 * scheduled job.
 *
 * <ul>
 *   <li>{@code delivery_status=PENDING} older than 15 min → mark
 *       {@code NOT_DELIVERED} with {@code stale-pending-timeout} reason.
 *       Covers listener crashes / OOMs / forced kills after a successful
 *       {@code claimRun()} but before {@code markDelivered}.</li>
 *   <li>{@code status='running'} older than 30 min → mark {@code failed}
 *       with {@code stale-running-timeout}. Covers
 *       {@code CronJobLifecycleService.markRunFailed()} itself failing under
 *       DB jitter (the LLM call already terminated by then).</li>
 * </ul>
 *
 * <p>Single sweep, two predicates, one DB roundtrip per state. Spring
 * {@code @Scheduled} is already enabled at the application bootstrap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronRunStaleCleanup {

    private final CronJobRunMapper runMapper;

    private static final Duration DELIVERY_STALE = Duration.ofMinutes(15);
    private static final Duration RUN_STALE = Duration.ofMinutes(30);

    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();

        int stalePending = runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getDeliveryStatus, "PENDING")
                .lt(CronJobRunEntity::getStartedAt, now.minus(DELIVERY_STALE))
                .set(CronJobRunEntity::getDeliveryStatus, "NOT_DELIVERED")
                .set(CronJobRunEntity::getDeliveryError, "stale-pending-timeout"));

        int staleRunning = runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getStatus, "running")
                .lt(CronJobRunEntity::getStartedAt, now.minus(RUN_STALE))
                .set(CronJobRunEntity::getStatus, "failed")
                .set(CronJobRunEntity::getFinishedAt, now)
                .set(CronJobRunEntity::getErrorMessage, "stale-running-timeout"));

        if (stalePending > 0 || staleRunning > 0) {
            log.warn("[CronCleanup] swept stalePending={} staleRunning={}", stalePending, staleRunning);
        }
    }
}
