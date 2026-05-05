package vip.mate.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.wiki.hotcache.HotCacheUpdateReason;
import vip.mate.wiki.hotcache.HotCacheUpdateScheduler;
import vip.mate.wiki.hotcache.WikiHotCacheService;
import vip.mate.wiki.model.WikiHotCacheEntity;

/**
 * Operator endpoints for the KB hot cache: inspect the current snapshot,
 * trigger a manual rebuild, or wipe the cache so the next event-driven
 * rebuild starts from scratch.
 *
 * <p>Sits behind the standard JWT-protected {@code /api/v1} prefix.
 */
@RestController
@RequestMapping("/api/v1/wiki/hot-cache")
@RequiredArgsConstructor
@Tag(name = "Wiki Hot Cache", description = "Operator endpoints for the KB-level recent activity snapshot")
public class WikiHotCacheController {

    private final WikiHotCacheService service;
    private final HotCacheUpdateScheduler scheduler;

    @Operation(summary = "Get the current hot cache snapshot for a KB",
               description = "Returns null data if no rebuild has run yet.")
    @GetMapping("/{kbId}")
    public R<WikiHotCacheEntity> get(@PathVariable Long kbId) {
        return R.ok(service.findByKb(kbId).orElse(null));
    }

    @Operation(summary = "Schedule a manual rebuild of the hot cache",
               description = "Async — the scheduler runs the LLM call on a virtual thread. "
                           + "MANUAL bypasses the debounce window so operator triggers always run. "
                           + "Poll GET to see progress.")
    @PostMapping("/{kbId}/regenerate")
    public R<Void> regenerate(@PathVariable Long kbId) {
        scheduler.scheduleRebuild(kbId, HotCacheUpdateReason.MANUAL);
        return R.ok();
    }

    @Operation(summary = "Soft-delete the hot cache row",
               description = "The next event-driven rebuild will create a fresh row. "
                           + "Use this to clear a wedged cache without waiting for the staleness window.")
    @DeleteMapping("/{kbId}")
    public R<Void> reset(@PathVariable Long kbId) {
        service.findByKb(kbId).ifPresent(row -> service.softDelete(row.getId()));
        return R.ok();
    }
}
