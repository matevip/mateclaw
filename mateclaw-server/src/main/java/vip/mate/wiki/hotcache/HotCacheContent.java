package vip.mate.wiki.hotcache;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * In-memory value object for one hot cache snapshot — four sections plus
 * an "updated" timestamp that ride together into the agent system prompt.
 *
 * <p>Wire shape on disk is the rendered markdown produced by
 * {@link #toMarkdown()}; the structured form is preserved here so the
 * updater LLM can return JSON-shaped sections that are validated, scored,
 * and rendered uniformly across versions.
 *
 * <p>Example rendering:
 * <pre>
 * ---
 * type: meta
 * updated: 2026-05-02T08:30:00Z
 * ---
 *
 * ## Last Updated
 * 2026-05-02 — Ingested 3 papers on RedLock; flagged contradiction with paxos-comparison page
 *
 * ## Key Recent Facts
 * - RedLock has known safety issues under network partition (Kleppmann 2016)
 * - Internal Redis 7.4 release notes confirm scheduled deprecation in 8.0
 *
 * ## Recent Changes
 * - Created: [[redlock-safety-analysis]]
 * - Updated: [[distributed-locks]], [[paxos-comparison]]
 *
 * ## Active Threads
 * - Open question: should we recommend ZooKeeper for new services?
 * </pre>
 */
@Data
@Builder
public class HotCacheContent {

    private Instant updatedAt;

    /** Single-paragraph "what happened most recently" headline. */
    private String lastUpdatedSummary;

    @Builder.Default private List<String> keyRecentFacts = List.of();
    @Builder.Default private List<String> recentChanges = List.of();
    @Builder.Default private List<String> activeThreads = List.of();

    /**
     * Renders the structured snapshot to its on-disk markdown form. Empty
     * sections render as {@code (none)} so the output remains readable when
     * a rebuild produces less than a full set of sections.
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("type: meta\n");
        sb.append("updated: ").append(updatedAt != null ? updatedAt.toString() : "unknown").append("\n");
        sb.append("---\n\n");

        sb.append("## Last Updated\n");
        sb.append(lastUpdatedSummary != null && !lastUpdatedSummary.isBlank()
                ? lastUpdatedSummary : "(no recent activity)").append("\n\n");

        appendBulletSection(sb, "Key Recent Facts", keyRecentFacts);
        appendBulletSection(sb, "Recent Changes", recentChanges);
        appendBulletSection(sb, "Active Threads", activeThreads);

        return sb.toString();
    }

    private static void appendBulletSection(StringBuilder sb, String heading, List<String> bullets) {
        sb.append("## ").append(heading).append('\n');
        if (bullets == null || bullets.isEmpty()) {
            sb.append("(none)\n\n");
            return;
        }
        for (String bullet : bullets) {
            sb.append("- ").append(bullet).append('\n');
        }
        sb.append('\n');
    }
}
