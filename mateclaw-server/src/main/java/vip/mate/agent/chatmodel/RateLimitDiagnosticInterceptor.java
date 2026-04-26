package vip.mate.agent.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.List;

/**
 * RFC-062 diagnostic helper: when Anthropic returns 429 we want to see the
 * {@code anthropic-ratelimit-*} headers in the log so we can tell apart the
 * three distinct "rate limit" failure modes that all share the same
 * {@code {"type":"rate_limit_error","message":"Error"}} body:
 *
 * <table>
 *   <caption>How to read the headers</caption>
 *   <tr><th>Failure mode</th><th>tokens-remaining</th><th>retry-after</th></tr>
 *   <tr><td>5h Pro/Max quota exhausted</td><td>0</td><td>thousands of seconds</td></tr>
 *   <tr><td>Anti-abuse fingerprint gate</td><td>large</td><td>tens of seconds</td></tr>
 *   <tr><td>Per-minute burst limit</td><td>large</td><td>single-digit seconds</td></tr>
 * </table>
 *
 * <p>Spring AI logs the body but not the headers, so we'd be debugging blind
 * without this. Sync (RestClient) variant; the WebFlux equivalent lives in
 * {@link RateLimitDiagnosticExchangeFilter}.
 */
@Slf4j
class RateLimitDiagnosticInterceptor implements ClientHttpRequestInterceptor {

    /** Anthropic's documented rate-limit response headers. */
    private static final List<String> RATE_LIMIT_HEADERS = List.of(
            "anthropic-ratelimit-requests-limit",
            "anthropic-ratelimit-requests-remaining",
            "anthropic-ratelimit-requests-reset",
            "anthropic-ratelimit-tokens-limit",
            "anthropic-ratelimit-tokens-remaining",
            "anthropic-ratelimit-tokens-reset",
            "anthropic-ratelimit-input-tokens-limit",
            "anthropic-ratelimit-input-tokens-remaining",
            "anthropic-ratelimit-input-tokens-reset",
            "anthropic-ratelimit-output-tokens-limit",
            "anthropic-ratelimit-output-tokens-remaining",
            "anthropic-ratelimit-output-tokens-reset",
            "retry-after");

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() == 429) {
            // Log REQUEST headers too so we can verify Spring AI didn't strip
            // our OAuth fingerprint when it cloned the rest client builder.
            logRequestHeaders(request.getHeaders());
            logHeaders(response.getHeaders());
        }
        return response;
    }

    private static void logRequestHeaders(HttpHeaders requestHeaders) {
        StringBuilder sb = new StringBuilder("[Anthropic 429] outgoing request headers (sanitized): ");
        boolean any = false;
        for (var entry : requestHeaders.entrySet()) {
            String name = entry.getKey().toLowerCase();
            String displayValue = name.equals("authorization")
                    ? "Bearer <redacted>"
                    : String.join(",", entry.getValue());
            if (any) sb.append(", ");
            sb.append(name).append('=').append(displayValue);
            any = true;
        }
        log.warn(sb.toString());
    }

    private static void logHeaders(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder("[Anthropic 429] rate-limit headers: ");
        boolean any = false;
        for (String name : RATE_LIMIT_HEADERS) {
            String value = headers.getFirst(name);
            if (value != null) {
                if (any) sb.append(", ");
                sb.append(name).append('=').append(value);
                any = true;
            }
        }
        if (!any) {
            // Anthropic still returned 429 but didn't include any rate-limit
            // headers — strong signal of the anti-abuse path (it generally
            // doesn't bother filling them in).
            log.warn("[Anthropic 429] no rate-limit headers present — likely anti-abuse gate, not real quota");
        } else {
            log.warn(sb.toString());
        }
    }
}
