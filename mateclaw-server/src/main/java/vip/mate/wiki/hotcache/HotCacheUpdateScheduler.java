package vip.mate.wiki.hotcache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiHotCacheEntity;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Off-loads hot-cache rebuilds onto a virtual-thread executor and applies
 * two safety nets:
 *
 * <ul>
 *   <li>A per-KB {@link ReentrantLock} so two threads can never race on
 *       the same row.</li>
 *   <li>A debounce window ({@link HotCacheProperties#getDebounce()}):
 *       within that window of the last rebuild start, additional events
 *       are dropped because the next rebuild already covers them.</li>
 * </ul>
 *
 * <p>Both checks are best-effort — there's no global coordination, so
 * two JVMs can each do one rebuild back-to-back. The
 * {@code last_rebuild_started_at} timestamp the updater writes is the
 * cross-process signal that PR-2's debounce uses; a future PR can lift
 * this to a distributed lock if churn shows up in metrics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotCacheUpdateScheduler {

    private final HotCacheProperties props;
    private final WikiHotCacheService cacheService;
    private final WikiHotCacheUpdater updater;

    private final ConcurrentMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Virtual threads — rebuilds are LLM-bound, not CPU-bound. */
    private static final ExecutorService REBUILD_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /** Schedule a rebuild for {@code kbId}; returns immediately. */
    public void scheduleRebuild(Long kbId, HotCacheUpdateReason reason) {
        if (kbId == null) return;
        REBUILD_EXECUTOR.execute(() -> attemptRebuild(kbId, reason));
    }

    /** Synchronous variant — used by tests + the (future) admin API. */
    public void rebuildNowBlocking(Long kbId, HotCacheUpdateReason reason) {
        if (kbId == null) return;
        attemptRebuild(kbId, reason);
    }

    private void attemptRebuild(Long kbId, HotCacheUpdateReason reason) {
        ReentrantLock lock = locks.computeIfAbsent(kbId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("[HotCache][kb={}] rebuild already running; reason={} skipped", kbId, reason);
            return;
        }
        try {
            if (withinDebounceWindow(kbId, reason)) return;
            updater.rebuild(kbId, reason);
        } catch (Exception e) {
            log.warn("[HotCache][kb={}] scheduled rebuild crashed; reason={}: {}",
                    kbId, reason, e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private boolean withinDebounceWindow(Long kbId, HotCacheUpdateReason reason) {
        // MANUAL bypasses debounce — operators triggering by hand expect to
        // see their click take effect.
        if (reason == HotCacheUpdateReason.MANUAL) return false;

        WikiHotCacheEntity existing = cacheService.findByKb(kbId).orElse(null);
        if (existing == null || existing.getLastRebuildStartedAt() == null) return false;
        Instant lastStart = existing.getLastRebuildStartedAt()
                .atZone(ZoneId.systemDefault()).toInstant();
        if (Instant.now().isBefore(lastStart.plus(props.getDebounce()))) {
            log.debug("[HotCache][kb={}] within debounce window; reason={} skipped", kbId, reason);
            return true;
        }
        return false;
    }
}
