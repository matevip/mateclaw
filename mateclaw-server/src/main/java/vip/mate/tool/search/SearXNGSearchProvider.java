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
        String baseUrl = config.getSearxngBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/search?q=" + encoded + "&format=json&categories=general&language=auto";

        String response = HttpUtil.createGet(url)
                .header("Accept", "application/json")
                .timeout(15000)
                .execute()
                .body();

        log.debug("SearXNG result for '{}': length={}", query, response != null ? response.length() : 0);
        return parseResponse(response);
    }

    private List<SearchResult> parseResponse(String response) {
        List<SearchResult> results = new ArrayList<>();
        if (response == null || response.isBlank()) return results;

        try {
            JSONObject json = JSONUtil.parseObj(response);
            JSONArray items = json.getJSONArray("results");
            if (items == null) return results;

            int limit = Math.min(items.size(), 5);
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
            log.warn("SearXNG 结果解析失败: {}", e.getMessage());
        }
        return results;
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
