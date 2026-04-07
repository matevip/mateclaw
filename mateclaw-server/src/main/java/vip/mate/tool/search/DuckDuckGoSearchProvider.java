package vip.mate.tool.search;

import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DuckDuckGo 搜索提供商 — 无需 API Key（keyless fallback）
 *
 * <p>通过 DuckDuckGo HTML 端点实现免 key 搜索。
 * 作为零配置兜底方案，结果质量不如 Serper/Tavily 但"总比没有强"。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class DuckDuckGoSearchProvider implements SearchProvider {

    private static final String SEARCH_URL = "https://html.duckduckgo.com/html/";

    // 匹配 DuckDuckGo HTML 搜索结果
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.+?)</a>",
            Pattern.DOTALL);
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "<a[^>]+class=\"result__snippet\"[^>]*>(.+?)</a>",
            Pattern.DOTALL);
    private static final Pattern RESULT_BLOCK_PATTERN = Pattern.compile(
            "<div[^>]+class=\"result results_links[^\"]*\"[^>]*>(.*?)</div>\\s*(?=<div[^>]+class=\"result |$)",
            Pattern.DOTALL);

    @Override
    public String id() {
        return "duckduckgo";
    }

    @Override
    public String label() {
        return "DuckDuckGo";
    }

    @Override
    public boolean requiresCredential() {
        return false;
    }

    @Override
    public int autoDetectOrder() {
        return 100;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        // 通过 duckduckgoEnabled 控制，默认启用
        return !Boolean.FALSE.equals(config.getDuckduckgoEnabled());
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

        // DuckDuckGo 在部分网络环境下可能出现 SSLHandshakeException，加一次重试
        String response = null;
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                response = HttpUtil.createPost(SEARCH_URL)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "text/html")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                        .body("q=" + encoded)
                        .timeout(15000)
                        .execute()
                        .body();
                break; // 成功则跳出
            } catch (Exception e) {
                log.warn("DuckDuckGo 请求失败 (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    throw e; // 最后一次仍失败则抛出，由 WebSearchService 捕获
                }
                try {
                    Thread.sleep(1000); // 短暂等待后重试
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("DuckDuckGo 搜索被中断", ie);
                }
            }
        }

        log.debug("DuckDuckGo search completed for '{}', response length: {}", query,
                response != null ? response.length() : 0);
        return parseHtmlResults(response);
    }

    private List<SearchResult> parseHtmlResults(String html) {
        List<SearchResult> results = new ArrayList<>();
        if (html == null || html.isBlank()) return results;

        // 提取每个结果块
        Matcher titleMatcher = RESULT_PATTERN.matcher(html);
        Matcher snippetMatcher = SNIPPET_PATTERN.matcher(html);

        int count = 0;
        while (titleMatcher.find() && count < 5) {
            String rawUrl = titleMatcher.group(1);
            String rawTitle = titleMatcher.group(2);

            String url = decodeUrl(rawUrl);
            String title = stripHtml(rawTitle);

            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = stripHtml(snippetMatcher.group(1));
            }

            if (title.isBlank() && snippet.isBlank()) continue;

            results.add(SearchResult.builder()
                    .title(title)
                    .url(url)
                    .snippet(snippet)
                    .source(extractDomain(url))
                    .providerId(id())
                    .build());
            count++;
        }

        log.debug("DuckDuckGo 解析到 {} 条结果", results.size());
        return results;
    }

    /**
     * DuckDuckGo 返回的 URL 经过跳转编码 (//duckduckgo.com/l/?uddg=...)，需要解码
     */
    private String decodeUrl(String rawUrl) {
        if (rawUrl == null) return "";
        // DuckDuckGo 使用 redirect URL
        if (rawUrl.contains("uddg=")) {
            try {
                String encoded = rawUrl.substring(rawUrl.indexOf("uddg=") + 5);
                int ampIndex = encoded.indexOf('&');
                if (ampIndex > 0) encoded = encoded.substring(0, ampIndex);
                return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("URL 解码失败: {}", rawUrl);
            }
        }
        return rawUrl;
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"").replaceAll("&#39;", "'")
                .trim();
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
