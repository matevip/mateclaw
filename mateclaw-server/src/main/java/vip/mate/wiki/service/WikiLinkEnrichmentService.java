package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * RFC-031: Lightweight wikilink enrichment service.
 * Adds [[slug]] cross-references to page content without modifying
 * the actual text. Corresponds to llm_wiki's enrich-wikilinks.ts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiLinkEnrichmentService {

    private final WikiPageService pageService;
    private final WikiModelRoutingService routingService;
    private final WikiProperties wikiProperties;

    private static final ExecutorService WIKI_EXECUTOR =
        Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Enrich a single page with [[wikilinks]].
     */
    public void enrichPage(Long pageId, Long modelId) {
        WikiPageEntity page = pageService.getById(pageId);
        if (page == null || page.getContent() == null) return;

        String index = buildIndexPrompt(page.getKbId());
        ChatModel chatModel = routingService.buildChatModel(modelId);
        String enriched = callEnrichLlm(chatModel, page.getContent(), index);

        if (enriched != null
                && enriched.length() >= page.getContent().length() * wikiProperties.getWikilinkMinContentRatio()) {
            page.setContent(enriched);
            page.setOutgoingLinks(pageService.extractLinksAsJson(enriched));
            pageService.updateById(page);
        }
    }

    /**
     * Batch-enrich all pages in a KB (e.g. after initial ingest).
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
        ChatModel chatModel = routingService.buildChatModel(modelId);
        String enriched = callEnrichLlm(chatModel, page.getContent(), index);
        if (enriched != null
                && enriched.length() >= page.getContent().length() * wikiProperties.getWikilinkMinContentRatio()) {
            page.setContent(enriched);
            page.setOutgoingLinks(pageService.extractLinksAsJson(enriched));
            pageService.updateById(page);
        }
    }

    private String callEnrichLlm(ChatModel model, String content, String index) {
        String systemPrompt = """
            You are a wiki cross-referencing assistant.
            Your ONLY job: add [[slug]] markers around entity and concept names that appear in the wiki index.
            Rules:
            - Do NOT change any content, rewrite sentences, or add new text.
            - Do NOT modify YAML frontmatter.
            - Only wrap existing words/phrases with [[ and ]].
            - Use the exact slug from the wiki index (not the title).
            - Return the COMPLETE page text with [[wikilinks]] added.
            """;
        String userPrompt = "Wiki Index (slug → title):\n" + index + "\n\nPage content:\n" + content;

        try {
            ChatResponse response = model.call(
                new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
                ))
            );
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[WikiEnrich] Failed to enrich page: {}", e.getMessage());
            return null;
        }
    }

    private String buildIndexPrompt(Long kbId) {
        return pageService.listSummaries(kbId).stream()
            .map(p -> p.getSlug() + " → " + p.getTitle())
            .collect(Collectors.joining("\n"));
    }
}
