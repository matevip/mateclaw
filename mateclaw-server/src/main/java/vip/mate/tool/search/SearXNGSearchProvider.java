package vip.mate.tool.search;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SearXNG 搜索提供商 — 无需 API Key，但需要 base URL（自部署实例）
 *
 * <p>SearXNG 是开源的元搜索引擎，可自部署。Docker 部署 MateClaw 时自动包含 SearXNG 服务，
 * 默认 base URL 为 {@code http://searxng:8080}。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class SearXNGSearchProvider implements SearchProvider {

    @Override
    public String id() {
        return "searxng";
    }

    @Override
    public String label() {
        return "SearXNG";
    }

    @Override
    public boolean requiresCredential() {
        return false;
    }

    @Override
    public int autoDetectOrder() {
        // SearXNG 聚合多引擎，结果质量高于 DuckDuckGo（order=100），应优先
        return 50;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        String baseUrl = config.getSearxngBaseUrl();
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        return search(SearchQuery.of(query), config);
    }

    @Override
    public List<SearchResult> search(SearchQuery searchQuery, SystemSettingsDTO config) {
        String baseUrl = config.getSearxngBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String encoded = URLEncoder.encode(searchQuery.query(), StandardCharsets.UTF_8);
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
                .append("/search?q=").append(encoded)
                .append("&format=json&categories=general");

        // SearXNG language: language 参数
        if (searchQuery.hasLanguage()) {
            urlBuilder.append("&language=").append(URLEncoder.encode(searchQuery.language(), StandardCharsets.UTF_8));
        } else {
            urlBuilder.append("&language=auto");
        }
        // SearXNG freshness: time_range 参数
        if (searchQuery.hasFreshness()) {
            urlBuilder.append("&time_range=").append(searchQuery.freshness().toLowerCase());
        }

        var resp = HttpUtil.createGet(urlBuilder.toString())
                .header("Accept", "application/json")
                .timeout(15000)
                .execute();
        int status = resp.getStatus();
        String response = resp.body();
        String contentType = resp.header("Content-Type");

        log.debug("SearXNG response for '{}': status={}, contentType={}, length={}",
                searchQuery.query(), status, contentType, response != null ? response.length() : 0);
        return parseResponse(response, status, contentType, searchQuery.resolvedCount(), urlBuilder.toString());
    }

    private List<SearchResult> parseResponse(String response, int status, String contentType,
                                             int limit, String requestUrl) {
        List<SearchResult> results = new ArrayList<>();
        if (response == null || response.isBlank()) {
            log.warn("SearXNG returned empty body (status={}, url={})", status, requestUrl);
            return results;
        }
        if (status >= 400) {
            log.warn("SearXNG returned HTTP {} — preview: {}", status, preview(response));
            return results;
        }
        if (contentType != null && !contentType.contains("json")) {
            // Most common cause: settings.yml has no `json` under search.formats
            // or the Limiter plugin rewrote the response to HTML.
            log.warn("SearXNG did not return JSON (contentType={}). Check settings.yml has search.formats including 'json' and server.limiter: false. Preview: {}",
                    contentType, preview(response));
            return results;
        }

        try {
            JSONObject json = JSONUtil.parseObj(response);
            JSONArray items = json.getJSONArray("results");
            if (items == null || items.isEmpty()) return results;

            limit = Math.min(items.size(), limit);
            for (int i = 0; i < limit; i++) {
                JSONObject item = items.getJSONObject(i);
                String itemUrl = item.getStr("url");
                results.add(SearchResult.builder()
                        .title(item.getStr("title"))
                        .url(itemUrl)
                        .snippet(item.getStr("content"))
                        .source(extractDomain(itemUrl))
                        .date(item.getStr("publishedDate"))
                        .providerId(id())
                        .build());
            }
        } catch (Exception e) {
            log.warn("SearXNG parse failed: {} — preview: {}", e.getMessage(), preview(response));
        }
        return results;
    }

    private static String preview(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
