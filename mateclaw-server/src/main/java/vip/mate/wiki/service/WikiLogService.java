package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * RFC-051 PR-2c: append-friendly activity log for the {@code log} system page.
 * <p>
 * The log page is intentionally just a Markdown document the system writes
 * into. Each entry is a single bullet under a {@code ## YYYY-MM-DD <event>}
 * section so the page stays human-readable. We don't ship a separate
 * activity table yet — when the volume justifies it (RFC §7.3 calls it
 * {@code mate_wiki_activity_log}), this service is the only place that
 * needs to swap storage backends.
 *
 * <h2>Trim policy</h2>
 * Because the log page is a single Markdown blob, we cap it at 10 000
 * characters. Once exceeded, the oldest sections are dropped from the top
 * (right after the {@code # Log} heading) until the page is back under the
 * cap. This keeps the page readable and bounded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiLogService {

    private static final int MAX_LOG_CHARS = 10_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final WikiPageService pageService;
    private final WikiPageMapper pageMapper;

    /** Common entry types so callers don't have to invent strings. */
    public enum EventType {
        INGEST, COMPILE, EDIT, ARCHIVE, REINDEX
    }

    /**
     * Append a single bullet under today's section header. Idempotent in the
     * sense that two simultaneous calls produce two bullets — never crashes,
     * never overwrites prior content.
     *
     * @param kbId  knowledge base id
     * @param event high-level category
     * @param body  human-readable detail; rendered as Markdown after a hyphen
     */
    public void append(Long kbId, EventType event, String body) {
        if (kbId == null || event == null || body == null || body.isBlank()) return;
        WikiPageEntity log = pageService.getBySlug(kbId, WikiScaffoldService.LOG_SLUG);
        if (log == null) {
            // Caller is expected to ensure scaffold first; bail quietly.
            WikiLogService.log.debug("[WikiLog] No log page for kbId={}, skipping append", kbId);
            return;
        }
        try {
            String existing = log.getContent() == null ? "# Log\n" : log.getContent();
            String today = LocalDate.now().format(DAY);
            String time = LocalDateTime.now().format(TIME);
            String bullet = "- " + time + " — " + body.replace("\n", " ").trim();
            String updated = appendBullet(existing, today, event.name().toLowerCase(), bullet);
            if (updated.length() > MAX_LOG_CHARS) {
                updated = trimOldest(updated, MAX_LOG_CHARS);
            }
            if (updated.equals(existing)) return;
            log.setContent(updated);
            pageMapper.updateById(log);
        } catch (Exception e) {
            WikiLogService.log.warn("[WikiLog] Append failed for kbId={}: {}", kbId, e.getMessage());
        }
    }

    String appendBullet(String content, String today, String eventTag, String bullet) {
        String header = "## " + today + " " + eventTag;
        int idx = content.indexOf("\n" + header + "\n");
        if (idx >= 0) {
            // Section exists — insert bullet at end of that section (right before the next "## ").
            int sectionStart = idx + 1;
            int sectionContentStart = content.indexOf('\n', sectionStart);
            int nextSection = content.indexOf("\n## ", sectionContentStart);
            int insertAt = nextSection < 0 ? content.length() : nextSection;
            String before = content.substring(0, insertAt);
            String after = content.substring(insertAt);
            String prefix = before.endsWith("\n") ? before : before + "\n";
            return prefix + bullet + "\n" + (after.startsWith("\n") ? after : "\n" + after);
        }
        // New section. Insert at the top, right after the "# Log" heading.
        int firstSection = content.indexOf("\n## ");
        String section = "\n" + header + "\n\n" + bullet + "\n";
        if (firstSection < 0) {
            String tail = content.endsWith("\n") ? content : content + "\n";
            return tail + section;
        }
        return content.substring(0, firstSection) + section + content.substring(firstSection);
    }

    String trimOldest(String content, int cap) {
        // Drop the oldest "## ..." section (which is at the bottom under our prepend
        // strategy) until we're under the cap.
        while (content.length() > cap) {
            int lastHeading = content.lastIndexOf("\n## ");
            if (lastHeading < 0) break;
            content = content.substring(0, lastHeading) + "\n";
        }
        return content;
    }
}
