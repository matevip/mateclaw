package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiImageCaptionCacheEntity;
import vip.mate.wiki.repository.WikiImageCaptionCacheMapper;

import java.util.Optional;

/**
 * Service layer for the SHA-256 keyed image caption cache.
 *
 * <p>The cache is the storage half of the vision-in pipeline: the
 * {@code ImageVisionService} (added in a subsequent PR) calls
 * {@link #lookup(String)} before invoking a remote vision provider and
 * calls {@link #persist(WikiImageCaptionCacheEntity)} once a fresh
 * caption is available. Lookups bump the per-row hit counter on a
 * best-effort basis — if the bump fails the read still succeeds, since
 * the counter is operational metadata only.
 *
 * <p>Concurrent inserts of the same SHA are tolerated: the unique key
 * lets the database serialize them, and the loser quietly drops its
 * value (the earlier writer's caption is equally valid for a content-
 * addressed cache).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiImageCaptionCacheService {

    private final WikiImageCaptionCacheMapper mapper;

    /** Returns the cached row for the given image SHA-256, or empty on miss. */
    public Optional<WikiImageCaptionCacheEntity> lookup(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return Optional.empty();
        }
        WikiImageCaptionCacheEntity row = mapper.selectOne(
                new LambdaQueryWrapper<WikiImageCaptionCacheEntity>()
                        .eq(WikiImageCaptionCacheEntity::getImageSha256, sha256));
        if (row == null) {
            return Optional.empty();
        }

        // Best-effort hit-count bump; never fail the read on a counter mishap.
        try {
            mapper.bumpHitCount(sha256);
        } catch (Exception e) {
            log.debug("[ImageCaptionCache] hit_count bump failed for sha={}: {}",
                    truncate(sha256), e.getMessage());
        }
        return Optional.of(row);
    }

    /**
     * Inserts a new cache row, treating duplicate-key collisions as success.
     *
     * <p>When two requests race on the same novel SHA, both arrive at the
     * vision provider, both produce a caption, and both attempt to write.
     * The DB enforces one winner; the loser sees a duplicate-key error,
     * which we swallow because the earlier-written caption is just as good.
     */
    @Transactional
    public void persist(WikiImageCaptionCacheEntity row) {
        if (row == null || row.getImageSha256() == null) {
            throw new IllegalArgumentException("image_sha256 is required");
        }
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException dup) {
            log.debug("[ImageCaptionCache] race on sha={}; keeping earlier writer's value",
                    truncate(row.getImageSha256()));
        }
    }

    /** Returns the first 8 hex chars of the SHA, suitable for log messages. */
    private static String truncate(String sha) {
        return sha == null ? "null" : sha.substring(0, Math.min(8, sha.length()));
    }
}
