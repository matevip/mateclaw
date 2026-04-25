package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;

/**
 * RFC-051 PR-2: idempotent system-page scaffold for a knowledge base.
 * <p>
 * A KB always has two system pages once {@link #ensureScaffold(Long)} has run:
 * <ul>
 *   <li>{@code overview} — entry point describing scope and recent updates.</li>
 *   <li>{@code log} — append-only ingest / compile / edit record.</li>
 * </ul>
 * Both carry {@code page_type='system'} and {@code locked=1} so neither AI
 * tools nor batch deletes can remove them. The deterministic-rebuild logic
 * for the overview body and the activity-log writer land as follow-ups; this
 * skeleton ships only the create-if-missing path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiScaffoldService {

    public static final String SYSTEM_PAGE_TYPE = "system";
    public static final String OVERVIEW_SLUG = "overview";
    public static final String LOG_SLUG = "log";

    private final WikiPageService pageService;
    private final WikiPageMapper pageMapper;

    /**
     * Make sure both system pages exist for {@code kbId}. Re-creates them with
     * default content if missing; otherwise refreshes their protection flags
     * (in case an earlier version stored them without {@code locked=1}).
     * <p>
     * Safe to call repeatedly: it issues at most two SELECTs and one or two
     * INSERT/UPDATEs. Throws nothing; logs on failure.
     */
    public void ensureScaffold(Long kbId) {
        if (kbId == null) return;
        try {
            ensureSystemPage(kbId, OVERVIEW_SLUG, "Overview", DEFAULT_OVERVIEW);
            ensureSystemPage(kbId, LOG_SLUG, "Log", initialLog());
        } catch (Exception e) {
            log.warn("[WikiScaffold] ensureScaffold failed for kbId={}: {}", kbId, e.getMessage());
        }
    }

    private void ensureSystemPage(Long kbId, String slug, String title, String content) {
        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        if (existing == null) {
            WikiPageEntity created = pageService.createPage(kbId, slug, title, content,
                    summaryOf(content), null, SYSTEM_PAGE_TYPE);
            // createPage doesn't set locked; flip it on now via a targeted update.
            pageMapper.update(null,
                    new LambdaUpdateWrapper<WikiPageEntity>()
                            .eq(WikiPageEntity::getId, created.getId())
                            .set(WikiPageEntity::getLocked, 1));
            log.info("[WikiScaffold] Created system page slug={} for kbId={}", slug, kbId);
            return;
        }
        // Heal previously-created system pages that lack the locked flag.
        boolean needsLockFix = existing.getLocked() == null || existing.getLocked() != 1;
        boolean needsTypeFix = !SYSTEM_PAGE_TYPE.equals(existing.getPageType());
        if (needsLockFix || needsTypeFix) {
            LambdaUpdateWrapper<WikiPageEntity> upd = new LambdaUpdateWrapper<WikiPageEntity>()
                    .eq(WikiPageEntity::getId, existing.getId());
            if (needsLockFix) upd.set(WikiPageEntity::getLocked, 1);
            if (needsTypeFix) upd.set(WikiPageEntity::getPageType, SYSTEM_PAGE_TYPE);
            pageMapper.update(null, upd);
            log.info("[WikiScaffold] Repaired protection flags for slug={} kbId={}", slug, kbId);
        }
    }

    private String summaryOf(String content) {
        if (content == null) return "";
        String oneline = content.replaceAll("\\s+", " ").trim();
        return oneline.length() > 200 ? oneline.substring(0, 200) + "..." : oneline;
    }

    private String initialLog() {
        return "# Log\n\n## " + java.time.LocalDate.now() + " init\n\n- System pages initialized.\n";
    }

    private static final String DEFAULT_OVERVIEW = """
            # Overview

            <!-- mate:overview:v1:start -->
            ## Scope

            - Sources: 0
            - Wiki pages: 0
            - Chunks: 0
            - Last ingest: -

            ## Recent Updates

            No updates yet.

            ## Coverage

            - Pages with citations: 0
            - Pages with wikilinks: 0
            - Isolated pages: 0
            <!-- mate:overview:v1:end -->
            """;
}
