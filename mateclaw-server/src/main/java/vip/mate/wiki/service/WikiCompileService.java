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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiModelRoutingService;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiChunkMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC-051 PR-4: on-demand page compilation.
 * <p>
 * Given a topic (or an explicit {@code slug}), search for the most relevant
 * chunks via {@link HybridRetriever}, build a short evidence pack from the
 * top hits, and ask the LLM for a single Markdown page. The resulting page
 * is persisted and its citations are bound to the evidence chunk IDs only —
 * not to every chunk of the source raw — so relation signals stay clean.
 * <p>
 * This is the bridge between lazy ingest (where 0 pages is the steady state)
 * and the user/agent saying "now produce a page about X".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiCompileService {

    private final HybridRetriever hybridRetriever;
    private final WikiChunkMapper chunkMapper;
    private final WikiPageService pageService;
    private final WikiCitationService citationService;
    private final ObjectMapper objectMapper;

    /**
     * Optional. When wired we use the routing chain (stepModels[CREATE_PAGE]
     * -&gt; wikiDefaultModelId -&gt; system default); otherwise the caller's
     * pre-built {@link ChatModel} factory wins.
     */
    @Autowired(required = false)
    private WikiModelRoutingService modelRoutingService;

    public record CompileResult(Long pageId, String slug, String title, int evidenceChunkCount,
                                 boolean created) {}

    /**
     * Compile or update a single page on the topic.
     *
     * @param kbId              knowledge base id
     * @param topic             natural-language topic; required
     * @param slug              optional explicit slug; auto-derived from topic when null/blank
     * @param maxEvidenceChunks evidence pack cap (defaults to 8 when null)
     * @return result with the persisted page id and how many chunks were cited
     */
    public CompileResult compilePage(Long kbId, String topic, String slug, Integer maxEvidenceChunks) {
        if (kbId == null) throw new IllegalArgumentException("kbId is required");
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic is required");
        int cap = (maxEvidenceChunks == null || maxEvidenceChunks <= 0)
                ? 8 : Math.min(20, maxEvidenceChunks);

        // 1. Retrieve evidence chunks via semantic search (hybrid retriever).
        List<HybridRetriever.ChunkHit> hits = hybridRetriever.searchChunks(kbId, topic, cap);
        if (hits.isEmpty()) {
            throw new IllegalStateException("No evidence chunks found for topic: " + topic);
        }

        List<Long> evidenceChunkIds = new ArrayList<>(hits.size());
        StringBuilder evidenceBlock = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            HybridRetriever.ChunkHit hit = hits.get(i);
            evidenceChunkIds.add(hit.chunkId());
            WikiChunkEntity chunk = chunkMapper.selectById(hit.chunkId());
            if (chunk == null || chunk.getContent() == null) continue;
            evidenceBlock.append("### Evidence ").append(i + 1)
                    .append(" (chunk=").append(hit.chunkId()).append(")");
            if (hit.headerBreadcrumb() != null && !hit.headerBreadcrumb().isBlank()) {
                evidenceBlock.append(" — ").append(hit.headerBreadcrumb());
            }
            if (hit.pageNumber() != null) {
                evidenceBlock.append(" (page ").append(hit.pageNumber()).append(")");
            }
            evidenceBlock.append("\n\n").append(chunk.getContent()).append("\n\n");
        }

        // 2. Resolve slug + title.
        String resolvedSlug = (slug == null || slug.isBlank()) ? WikiPageService.toSlug(topic) : slug;
        if (resolvedSlug == null || resolvedSlug.isBlank()) {
            resolvedSlug = "page-" + System.currentTimeMillis();
        }
        WikiPageEntity existing = pageService.getBySlug(kbId, resolvedSlug);
        if (existing != null && WikiPageService.isProtected(existing)) {
            throw new IllegalStateException("Refusing to compile over protected page: " + resolvedSlug);
        }

        // 3. Build LLM prompt.
        String system = """
                You are a wiki page compiler. Produce a single Markdown page that
                synthesizes the supplied evidence. Constraints:
                - Output ONLY a JSON object: {"title": "...", "summary": "...", "content": "..."}
                - Do not invent facts beyond the evidence.
                - Use [[wikilinks]] when an evidence breadcrumb names a related concept.
                - Keep summary <= 300 characters.
                - content must be Markdown with ## headers.
                """;
        String user = "## Topic\n\n" + topic + "\n\n## Evidence\n\n" + evidenceBlock;

        ChatModel chatModel = resolveChatModel(kbId);
        ChatResponse resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        if (resp == null || resp.getResult() == null
                || resp.getResult().getOutput() == null
                || resp.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("LLM returned no compile output");
        }
        String body = resp.getResult().getOutput().getText();
        JsonNode parsed = parseJson(body);
        if (parsed == null) {
            throw new IllegalStateException("LLM compile output was not valid JSON");
        }
        String title = parsed.path("title").asText(topic);
        String summary = parsed.path("summary").asText("");
        String content = parsed.path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("LLM compile output missing content");
        }

        // 4. Persist (create or update via AI path).
        WikiPageEntity persisted;
        boolean created;
        if (existing == null) {
            persisted = pageService.createPage(kbId, resolvedSlug, title, content, summary, null);
            created = true;
        } else {
            persisted = pageService.updatePageByAi(kbId, resolvedSlug, content, summary, null);
            created = false;
            if (persisted == null) persisted = existing;
        }

        // 5. Bind evidence citations only.
        try {
            citationService.buildCitations(persisted.getId(), kbId, evidenceChunkIds);
        } catch (Exception e) {
            log.warn("[WikiCompile] Failed to attach evidence citations for pageId={}: {}",
                    persisted.getId(), e.getMessage());
        }

        log.info("[WikiCompile] {} page slug={} title='{}' from {} evidence chunks (kbId={})",
                created ? "Created" : "Updated", resolvedSlug, title, evidenceChunkIds.size(), kbId);
        return new CompileResult(persisted.getId(), resolvedSlug, title, evidenceChunkIds.size(), created);
    }

    private ChatModel resolveChatModel(Long kbId) {
        if (modelRoutingService == null) {
            throw new IllegalStateException("ModelRoutingService unavailable; cannot compile");
        }
        Long modelId = modelRoutingService.selectModelId(kbId, "compile_page", WikiJobStep.CREATE_PAGE);
        return modelRoutingService.buildChatModel(modelId);
    }

    private JsonNode parseJson(String text) {
        if (text == null) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            int s = text.indexOf('{');
            int e = text.lastIndexOf('}');
            if (s >= 0 && e > s) {
                try {
                    return objectMapper.readTree(text.substring(s, e + 1));
                } catch (Exception ignored2) {
                    return null;
                }
            }
            return null;
        }
    }
}
