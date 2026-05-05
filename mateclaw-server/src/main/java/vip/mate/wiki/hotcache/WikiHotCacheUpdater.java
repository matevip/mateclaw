package vip.mate.wiki.hotcache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.metrics.WikiMetrics;
import vip.mate.wiki.model.WikiHotCacheEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiHotCacheMapper;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiScaffoldService;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Performs one LLM-driven hot cache rebuild for a single KB. Caller
 * (typically {@link HotCacheUpdateScheduler}) handles debounce + per-KB
 * locking; this class is otherwise stateless and idempotent.
 *
 * <p>Pipeline: gather inputs (previous body + activity log excerpt +
 * recent creates / updates) → build a system+user prompt pair → resolve a
 * KB-routed chat model the same way the wiki narrative refresher does →
 * call → truncate to the configured cap → diff against the existing
 * content hash → write only if changed.
 *
 * <p>Failure modes (flag off, model resolution miss, LLM error) all
 * surface as warnings in the row's {@code last_rebuild_error}. They never
 * propagate up — the triggering event must complete regardless of cache
 * health.
 */
@Slf4j
@Service
public class WikiHotCacheUpdater {

    private static final String FLAG = "wiki.hot_cache.enabled";
    private static final RetryTemplate NO_RETRY = RetryTemplate.builder().maxAttempts(1).build();

    private final WikiHotCacheService cacheService;
    private final WikiHotCacheMapper mapper;
    private final HotCacheRebuildPromptBuilder promptBuilder;
    private final HotCacheProperties props;
    private final WikiModelRoutingService modelRoutingService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final FeatureFlagService featureFlagService;
    private final WikiMetrics metrics;
    private final WikiPageService pageService;

    public WikiHotCacheUpdater(WikiHotCacheService cacheService,
                               WikiHotCacheMapper mapper,
                               HotCacheRebuildPromptBuilder promptBuilder,
                               HotCacheProperties props,
                               WikiModelRoutingService modelRoutingService,
                               ModelConfigService modelConfigService,
                               AgentGraphBuilder agentGraphBuilder,
                               FeatureFlagService featureFlagService,
                               WikiMetrics metrics,
                               WikiPageService pageService) {
        this.cacheService = cacheService;
        this.mapper = mapper;
        this.promptBuilder = promptBuilder;
        this.props = props;
        this.modelRoutingService = modelRoutingService;
        this.modelConfigService = modelConfigService;
        this.agentGraphBuilder = agentGraphBuilder;
        this.featureFlagService = featureFlagService;
        this.metrics = metrics;
        this.pageService = pageService;
    }

    /**
     * Rebuild the hot cache row for {@code kbId}. Safe to call concurrently;
     * the scheduler holds the lock that makes it serial per KB.
     */
    public void rebuild(Long kbId, HotCacheUpdateReason reason) {
        if (kbId == null) return;
        if (!featureFlagService.isEnabledForKb(FLAG, kbId)) {
            log.debug("[HotCache][kb={}] flag disabled; skip rebuild reason={}", kbId, reason);
            return;
        }

        Instant start = Instant.now();
        markRebuildStarted(kbId);

        try {
            // 1. Inputs.
            String prevContent = cacheService.getContentOrNull(kbId);
            LocalDateTime since = LocalDateTime.now().minus(props.getRecentWindow());
            List<WikiPageEntity> recentCreates =
                    pageService.findRecentCreated(kbId, since, props.getMaxRecentPages());
            List<WikiPageEntity> recentUpdates =
                    pageService.findRecentUpdated(kbId, since, props.getMaxRecentPages());
            String logExcerpt = readLogExcerpt(kbId);

            // 2. Cheap exit: if the KB has had no activity at all in the
            //    window, skip the LLM call entirely (the snapshot would be
            //    no different from what's already there).
            if (recentCreates.isEmpty() && recentUpdates.isEmpty()
                    && (logExcerpt == null || logExcerpt.isBlank())) {
                log.debug("[HotCache][kb={}] no recent activity; skip rebuild reason={}", kbId, reason);
                clearRebuildMarker(kbId);
                return;
            }

            // 3. Resolve a chat model for this KB.
            ChatModel chatModel = resolveChatModel(kbId);
            if (chatModel == null) {
                log.debug("[HotCache][kb={}] no chat model resolvable; skip rebuild", kbId);
                recordError(kbId, "no chat model resolvable",
                        Duration.between(start, Instant.now()).toMillis());
                return;
            }

            // 4. Build prompts + call LLM.
            String system = promptBuilder.buildSystem();
            String user = promptBuilder.buildUser(prevContent, logExcerpt, recentCreates, recentUpdates);
            Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));
            String rendered = chatModel.call(prompt).getResult().getOutput().getText();

