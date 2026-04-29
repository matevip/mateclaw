package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.event.WikiKbDirtyEvent;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generates and maintains the LLM-narrated section of a knowledge base's
 * {@code overview} system page. Listens for {@link WikiKbDirtyEvent}s after
 * commit, debounces per-KB so a burst of ingests collapses into a single LLM
 * call, then rewrites only the narrative marker block — leaving the
 * deterministic stats block (managed by {@link WikiOverviewService}) and any
 * user-authored prose untouched.
 *
 * <h2>Marker contract</h2>
 * The narrative lives between:
 * <pre>
 * &lt;!-- mate:overview:narrative:v1:start --&gt;
 *   ... LLM-rewritten 2-3 sentence summary ...
 * &lt;!-- mate:overview:narrative:v1:end --&gt;
 * </pre>
 * If the markers are missing (legacy overview pages), the narrative is
 * inserted right after the existing stats block — so visual order on the
 * rendered page is: stats → narrative → user prose.
 *
 * <h2>Failure modes</h2>
 * Every failure mode short-circuits gracefully — narrative regen is best-
 * effort decoration, never a blocker:
 * <ul>
 *   <li>Empty KB (no processed raws) → skip silently</li>
 *   <li>No LLM model resolvable → skip silently, log debug</li>
 *   <li>LLM call throws / times out → keep existing narrative, log warn</li>
 *   <li>LLM returns empty / overlong garbage → keep existing narrative</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiNarrativeService {

    public static final String NARRATIVE_START = "<!-- mate:overview:narrative:v1:start -->";
    public static final String NARRATIVE_END = "<!-- mate:overview:narrative:v1:end -->";

    /** Debounce window: a burst of ingests within this period collapses into a single LLM call. */
    private static final long DEBOUNCE_MS = 10_000L;
    /** Hard cap on the LLM-generated narrative length (chars). */
    private static final int MAX_NARRATIVE_CHARS = 600;
    /** How many recent raw titles to feed the LLM as context. */
    private static final int RECENT_SOURCES_LIMIT = 10;
    /** How many existing page titles to feed the LLM as context. */
    private static final int TOP_PAGES_LIMIT = 15;
    /** Spring AI's internal retries are off — wiki has its own. */
    private static final RetryTemplate NO_RETRY = RetryTemplate.builder().maxAttempts(1).build();

    private final WikiPageService pageService;
    private final WikiPageMapper pageMapper;
    private final WikiRawMaterialMapper rawMapper;
    private final WikiKnowledgeBaseService kbService;
    private final WikiScaffoldService scaffoldService;
    private final WikiModelRoutingService modelRoutingService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;

    /** kbId → pending regen task. Coalesces bursts. */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wiki-narrative");
        t.setDaemon(true);
        return t;
    });

    /**
     * Spring fires this AFTER_COMMIT so we never run on a transaction that
     * subsequently rolled back. {@code fallbackExecution=true} keeps it
     * working when the publisher isn't inside a transaction (test paths,
     * imperative ingest from the UI thread).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onKbDirty(WikiKbDirtyEvent event) {
        scheduleRegen(event.getKbId());
    }

    /**
     * Schedule (or re-schedule) a narrative regen. Cancels any in-flight
     * scheduled task for the same KB so a burst of N ingests produces one
     * LLM call, not N. Public so admin endpoints / manual rebuild can call
     * directly.
     */
    public void scheduleRegen(Long kbId) {
        if (kbId == null) return;
        pending.compute(kbId, (k, existing) -> {
            if (existing != null) existing.cancel(false);
            return scheduler.schedule(() -> runRegen(k), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        });
    }

    private void runRegen(Long kbId) {
        try {
            regenerateNow(kbId);
        } catch (Exception e) {
            log.warn("[WikiNarrative] Regen failed for kbId={}: {}", kbId, e.getMessage());
        } finally {
            pending.remove(kbId);
        }
    }

    /**
     * Synchronous regeneration — exposed so an admin endpoint or test can
     * call without going through the debouncer.
     */
    public void regenerateNow(Long kbId) {
        if (kbId == null) return;

        WikiPageEntity overview = pageService.getBySlug(kbId, WikiScaffoldService.OVERVIEW_SLUG);
        if (overview == null) {
            scaffoldService.ensureScaffold(kbId);
            overview = pageService.getBySlug(kbId, WikiScaffoldService.OVERVIEW_SLUG);
            if (overview == null) {
                log.debug("[WikiNarrative] No overview page for kbId={} after scaffold, skipping", kbId);
                return;
            }
        }

        List<WikiRawMaterialEntity> recentRaws = rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .isNotNull(WikiRawMaterialEntity::getLastProcessedAt)
                        .orderByDesc(WikiRawMaterialEntity::getLastProcessedAt)
                        .last("LIMIT " + RECENT_SOURCES_LIMIT));
        if (recentRaws == null || recentRaws.isEmpty()) {
            // Empty KB — nothing to summarise. Leave whatever is in the markers.
            log.debug("[WikiNarrative] No processed sources for kbId={}, skipping", kbId);
            return;
        }

        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        String kbTitle = (kb != null && kb.getName() != null) ? kb.getName() : ("KB #" + kbId);

        // Top pages = most recent non-system pages by update time. listByKbId
        // already excludes archived; we slice to TOP_PAGES_LIMIT after a
        // fresh sort because the underlying default order isn't guaranteed.
        List<WikiPageEntity> kbPages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getPageType, WikiScaffoldService.SYSTEM_PAGE_TYPE)
                        .orderByDesc(WikiPageEntity::getUpdateTime)
                        .last("LIMIT " + TOP_PAGES_LIMIT));

        String currentNarrative = extractNarrative(overview.getContent());

        ChatModel chatModel = resolveChatModel(kbId);
        if (chatModel == null) {
            log.debug("[WikiNarrative] No chat model resolvable for kbId={}, skipping", kbId);
            return;
        }

        String narrative;
        try {
            Prompt prompt = buildPrompt(kbTitle, recentRaws, kbPages, currentNarrative);
            String raw = chatModel.call(prompt).getResult().getOutput().getText();
            narrative = sanitize(raw);
        } catch (Exception e) {
            log.warn("[WikiNarrative] LLM call failed for kbId={}: {}", kbId, e.getMessage());
            return;
        }
        if (narrative == null || narrative.isBlank()) {
            log.debug("[WikiNarrative] LLM returned blank narrative for kbId={}, keeping existing", kbId);
            return;
        }

        // Re-read the page right before write so we don't clobber a concurrent
        // stats refresh from WikiOverviewService.
        WikiPageEntity fresh = pageService.getBySlug(kbId, WikiScaffoldService.OVERVIEW_SLUG);
        if (fresh == null) return;
        String spliced = spliceNarrative(fresh.getContent(), narrative);
        if (spliced.equals(fresh.getContent())) return;
        fresh.setContent(spliced);
        pageMapper.updateById(fresh);
        log.info("[WikiNarrative] Refreshed narrative for kbId={} ({} chars)", kbId, narrative.length());
    }

    // ------------------------------------------------------------------
    // Helpers — visible for testing
    // ------------------------------------------------------------------

    String extractNarrative(String content) {
        if (content == null) return "";
        int s = content.indexOf(NARRATIVE_START);
        int e = content.indexOf(NARRATIVE_END);
        if (s < 0 || e < 0 || e < s) return "";
        String inner = content.substring(s + NARRATIVE_START.length(), e).trim();
        return inner;
    }

    String spliceNarrative(String content, String narrative) {
        String generated = NARRATIVE_START + "\n" + narrative.trim() + "\n" + NARRATIVE_END;
        if (content == null || content.isEmpty()) {
            return "# Overview\n\n" + generated + "\n";
        }
        int start = content.indexOf(NARRATIVE_START);
        int end = content.indexOf(NARRATIVE_END);
        if (start >= 0 && end > start) {
            return content.substring(0, start)
                    + generated
                    + content.substring(end + NARRATIVE_END.length());
        }
        // Markers missing — drop the narrative right after the stats block (if
        // present) so visual order is stats → narrative; else append at end.
        int statsEnd = content.indexOf(WikiOverviewService.MARKER_END);
        if (statsEnd >= 0) {
            int splitAt = statsEnd + WikiOverviewService.MARKER_END.length();
            String before = content.substring(0, splitAt);
            String after = content.substring(splitAt);
            String prefix = before.endsWith("\n") ? before : before + "\n";
            String suffix = after.startsWith("\n") ? after : "\n" + after;
            return prefix + "\n" + generated + suffix;
        }
        String trimmed = content.endsWith("\n") ? content : content + "\n";
        return trimmed + "\n" + generated + "\n";
    }

    String sanitize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // Strip stray markdown code fences the model sometimes wraps even though
        // the system prompt forbids them.
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        // Collapse internal newlines — narrative is supposed to be a single paragraph.
        s = s.replaceAll("\\s*\\n+\\s*", " ").trim();
        if (s.length() > MAX_NARRATIVE_CHARS) {
            s = s.substring(0, MAX_NARRATIVE_CHARS).trim() + "…";
        }
        return s;
    }

    private Prompt buildPrompt(String kbTitle,
                               List<WikiRawMaterialEntity> recentRaws,
                               List<WikiPageEntity> topPages,
                               String currentNarrative) {
        StringBuilder sources = new StringBuilder();
        for (WikiRawMaterialEntity r : recentRaws) {
            String t = r.getTitle() == null || r.getTitle().isBlank()
                    ? ("source #" + r.getId()) : r.getTitle();
            sources.append("- ").append(t)
                   .append(" (").append(r.getSourceType() == null ? "?" : r.getSourceType()).append(")\n");
        }
        StringBuilder pages = new StringBuilder();
        if (topPages != null) {
            for (WikiPageEntity p : topPages) {
                if (p.getTitle() == null || p.getTitle().isBlank()) continue;
                pages.append("- ").append(p.getTitle()).append('\n');
            }
        }
        if (pages.length() == 0) pages.append("_(无)_\n");
        String narrative = (currentNarrative == null || currentNarrative.isBlank())
                ? "_(尚无)_" : currentNarrative;

        String system = PromptLoader.loadPrompt("wiki/narrative-system");
        String userTemplate = PromptLoader.loadPrompt("wiki/narrative-user");
        String user = userTemplate
                .replace("{kb_title}", kbTitle)
                .replace("{recent_sources}", sources.toString().trim())
                .replace("{top_pages}", pages.toString().trim())
                .replace("{current_narrative}", narrative);

        return new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));
    }

    private ChatModel resolveChatModel(Long kbId) {
        try {
            Long modelId = modelRoutingService.selectModelId(kbId, "heavy_ingest", WikiJobStep.SUMMARY);
            ModelConfigEntity model = modelConfigService.getModel(modelId);
            if (model != null) {
                return agentGraphBuilder.buildRuntimeChatModel(model, NO_RETRY);
            }
        } catch (Exception e) {
            log.debug("[WikiNarrative] Model routing failed for kbId={}: {}", kbId, e.getMessage());
        }
        return null;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
