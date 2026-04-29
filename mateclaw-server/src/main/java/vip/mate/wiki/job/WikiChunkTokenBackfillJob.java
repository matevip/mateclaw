package vip.mate.wiki.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.repository.WikiChunkMapper;

import java.util.List;

/**
 * RFC-051 PR-1a: low-frequency backfill job that fills the new
 * {@code token_count} column on existing chunks. Runs in the background after
 * the V39 migration adds the column with all-NULL data.
 * <p>
 * Strategy:
 * <ul>
 *   <li>Pick at most {@value #BATCH_SIZE} chunks where {@code token_count IS NULL}.</li>
 *   <li>Estimate tokens as {@code ceil(charCount / 4.0)} — a rough approximation
 *       that PR-1c will replace with a real tokenizer once Tika and the
 *       preprocessing pipeline are in place.</li>
 *   <li>Persist and stop. Subsequent ticks pick up the next batch.</li>
 *   <li>Any failure is logged at {@code warn} and swallowed so the main
 *       ingest pipeline is never affected.</li>
 * </ul>
 *
 * The job is scheduled at a half-hour cron so a fresh upgrade does not
 * thrash the database.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikiChunkTokenBackfillJob {

    private static final int BATCH_SIZE = 500;

    private final WikiChunkMapper chunkMapper;

    /**
     * RFC-051 follow-up: count chunks still missing a token estimate.
     * Used by the admin endpoint to decide whether a manual rerun is worthwhile.
     */
    public long pendingCount() {
        return chunkMapper.selectCount(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .isNull(WikiChunkEntity::getTokenCount));
    }

    @Async
    @Scheduled(cron = "${mate.wiki.chunk-token-backfill-cron:0 */30 * * * ?}")
    public void runOnce() {
        try {
            List<WikiChunkEntity> batch = chunkMapper.selectList(
                    new LambdaQueryWrapper<WikiChunkEntity>()
                            .isNull(WikiChunkEntity::getTokenCount)
                            .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                return;
            }

            int updated = 0;
            for (WikiChunkEntity chunk : batch) {
                Integer chars = chunk.getCharCount();
                if (chars == null || chars <= 0) {
                    chunk.setTokenCount(0);
                } else {
                    chunk.setTokenCount((int) Math.ceil(chars / 4.0));
                }
                try {
                    chunkMapper.updateById(chunk);
                    updated++;
                } catch (Exception inner) {
                    log.warn("[WikiChunkTokenBackfill] Failed to update chunk={}: {}",
                            chunk.getId(), inner.getMessage());
                }
            }
            log.info("[WikiChunkTokenBackfill] Backfilled token_count for {}/{} chunks",
                    updated, batch.size());
        } catch (Exception e) {
            log.warn("[WikiChunkTokenBackfill] Backfill batch failed (will retry next tick): {}",
                    e.getMessage());
        }
    }
}
