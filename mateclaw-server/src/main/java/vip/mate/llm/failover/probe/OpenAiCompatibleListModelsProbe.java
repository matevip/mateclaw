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
 * Probes an OpenAI-compatible provider by calling its {@code GET /v1/models}
 * endpoint — free (zero token cost) and authenticates the API key in one
 * round-trip. A 200 response with body present is sufficient to confirm
 * reachability + auth; we don't try to parse the model list because
 * different providers (Kimi / DeepSeek / Moonshot / OpenRouter) have minor
 * schema differences that aren't relevant to a liveness check.
 */
@Slf4j
@Component
public class OpenAiCompatibleListModelsProbe implements ProviderProbeStrategy {

    /** Conservative HTTP timeout — keeps a stalled provider from holding up the parallel batch. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    public ProbeResult probe(ModelProviderEntity provider) {
        if (provider == null || !StringUtils.hasText(provider.getBaseUrl())) {
            return ProbeResult.fail(0, "base URL not configured");
        }
        long start = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            RestClient client = RestClient.builder()
                    .baseUrl(normalizeBaseUrl(provider.getBaseUrl()))
                    .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            RestClient.RequestHeadersSpec<?> spec = client.get().uri("/v1/models");
            String apiKey = provider.getApiKey();
            if (StringUtils.hasText(apiKey)) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
            }

            String body = spec.retrieve().body(String.class);
            long latency = System.currentTimeMillis() - start;
            if (body == null || body.isBlank()) {
                return ProbeResult.fail(latency, "empty body from /v1/models");
            }
            return ProbeResult.ok(latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.debug("[Probe] {} /v1/models failed: {}", provider.getProviderId(), e.getMessage());
            return ProbeResult.fail(latency, shortMessage(e));
        }
    }

    private static String normalizeBaseUrl(String url) {
        // Strip trailing slash for clean URL composition; /v1/models then concatenates correctly.
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
