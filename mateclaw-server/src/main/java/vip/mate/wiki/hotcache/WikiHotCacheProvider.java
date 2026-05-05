package vip.mate.wiki.hotcache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.spi.MemoryProvider;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.List;

/**
 * Memory-SPI provider that injects each KB's hot cache snapshot into the
 * agent's system prompt at build time.
 *
 * <p>Resolves the agent's accessible KBs via the same path the chat
 * pipeline already uses ({@link WikiKnowledgeBaseService#listByAgentId}).
 * If the agent reaches more than {@link #MAX_KBS_PER_AGENT} KBs, only the
 * top KBs by recent-update order contribute (the list is already sorted
 * by {@code update_time DESC}); this is a prompt-budget guard, not a
 * permissions decision.
 *
 * <p>Per-turn paths are intentionally untouched — wiki relevance is
 * already handled by {@code WikiContextService.buildRelevantContext} on
 * each user message; this provider's job is the steady "what's been
 * happening here" header that the model sees once at session start.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikiHotCacheProvider implements MemoryProvider {

    private static final String FLAG = "wiki.hot_cache.enabled";
    private static final int MAX_KBS_PER_AGENT = 2;

    private final WikiHotCacheService cacheService;
    private final WikiKnowledgeBaseService kbService;
    private final FeatureFlagService featureFlagService;

    @Override
    public String id() {
        return "wiki_hot_cache";
    }

    @Override
    public int order() {
        // After builtin (0), structured (10), session-search (20).
        // Hot cache is freshest, but slots after the more general blocks
        // so the model frames "recent activity" as a sub-context, not a
        // top-level identity hint.
        return 30;
    }

    @Override
    public String systemPromptBlock(Long agentId) {
        if (agentId == null) return "";
        try {
            if (!featureFlagService.isEnabled(FLAG)) return "";

            List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
            if (kbs.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            int taken = 0;
            for (WikiKnowledgeBaseEntity kb : kbs) {
                if (taken >= MAX_KBS_PER_AGENT) break;
                String content = cacheService.getContentOrNull(kb.getId());
                if (content == null || content.isBlank()) continue;
                if (taken == 0) {
                    sb.append("# Recent Wiki Activity\n\n");
                } else {
                    sb.append("\n\n## ").append(kb.getName()).append("\n\n");
                }
                sb.append(content);
                taken++;
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[WikiHotCache] systemPromptBlock failed for agent={}: {}",
                    agentId, e.getMessage());
            return "";
        }
    }
}
