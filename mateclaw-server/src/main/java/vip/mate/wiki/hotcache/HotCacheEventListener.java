package vip.mate.wiki.hotcache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.memory.event.ConversationCompletedEvent;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.List;

/**
 * Wires the wiki hot-cache rebuild trigger into the existing event flow.
 *
 * <p>Today only {@link ConversationCompletedEvent} is published — when a
 * chat turn ends, we schedule a rebuild for the agent's primary KB.
 * (Per the existing chat retrieval path, "primary" is the first entry
 * from {@link WikiKnowledgeBaseService#listByAgentId}, ordered by recent
 * update.)
 *
 * <p>The compile-completed and page-updated events that the design
 * envisions don't have publishers in the codebase yet; their listeners
 * will land in the PRs that introduce those publishers, to avoid
 * shipping dead glue. The scheduler exposes {@code rebuildNowBlocking}
 * so the future admin API can drive {@code MANUAL} rebuilds without
 * going through events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotCacheEventListener {

    private final HotCacheUpdateScheduler scheduler;
    private final WikiKnowledgeBaseService kbService;

    @EventListener
    public void onConversationEnd(ConversationCompletedEvent event) {
        Long agentId = event.agentId();
        if (agentId == null) return;
        Long kbId = resolvePrimaryKb(agentId);
        if (kbId == null) {
            log.debug("[HotCache] agent={} has no KB; skip post-conversation rebuild", agentId);
            return;
        }
        scheduler.scheduleRebuild(kbId, HotCacheUpdateReason.CONVERSATION_END);
    }

    private Long resolvePrimaryKb(Long agentId) {
        try {
            List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
            if (kbs.isEmpty()) return null;
            return kbs.get(0).getId();
        } catch (Exception e) {
            log.debug("[HotCache] KB resolution failed for agent={}: {}", agentId, e.getMessage());
            return null;
        }
    }
}
