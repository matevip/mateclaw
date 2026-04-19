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
 * Probes DashScope by hitting its OpenAI-compatible {@code /v1/models}
 * endpoint at {@code https://dashscope.aliyuncs.com/compatible-mode}.
 * The native DashScope protocol has no equivalent free endpoint, but the
 * compatible-mode listing is free and authenticates the same API key. The
 * runtime native chat path still uses the native endpoint; this is purely
 * a liveness probe.
 */
@Slf4j
@Component
public class DashScopeListModelsProbe implements ProviderProbeStrategy {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String COMPATIBLE_BASE = "https://dashscope.aliyuncs.com/compatible-mode";

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.DASHSCOPE_NATIVE;
    }

    @Override
    public ProbeResult probe(ModelProviderEntity provider) {
        if (provider == null || !StringUtils.hasText(provider.getApiKey())) {
            return ProbeResult.fail(0, "API key not configured");
        }
        long start = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            RestClient client = RestClient.builder()
                    .baseUrl(COMPATIBLE_BASE)
                    .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey().trim())
                    .build();

            String body = client.get().uri("/v1/models").retrieve().body(String.class);
            long latency = System.currentTimeMillis() - start;
            if (body == null || body.isBlank()) {
                return ProbeResult.fail(latency, "empty body from /v1/models");
            }
            return ProbeResult.ok(latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.debug("[Probe] dashscope /v1/models failed: {}", e.getMessage());
            return ProbeResult.fail(latency, shortMessage(e));
        }
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