            // 5. Truncate + persist.
            String truncated = truncate(rendered, props.getMaxChars());
            if (truncated == null || truncated.isBlank()) {
                log.warn("[HotCache][kb={}] LLM returned empty body; treating as failure", kbId);
                recordError(kbId, "LLM returned empty body",
                        Duration.between(start, Instant.now()).toMillis());
                return;
            }
            String hash = sha256(truncated);
            persistRebuild(kbId, truncated, hash, reason, start);

            metrics.recordCompileStage("hot-cache-rebuild", kbId,
                    Duration.between(start, Instant.now()));
            log.info("[HotCache][kb={}] rebuilt; reason={} chars={} duration_ms={}",
                    kbId, reason, truncated.length(),
                    Duration.between(start, Instant.now()).toMillis());

        } catch (Exception e) {
            log.warn("[HotCache][kb={}] rebuild failed; reason={}: {}",
                    kbId, reason, e.getMessage(), e);
            recordError(kbId, e.getMessage(),
                    Duration.between(start, Instant.now()).toMillis());
            // Intentionally swallow — caller's event must complete.
        }
    }

    private void persistRebuild(Long kbId, String content, String hash,
                                HotCacheUpdateReason reason, Instant start) {
        WikiHotCacheEntity row = cacheService.findByKb(kbId).orElseGet(() -> {
            WikiHotCacheEntity fresh = new WikiHotCacheEntity();
            fresh.setKbId(kbId);
            fresh.setRebuildCount(0L);
            return fresh;
        });

        boolean unchanged = hash.equals(row.getContentHash());
        if (!unchanged) {
            row.setContent(content);
            row.setContentHash(hash);
            row.setRebuildCount((row.getRebuildCount() == null ? 0L : row.getRebuildCount()) + 1);
        }
        row.setLastUpdated(LocalDateTime.now());
        row.setUpdateReason(reason.name());
        row.setLastRebuildDurationMs(Duration.between(start, Instant.now()).toMillis());
        row.setLastRebuildError(null);

        if (row.getId() == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        if (unchanged) {
            log.debug("[HotCache][kb={}] body unchanged; only timestamps refreshed", kbId);
        }
    }

    /**
     * Reads the most recent slice of the KB's activity log markdown page
     * (created by {@code WikiLogService.append}). Bounded to
     * {@link HotCacheProperties#getLogExcerptCap()} chars from the tail —
     * older sections are dropped. Returns null if the log page is absent.
     */
    private String readLogExcerpt(Long kbId) {
        try {
            WikiPageEntity log = pageService.getBySlug(kbId, WikiScaffoldService.LOG_SLUG);
            if (log == null) return null;
            String content = log.getContent();
            if (content == null) return null;
            int cap = props.getLogExcerptCap();
            if (content.length() <= cap) return content;
            // Tail: latest entries are at the bottom of the markdown.
            return "…\n" + content.substring(content.length() - cap);
        } catch (Exception e) {
            log.debug("[HotCache][kb={}] log page read failed: {}", kbId, e.getMessage());
            return null;
        }
    }

    private ChatModel resolveChatModel(Long kbId) {
        try {
            Long modelId = modelRoutingService.selectModelId(kbId, "hot_cache_rebuild", WikiJobStep.SUMMARY);
            ModelConfigEntity model = modelConfigService.getModel(modelId);
            if (model == null) return null;
            return agentGraphBuilder.buildRuntimeChatModel(model, NO_RETRY);
        } catch (Exception e) {
            log.debug("[HotCache][kb={}] model routing failed: {}", kbId, e.getMessage());
            return null;
        }
    }

    private void markRebuildStarted(Long kbId) {
        cacheService.findByKb(kbId).ifPresent(row -> {
            row.setLastRebuildStartedAt(LocalDateTime.now());
            mapper.updateById(row);
        });
    }

    private void clearRebuildMarker(Long kbId) {
        cacheService.findByKb(kbId).ifPresent(row -> {
            row.setLastRebuildStartedAt(null);
            mapper.updateById(row);
        });
    }

    private void recordError(Long kbId, String error, long durationMs) {
        cacheService.findByKb(kbId).ifPresent(row -> {
            row.setLastRebuildError(error != null && error.length() > 500
                    ? error.substring(0, 500) : error);
            row.setLastRebuildDurationMs(durationMs);
            row.setLastRebuildStartedAt(null);
            mapper.updateById(row);
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "no-hash";
        }
    }
}
