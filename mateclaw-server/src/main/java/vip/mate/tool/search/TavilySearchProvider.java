package vip.mate.tool.search;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 搜索提供商 — 需要 API Key
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class TavilySearchProvider implements SearchProvider {

    private static final String DEFAULT_BASE_URL = "https://api.tavily.com/search";

    @Override
    public String id() {
        return "tavily";
    }

    @Override
    public String label() {
        return "Tavily";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 400;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        String key = config.getTavilyApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        String apiKey = config.getTavilyApiKey();
        String baseUrl = config.getTavilyBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }

        String body = JSONUtil.toJsonStr(new JSONObject()
                .set("query", query)
                .set("max_results", 5)
                .set("api_key", apiKey));
        String response = HttpUtil.createPost(baseUrl)
                .header("Content-Type", "application/json")
                .body(body)
                .timeout(15000)
                .execute()
                .body();

        log.debug("Tavily result for '{}': {}", query, response);
        return parseResponse(response);
    }

    private List<SearchResult> parseResponse(String response) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JSONObject json = JSONUtil.parseObj(response);
            JSONArray items = json.getJSONArray("results");
            if (items == null) return results;

            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                String url = item.getStr("url");
                results.add(SearchResult.builder()
                        .title(item.getStr("title"))
                        .url(url)
                        .snippet(item.getStr("content"))
                        .source(extractDomain(url))
                        .date(item.getStr("published_date"))
                        .providerId(id())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Tavily 结果解析失败: {}", e.getMessage());
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
