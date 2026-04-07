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
 * Serper (Google Search) 搜索提供商 — 需要 API Key
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class SerperSearchProvider implements SearchProvider {

    private static final String DEFAULT_BASE_URL = "https://google.serper.dev/search";

    @Override
    public String id() {
        return "serper";
    }

    @Override
    public String label() {
        return "Serper (Google)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 300;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        String key = config.getSerperApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        String apiKey = config.getSerperApiKey();
        String baseUrl = config.getSerperBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }

        String body = JSONUtil.toJsonStr(new JSONObject().set("q", query).set("num", 5));
        String response = HttpUtil.createPost(baseUrl)
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .timeout(15000)
                .execute()
                .body();

        log.debug("Serper result for '{}': {}", query, response);
        return parseResponse(response);
    }

    private List<SearchResult> parseResponse(String response) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JSONObject json = JSONUtil.parseObj(response);
            JSONArray organic = json.getJSONArray("organic");
            if (organic == null) return results;

            for (int i = 0; i < organic.size(); i++) {
                JSONObject item = organic.getJSONObject(i);
                String url = item.getStr("link");
                results.add(SearchResult.builder()
                        .title(item.getStr("title"))
                        .url(url)
                        .snippet(item.getStr("snippet"))
                        .source(extractDomain(url))
                        .date(item.getStr("date"))
                        .providerId(id())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Serper 结果解析失败: {}", e.getMessage());
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
