package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * RFC-051 PR-2b: deterministic overview rebuilder.
 * <p>
 * The {@code overview} system page wraps an auto-generated stats block
 * inside marker comments:
 *
 * <pre>
 * &lt;!-- mate:overview:v1:start --&gt;
 *   ... rebuilt block ...
 * &lt;!-- mate:overview:v1:end --&gt;
 * </pre>
 *
 * Anything outside the markers is user-authored prose and is preserved
 * verbatim. Inside the markers, this service rewrites a small set of stats
 * derived directly from the database — no LLM, no judgement calls.
 * <p>
 * Hook points: {@code WikiProcessingService.processRawMaterial} on success
 * and {@code WikiCompileService.compilePage} after a compile result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiOverviewService {

    public static final String MARKER_START = "<!-- mate:overview:v1:start -->";
    public static final String MARKER_END = "<!-- mate:overview:v1:end -->";

    private final WikiPageService pageService;
    private final WikiPageMapper pageMapper;
    private final WikiRawMaterialMapper rawMapper;
    private final WikiChunkMapper chunkMapper;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Rebuild the marker region of the overview page for {@code kbId}.
     * No-op when the overview page is missing — call
     * {@code WikiScaffoldService.ensureScaffold} first.
     */
    public void rebuild(Long kbId) {
        if (kbId == null) return;
        WikiPageEntity overview = pageService.getBySlug(kbId, WikiScaffoldService.OVERVIEW_SLUG);
        if (overview == null) {
            log.debug("[WikiOverview] No overview page for kbId={}, skipping rebuild", kbId);
            return;
        }
        try {
            String stats = computeStatsBlock(kbId);
            String rewritten = spliceMarkerRegion(overview.getContent(), stats);
            if (rewritten.equals(overview.getContent())) return;
            overview.setContent(rewritten);
            pageMapper.updateById(overview);
            log.debug("[WikiOverview] Refreshed overview for kbId={}", kbId);
        } catch (Exception e) {
            log.warn("[WikiOverview] Rebuild failed for kbId={}: {}", kbId, e.getMessage());
        }
    }

    private String computeStatsBlock(Long kbId) {
        long rawCount = rawMapper.selectCount(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId));
        // Page count excludes system pages (overview / log themselves).
        long pageCount = pageMapper.selectCount(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getPageType, WikiScaffoldService.SYSTEM_PAGE_TYPE));
        long chunkCount = chunkMapper.selectCount(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, kbId));
        long embeddedChunks = chunkMapper.selectCount(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, kbId)
                        .isNotNull(WikiChunkEntity::getEmbedding));
        long pagesWithLinks = pageMapper.selectCount(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getPageType, WikiScaffoldService.SYSTEM_PAGE_TYPE)
                        .isNotNull(WikiPageEntity::getOutgoingLinks)
                        .ne(WikiPageEntity::getOutgoingLinks, "[]")
                        .ne(WikiPageEntity::getOutgoingLinks, ""));
        WikiRawMaterialEntity latest = rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .isNotNull(WikiRawMaterialEntity::getLastProcessedAt)
                        .orderByDesc(WikiRawMaterialEntity::getLastProcessedAt)
                        .last("LIMIT 1"));
        String lastIngest = (latest != null && latest.getLastProcessedAt() != null)
                ? latest.getLastProcessedAt().format(ISO) : "—";

        long embedPct = chunkCount == 0 ? 0 : Math.round(100.0 * embeddedChunks / chunkCount);
        long linkPct  = pageCount == 0 ? 0 : Math.round(100.0 * pagesWithLinks / pageCount);

        return """
                ## Scope

                - Sources: %d
                - Wiki pages: %d
                - Chunks: %d
                - Last ingest: %s

                ## Coverage

                - Embedding coverage: %d / %d (%d%%)
                - Pages with wikilinks: %d / %d (%d%%)
                """.formatted(
                rawCount, pageCount, chunkCount, lastIngest,
                embeddedChunks, chunkCount, embedPct,
                pagesWithLinks, pageCount, linkPct);
    }

    String spliceMarkerRegion(String content, String newBlock) {
        if (content == null || content.isEmpty()) {
            // No prior overview — synthesize one with both markers.
            return "# Overview\n\n" + MARKER_START + "\n" + newBlock.trim() + "\n" + MARKER_END + "\n";
        }
        int start = content.indexOf(MARKER_START);
        int end = content.indexOf(MARKER_END);
        String generated = MARKER_START + "\n" + newBlock.trim() + "\n" + MARKER_END;
        if (start < 0 || end < 0 || end < start) {
            // Markers missing or scrambled — append the block at the end of the page.
            String trimmed = content.endsWith("\n") ? content : content + "\n";
            return trimmed + "\n" + generated + "\n";
        }
        // Replace the block (markers included) with the freshly generated one.
        return content.substring(0, start)
                + generated
                + content.substring(end + MARKER_END.length());
    }

    LocalDateTime now() { return LocalDateTime.now(); }
}
