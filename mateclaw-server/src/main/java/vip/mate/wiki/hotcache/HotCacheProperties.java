package vip.mate.wiki.hotcache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Tunables for the wiki hot-cache rebuild pipeline. Defaults match the
 * "ship cautiously" preset — short rebuild window, conservative LLM
 * timeout, modest input slice.
 *
 * <p>Override per-environment via {@code mateclaw.wiki.hot-cache.*}.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mateclaw.wiki.hot-cache")
public class HotCacheProperties {

    /** Minimum gap between two LLM-driven rebuilds for the same KB. */
    private Duration debounce = Duration.ofMinutes(5);

    /** Time window for "recent" log/page entries fed to the LLM. */
    private Duration recentWindow = Duration.ofHours(24);

    /** Hard cap on rendered hot cache body length (post-LLM truncation point). */
    private int maxChars = 4096;

    /** Cap on number of recent created/updated pages fed to the prompt (each side). */
    private int maxRecentPages = 10;

    /** Cap on chars from the previous hot cache body fed back as context. */
    private int previousContentCap = 1500;

    /** Cap on chars from the KB activity log markdown fed to the prompt. */
    private int logExcerptCap = 2000;
}
