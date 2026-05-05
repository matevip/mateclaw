package vip.mate.system.featureflag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.system.featureflag.repository.FeatureFlagMapper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Runtime-toggleable feature-flag store with in-memory caching.
 *
 * <p>Reads are O(1) once the cache is warm; the cache refreshes on a
 * configurable timer (default 30 s) and immediately on admin write via
 * {@link #invalidate()}. Multi-instance deployments converge within one
 * refresh tick; admin writes from one instance are not pushed to peers
 * (acceptable for a coarse-grained flag system).
 *
 * <p>Evaluation order:
 * <ol>
 *   <li>If {@code enabled == false}, return false.</li>
 *   <li>If {@code whitelist_kb_ids} is set and the context has a kbId,
 *       require kb membership; absence in the whitelist returns false.</li>
 *   <li>Same for {@code whitelist_user_ids}.</li>
 *   <li>If both whitelists are blank and {@code rollout_percent} is in
 *       (0, 100), use a deterministic hash of the context's keying value
 *       to gate.</li>
 *   <li>Otherwise return true.</li>
 * </ol>
 *
 * <p>Unknown flags evaluate to false (fail-closed). This makes it safe to
 * remove a flag from the seed list: callers see the feature as disabled
 * until the flag is re-introduced.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private static final FeatureFlagEntity MISSING = new FeatureFlagEntity();

    private final FeatureFlagMapper mapper;

    /** flagKey → resolved entity. {@link #MISSING} sentinel marks "not in DB". */
    private final ConcurrentHashMap<String, FeatureFlagEntity> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** Returns true iff the flag is enabled in the given context. */
    public boolean isEnabled(String flagKey, FlagContext ctx) {
        FeatureFlagEntity flag = cache.computeIfAbsent(flagKey, this::loadOne);
        if (flag == MISSING || !Boolean.TRUE.equals(flag.getEnabled())) {
            return false;
        }
        return matchesContext(flag, ctx == null ? FlagContext.empty() : ctx);
    }

    public boolean isEnabled(String flagKey) {
        return isEnabled(flagKey, FlagContext.empty());
    }

    public boolean isEnabledForKb(String flagKey, Long kbId) {
        return isEnabled(flagKey, FlagContext.ofKb(kbId));
    }

    public boolean isEnabledForUser(String flagKey, Long userId) {
        return isEnabled(flagKey, FlagContext.ofUser(userId));
    }

    /** Periodic full refresh; covers DB writes that bypass the admin API. */
    @Scheduled(fixedDelayString = "${mateclaw.feature-flag.refresh-ms:30000}")
    public void refresh() {
        try {
            List<FeatureFlagEntity> all = mapper.selectList(null);
            ConcurrentHashMap<String, FeatureFlagEntity> next = new ConcurrentHashMap<>();
            for (FeatureFlagEntity flag : all) {
                next.put(flag.getFlagKey(), flag);
            }
            cache.clear();
            cache.putAll(next);
            log.debug("[FeatureFlag] refreshed {} flags from DB", next.size());
        } catch (Exception e) {
            log.warn("[FeatureFlag] refresh failed; keeping previous cache: {}", e.getMessage());
        }
    }

    /** Invalidate + reload; called by admin-write paths to make changes visible immediately. */
    public void invalidate() {
        cache.clear();
        refresh();
    }

    // ==================== internal ====================

    private boolean matchesContext(FeatureFlagEntity flag, FlagContext ctx) {
        boolean kbWhitelistDefined = isPresent(flag.getWhitelistKbIds());
        boolean userWhitelistDefined = isPresent(flag.getWhitelistUserIds());

        if (kbWhitelistDefined && ctx.getKbId() != null) {
            Set<Long> kbs = parseIds(flag.getWhitelistKbIds());
            if (!kbs.contains(ctx.getKbId())) {
                return false;
            }
        }
        if (userWhitelistDefined && ctx.getUserId() != null) {
            Set<Long> users = parseIds(flag.getWhitelistUserIds());
            if (!users.contains(ctx.getUserId())) {
                return false;
            }
        }

        // Percentage rollout only consulted when no whitelist applies.
        if (!kbWhitelistDefined && !userWhitelistDefined) {
            Integer pct = flag.getRolloutPercent();
            if (pct != null && pct > 0 && pct < 100) {
                long key = ctx.getKbId() != null
                        ? ctx.getKbId()
                        : ctx.getUserId() != null
                                ? ctx.getUserId()
                                : flag.getFlagKey().hashCode();
                return Math.floorMod(key, 100L) < pct;
            }
        }

        return true;
    }

    private static boolean isPresent(String csv) {
        return csv != null && !csv.isBlank();
    }

    private static Set<Long> parseIds(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private FeatureFlagEntity loadOne(String flagKey) {
        try {
            FeatureFlagEntity flag = mapper.selectOne(
                    new LambdaQueryWrapper<FeatureFlagEntity>()
                            .eq(FeatureFlagEntity::getFlagKey, flagKey));
            return flag != null ? flag : MISSING;
        } catch (Exception e) {
            log.warn("[FeatureFlag] loadOne({}) failed: {}", flagKey, e.getMessage());
            return MISSING;
        }
    }
}
