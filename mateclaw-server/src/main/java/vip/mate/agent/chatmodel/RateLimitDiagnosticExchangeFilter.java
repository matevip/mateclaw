package vip.mate.agent.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RFC-062 diagnostic helper for the streaming (WebClient) path. Mirrors
 * {@link RateLimitDiagnosticInterceptor} for non-streaming RestClient calls —
 * see that class's javadoc for how to interpret the logged headers.
 *
 * <p>Stream calls are where most chat traffic flows, so without this filter
 * we wouldn't see rate-limit metadata for the most common 429 path.
 */
@Slf4j
class RateLimitDiagnosticExchangeFilter implements ExchangeFilterFunction {

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
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return next.exchange(request).doOnNext(response -> {
            if (response.statusCode().value() == 429) {
                logHeaders(response.headers().asHttpHeaders());
            }
        });
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
            log.warn("[Anthropic 429] no rate-limit headers present — likely anti-abuse gate, not real quota");
        } else {
            log.warn(sb.toString());
        }
    }
}
