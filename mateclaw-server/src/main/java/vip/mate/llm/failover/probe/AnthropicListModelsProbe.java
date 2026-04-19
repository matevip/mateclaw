package vip.mate.llm.failover.probe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import vip.mate.llm.failover.ProbeResult;
import vip.mate.llm.failover.ProviderProbeStrategy;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Probes Anthropic by calling {@code GET /v1/models} — free, validates the
 * x-api-key header in one round-trip. Default base URL is
 * {@code https://api.anthropic.com} when the provider doesn't override it.
 */
@Slf4j
@Component
public class AnthropicListModelsProbe implements ProviderProbeStrategy {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String DEFAULT_BASE = "https://api.anthropic.com";

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.ANTHROPIC_MESSAGES;
    }

    @Override
    public ProbeResult probe(ModelProviderEntity provider) {
        if (provider == null || !StringUtils.hasText(provider.getApiKey())) {
            return ProbeResult.fail(0, "API key not configured");
        }
        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? normalizeBaseUrl(provider.getBaseUrl())
                : DEFAULT_BASE;
        long start = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader("x-api-key", provider.getApiKey().trim())
                    .defaultHeader("anthropic-version", "2023-06-01")
                    .build();

            String body = client.get().uri("/v1/models").retrieve().body(String.class);
            long latency = System.currentTimeMillis() - start;
            if (body == null || body.isBlank()) {
                return ProbeResult.fail(latency, "empty body from /v1/models");
            }
            return ProbeResult.ok(latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.debug("[Probe] anthropic /v1/models failed: {}", e.getMessage());
            return ProbeResult.fail(latency, shortMessage(e));
        }
    }

    private static String normalizeBaseUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
