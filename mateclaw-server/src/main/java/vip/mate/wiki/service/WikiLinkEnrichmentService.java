package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.EnrichmentPlan;
import vip.mate.wiki.dto.EnrichmentReplacement;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * RFC-031 + RFC-051 PR-5b: lightweight wikilink enrichment.
 * <p>
 * Earlier versions asked the LLM for a fully rewritten page and trusted the
 * response if it was "long enough" — which let weak models silently drop
 * paragraphs, translate prose, or rephrase claims while pretending to only
 * add brackets. This rewrite switches to a replacement plan: the LLM
 * proposes wraps, Java applies them surgically, and a round-trip check
 * guarantees the non-link prose is unchanged byte-for-byte.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiLinkEnrichmentService {

    private final WikiPageService pageService;
    private final WikiModelRoutingService routingService;
    private final WikiProperties wikiProperties;
    private final ObjectMapper objectMapper;

    private static final ExecutorService WIKI_EXECUTOR =
        Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Enrich a single page with [[wikilinks]] using a replacement plan.
     */
    public void enrichPage(Long pageId, Long modelId) {
        WikiPageEntity page = pageService.getById(pageId);
        if (page == null || page.getContent() == null) return;

        String index = buildIndexPrompt(page.getKbId());
        ChatModel chatModel = routingService.buildChatModel(modelId);
        applyEnrichment(page, chatModel, index);
    }

    /**
     * Batch-enrich all pages in a KB.
     */
    public void enrichAllPages(Long kbId, Long modelId) {
        List<WikiPageEntity> pages = pageService.listByKbIdWithContent(kbId);
        String index = buildIndexPrompt(kbId);
        Semaphore sem = new Semaphore(wikiProperties.getMaxParallelPhaseBPages());

        for (WikiPageEntity page : pages) {
            sem.acquireUninterruptibly();
            WIKI_EXECUTOR.submit(() -> {
                try {
                    enrichPageWithIndex(page, modelId, index);
                } finally {
                    sem.release();
                }
            });
        }
    }

    private void enrichPageWithIndex(WikiPageEntity page, Long modelId, String index) {
        if (page == null || page.getContent() == null) return;
        ChatModel chatModel = routingService.buildChatModel(modelId);
        applyEnrichment(page, chatModel, index);
    }

    private void applyEnrichment(WikiPageEntity page, ChatModel chatModel, String index) {
        EnrichmentPlan plan = requestPlan(chatModel, page.getContent(), index, page.getSlug());
        if (plan == null || plan.isEmpty()) {
            return; // LLM had nothing to add or call failed; leave page alone.
        }
        WikiEnrichmentApplier.Result result = WikiEnrichmentApplier.apply(page.getContent(), plan);
        if (result instanceof WikiEnrichmentApplier.Result.Rejected rejected) {
            log.warn("[WikiEnrich] Plan rejected for slug={}: {}", page.getSlug(), rejected.reason());
            return;
        }
        if (result instanceof WikiEnrichmentApplier.Result.Unchanged) {
            return;
        }
        if (result instanceof WikiEnrichmentApplier.Result.Applied applied) {
            page.setContent(applied.content());
            page.setOutgoingLinks(pageService.extractLinksAsJson(applied.content()));
            pageService.updateById(page);
            log.info("[WikiEnrich] Applied {} replacements on slug={}", applied.replacementCount(), page.getSlug());
        }
    }

    private EnrichmentPlan requestPlan(ChatModel chatModel, String content, String index, String slug) {
        String systemPrompt = """
            You are a wiki cross-referencing assistant.
            Your ONLY job: emit a JSON replacement plan that wraps existing words/phrases
            with [[wikilinks]] from the supplied wiki index.

            Strict output contract — return ONLY this JSON object, nothing else:
            {
              "replacements": [
                {"original": "<exact substring of the page>",
                 "replacement": "[[<slug>]]" or "[[<slug>|<label>]]",
                 "occurrence": <1-based int>}
              ]
            }

            Rules — replacements that violate any rule will be rejected by the server:
            - Do NOT rewrite, translate, summarize, or add prose. You are only allowed to wrap.
            - The "replacement" string must contain exactly ONE wikilink and nothing else.
            - The visible text of the wikilink (the slug, or the label after |) must equal
              "original" byte-for-byte. Casing, punctuation, and whitespace must match.
            - Use slugs from the supplied wiki index. Do not invent new slugs.
            - "occurrence" counts only matches outside any existing [[...]]. Default 1.
            - Skip occurrences that are already inside an existing wikilink.
            - It is fine to return an empty replacements array if nothing fits.
            """;
        String userPrompt = "Wiki Index (slug → title):\n" + index
                + "\n\nPage content (slug=" + slug + "):\n" + content;

        try {
            ChatResponse response = chatModel.call(
                new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
                ))
            );
            String text = response.getResult().getOutput().getText();
            return parsePlan(text);
        } catch (Exception e) {
            log.warn("[WikiEnrich] Plan request failed for slug={}: {}", slug, e.getMessage());
            return null;
        }
    }

    EnrichmentPlan parsePlan(String text) {
        if (text == null || text.isBlank()) return null;
        // Tolerate fenced code blocks and stray prose around the JSON object.
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                trimmed = trimmed.substring(firstNl + 1, lastFence).trim();
            }
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            return planFromJson(root);
        } catch (Exception ignored) {
            int s = trimmed.indexOf('{');
            int e = trimmed.lastIndexOf('}');
            if (s >= 0 && e > s) {
                try {
                    return planFromJson(objectMapper.readTree(trimmed.substring(s, e + 1)));
                } catch (Exception ignored2) {
                    return null;
                }
            }
            return null;
        }
    }

    private EnrichmentPlan planFromJson(JsonNode root) {
        if (root == null) return null;
        JsonNode arr = root.path("replacements");
        if (!arr.isArray()) return new EnrichmentPlan(List.of());
        List<EnrichmentReplacement> out = new ArrayList<>(arr.size());
        for (JsonNode node : arr) {
            String original = node.path("original").asText("");
            String replacement = node.path("replacement").asText("");
            int occurrence = node.path("occurrence").asInt(1);
            if (original.isEmpty() || replacement.isEmpty()) continue;
            out.add(new EnrichmentReplacement(original, replacement, occurrence));
        }
        return new EnrichmentPlan(out);
    }

    private String buildIndexPrompt(Long kbId) {
        return pageService.listSummaries(kbId).stream()
            .filter(p -> !"system".equals(p.getPageType()))
            .map(p -> p.getSlug() + " → " + p.getTitle())
            .collect(Collectors.joining("\n"));
    }
}
