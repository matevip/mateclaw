package vip.mate.trigger.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import vip.mate.trigger.dispatch.TriggerDispatcher;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.repository.TriggerMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Maintains the in-memory map of cron-pattern triggers active on this node
 * and fires them through {@link TriggerDispatcher}. Coordination across
 * nodes uses ShedLock (per-trigger lock keyed by id) so simultaneous fires
 * collapse into one. Each scheduled task captures the trigger's
 * {@code patternVersion} at register time; on fire the live row's version
 * is re-read and the local task self-cancels when it has fallen behind a
 * newer cron expression — no need to chase a stale {@link ScheduledFuture}.
 *
 * <p>Only the {@code cron} pattern type registers here. Other pattern
 * flavours (channel_message, workflow_completion, ...) drive triggers
 * through their own ingestion pipeline and do not occupy a scheduler tick.
 */
@Slf4j
@Component
public class TriggerScheduler {

    private static final String PATTERN_CRON = "cron";

    private final TriggerMapper triggerMapper;
    private final TriggerDispatcher dispatcher;
    private final LockProvider lockProvider;
    private final ObjectMapper objectMapper;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<Long, Registration> registrations = new ConcurrentHashMap<>();

    public TriggerScheduler(TriggerMapper triggerMapper,
                            TriggerDispatcher dispatcher,
                            LockProvider lockProvider,
                            ObjectMapper objectMapper) {
        this.triggerMapper = triggerMapper;
        this.dispatcher = dispatcher;
        this.lockProvider = lockProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initScheduler() {
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("trigger-tick-");
        scheduler.setDaemon(true);
        scheduler.initialize();
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdown();
        registrations.clear();
    }

    /** Boot-time registration sweep; runs after Flyway and bean wiring complete. */
    @EventListener(ApplicationReadyEvent.class)
    void registerEnabledTriggersOnStartup() {
        syncFromDatabase();
    }

    /**
     * Periodic sweep that converges this node's local registrations with
     * the canonical state in {@code mate_trigger}.
     *
     * <p>Reasons this exists:
     * <ul>
     *   <li>Multi-instance: when node A creates / updates / disables a
     *       cron trigger, node B never gets the local-only register call.
     *       The fire-time {@code patternVersion} guard self-cancels stale
     *       schedules but does NOT register newly-created or newly-enabled
     *       triggers — only this sweep does.</li>
     *   <li>Recovery from missed events: if a register / unregister call
     *       races with a node restart, the in-memory map can drift from
     *       the row state. Refreshing every minute caps the divergence.</li>
     * </ul>
     *
     * <p>Convergence rules:
     * <ul>
     *   <li>Row enabled + cron type + not registered locally → register.</li>
     *   <li>Row enabled but local {@code capturedVersion} differs from
     *       row's {@code pattern_version} → re-register (the schedule
     *       carries the new expression).</li>
     *   <li>Local registration exists for a row that's now disabled,
     *       deleted, or no longer cron-typed → unregister.</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${mateclaw.workflow.trigger.sync-interval-ms:60000}",
               initialDelayString = "${mateclaw.workflow.trigger.sync-initial-delay-ms:60000}")
    public void syncFromDatabase() {
        var enabled = triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getEnabled, true)
                .eq(TriggerEntity::getDeleted, 0));
        java.util.Set<Long> seenIds = new java.util.HashSet<>();
        int registered = 0, refreshed = 0, removed = 0;
        for (TriggerEntity t : enabled) {
            if (!PATTERN_CRON.equalsIgnoreCase(t.getPatternType())) continue;
            seenIds.add(t.getId());
            Registration current = registrations.get(t.getId());
            long liveVersion = t.getPatternVersion() == null ? 1L : t.getPatternVersion();
            if (current == null) {
                if (registerInternal(t)) registered++;
            } else if (current.capturedVersion != liveVersion) {
                if (registerInternal(t)) refreshed++;
            }
        }
        // Drop registrations whose row was disabled / deleted / changed type
        // since the last sweep. Snapshot the keys first to avoid concurrent
        // modification on the underlying map.
        for (Long localId : new java.util.ArrayList<>(registrations.keySet())) {
            if (!seenIds.contains(localId)) {
                unregister(localId);
                removed++;
            }
        }
        if (registered + refreshed + removed > 0) {
            log.info("[TriggerScheduler] sync: registered={} refreshed={} removed={} active={}",
                    registered, refreshed, removed, registrations.size());
        }
    }

    /** Register or replace a single trigger (called from {@code TriggerService} on save). */
    public boolean register(TriggerEntity trigger) {
        if (trigger == null || !PATTERN_CRON.equalsIgnoreCase(trigger.getPatternType())) {
            return false;
        }
        return registerInternal(trigger);
    }

    /** Cancel any active schedule for {@code triggerId}. Idempotent. */
    public void unregister(long triggerId) {
        Registration r = registrations.remove(triggerId);
        if (r != null) {
            r.future.cancel(false);
        }
    }

    /**
     * Whether {@code triggerId} currently occupies an active scheduled task on
     * this node. Visible because monitoring / health endpoints surface the
     * same fact, and the alternative would be exposing the raw registration
     * map.
     */
    public boolean isRegistered(long triggerId) {
        return registrations.containsKey(triggerId);
    }

    /**
     * Manually drive the lamport + dispatch path the cron tick would otherwise
     * call. Used by integration tests; production code should never call this
     * directly — the scheduler owns its own tick.
     */
    public void fireForTest(long triggerId, long capturedVersion) {
        fireWithCoordination(triggerId, capturedVersion);
    }

    private boolean registerInternal(TriggerEntity trigger) {
        unregister(trigger.getId());
        ParsedCron parsed = parseCron(trigger);
        if (parsed == null) return false;

        long capturedVersion = trigger.getPatternVersion() == null ? 1L : trigger.getPatternVersion();
        Runnable task = () -> fireWithCoordination(trigger.getId(), capturedVersion);
        ScheduledFuture<?> future = scheduler.schedule(task,
                new CronTrigger(parsed.expression, parsed.timeZone));
        registrations.put(trigger.getId(), new Registration(future, capturedVersion));
        log.info("[TriggerScheduler] Registered trigger {} cron='{}' tz={} version={}",
                trigger.getId(), parsed.expression, parsed.timeZone.getID(), capturedVersion);
        return true;
    }

    private void fireWithCoordination(long triggerId, long capturedVersion) {
        // Per-fire lamport check: a newer expression in the DB invalidates
        // this scheduled task. Drop the fire and unregister so the next
        // registration cycle picks up the new schedule.
        TriggerEntity live = triggerMapper.selectById(triggerId);
        if (live == null || Boolean.FALSE.equals(live.getEnabled())) {
            unregister(triggerId);
            return;
        }
        long liveVersion = live.getPatternVersion() == null ? 1L : live.getPatternVersion();
        if (liveVersion != capturedVersion) {
            log.info("[TriggerScheduler] trigger {} self-cancelling (version changed {} -> {})",
                    triggerId, capturedVersion, liveVersion);
            unregister(triggerId);
            return;
        }
        if (live.getMaxFires() != null && live.getMaxFires() > 0
                && live.getFireCount() != null && live.getFireCount() >= live.getMaxFires()) {
            log.info("[TriggerScheduler] trigger {} reached max_fires={}, unregistering",
                    triggerId, live.getMaxFires());
            unregister(triggerId);
            return;
        }

        // Cross-node coordination: at-most-one node fires per tick.
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(),
                "trigger-fire-" + triggerId,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5)));
        if (lock.isEmpty()) {
            return; // peer is firing
        }
        try {
            vip.mate.trigger.dispatch.DispatchResult outcome =
                    dispatcher.dispatch(live, Map.of("firedAt", Instant.now().toString()));
            // Bookkeeping is honest: only a real fire bumps fireCount /
            // lastFiredAt. Skipped (no published revision, etc.) and failed
            // outcomes still record lastDispatchedAt + lastError so the UI
            // can show why a cron stopped firing.
            LocalDateTime now = LocalDateTime.now();
            live.setLastDispatchedAt(now);
            if (outcome != null && outcome.fired()) {
                live.setFireCount((live.getFireCount() == null ? 0L : live.getFireCount()) + 1);
                live.setLastFiredAt(now);
                live.setLastError(null);
            } else {
                live.setLastError(outcome == null ? "dispatcher returned null" : outcome.reason());
            }
            triggerMapper.updateById(live);
        } catch (Exception e) {
            log.error("[TriggerScheduler] trigger {} fire failed: {}", triggerId, e.getMessage(), e);
            try {
                live.setLastDispatchedAt(LocalDateTime.now());
                live.setLastError("scheduler threw: " + e.getMessage());
                triggerMapper.updateById(live);
            } catch (Exception ignored) {
                // Best-effort — don't let a bookkeeping failure mask the dispatch failure.
            }
        } finally {
            lock.get().unlock();
        }
    }

    private record ParsedCron(String expression, TimeZone timeZone) {}

    private ParsedCron parseCron(TriggerEntity trigger) {
        try {
            JsonNode node = objectMapper.readTree(
                    trigger.getPatternJson() == null ? "{}" : trigger.getPatternJson());
            String expr = node.path("cron").asText("");
            if (expr.isBlank()) {
                log.warn("[TriggerScheduler] trigger {} missing 'cron' in pattern_json; skipping",
                        trigger.getId());
                return null;
            }
            String tz = node.path("timezone").asText("UTC");
            return new ParsedCron(expr, TimeZone.getTimeZone(ZoneId.of(tz)));
        } catch (Exception e) {
            log.warn("[TriggerScheduler] trigger {} pattern_json parse failed: {}",
                    trigger.getId(), e.getMessage());
            return null;
        }
    }

    private record Registration(ScheduledFuture<?> future, long capturedVersion) {}
}
