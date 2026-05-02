package vip.mate.wiki.hotcache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiHotCacheEntity;
import vip.mate.wiki.repository.WikiHotCacheMapper;

import java.util.Optional;

/**
 * Read/write service for KB-level hot cache snapshots.
 *
 * <p>This PR provides read methods only — the LLM-driven rebuilder lands
 * in a follow-up PR (along with the event listener and debounce window).
 * Read methods are exposed now so the agent-prompt provider can wire up
 * against a stable API even before any cache rows exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiHotCacheService {

    private final WikiHotCacheMapper mapper;

    /**
     * Returns the active hot cache row for {@code kbId}, or empty when
     * none has ever been generated for this KB. The mapper logical-delete
     * filter excludes soft-deleted rows automatically.
     */
    public Optional<WikiHotCacheEntity> findByKb(Long kbId) {
        if (kbId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(
                new LambdaQueryWrapper<WikiHotCacheEntity>()
                        .eq(WikiHotCacheEntity::getKbId, kbId)));
    }

    /**
     * Returns the rendered markdown body for {@code kbId}, or {@code null}
     * when no cache row exists. Convenience for the agent-prompt provider
     * which only ever needs the body.
     */
    public String getContentOrNull(Long kbId) {
        return findByKb(kbId).map(WikiHotCacheEntity::getContent).orElse(null);
    }
}
