package vip.mate.wiki.hotcache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.wiki.model.WikiPageEntity;

import java.time.Instant;
import java.util.List;

/**
 * Builds the user-facing prompt for one hot-cache rebuild call by
 * substituting placeholders in {@code wiki/hot-cache-rebuild-user.txt}.
 *
 * <p>System prompt is loaded once and held identical across calls — the
 * intent is to keep prompt-cache reuse high once
 * {@code wiki.compile.cache.enabled} is wired in a follow-up PR.
 */
@Component
@RequiredArgsConstructor
public class HotCacheRebuildPromptBuilder {

    private final HotCacheProperties props;

    /**
     * Returns a single user-message body with the placeholders substituted.
     * Inputs are sliced to the configured caps before injection.
     */
    public String buildUser(String previousContent,
                            String logMarkdownExcerpt,
                            List<WikiPageEntity> recentCreates,
                            List<WikiPageEntity> recentUpdates) {
        String template = PromptLoader.loadPrompt("wiki/hot-cache-rebuild-user");
        return template
                .replace("{iso_timestamp}", Instant.now().toString())
                .replace("{recent_window}", props.getRecentWindow().toString())
                .replace("{previous_content}", abbreviate(blankToNone(previousContent), props.getPreviousContentCap()))
                .replace("{log_excerpt}", abbreviate(blankToNone(logMarkdownExcerpt), props.getLogExcerptCap()))
                .replace("{recent_creates}", renderPages(recentCreates))
                .replace("{recent_updates}", renderPages(recentUpdates));
    }

    public String buildSystem() {
        return PromptLoader.loadPrompt("wiki/hot-cache-rebuild-system");
    }

    private static String renderPages(List<WikiPageEntity> pages) {
        if (pages == null || pages.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (WikiPageEntity p : pages) {
            String slug = p.getSlug() == null ? "?" : p.getSlug();
            String title = p.getTitle() == null ? slug : p.getTitle();
            sb.append("- [[").append(slug).append("]] ").append(title).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String blankToNone(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
