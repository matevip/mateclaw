package vip.mate.agent.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebClient (streaming) counterpart of {@link ClaudeCodeSystemArrayInterceptor}.
 *
 * <p>Collects the full request body from the reactive publisher with
 * {@code DataBufferUtils.join}, rewrites the {@code system} field from a
 * plain string to a two-element content-block array, and emits the modified
 * bytes as a single new {@link DataBuffer}.
 *
 * <p>Anthropic's OAuth anti-abuse gate rejects the merged-string format with
 * a 429; two separate array elements always pass (verified 2026-04-25).
 * Spring AI's native array path is gated behind {@code @JsonIgnore} cache
 * options that {@code ModelOptionsUtils.copyToTarget} strips before our
 * settings can reach {@code buildSystemContent}.  This interceptor is immune
 * to that stripping because it runs at the HTTP transport layer.
 */
@Slf4j
@RequiredArgsConstructor
class ClaudeCodeSystemArrayExchangeFilter implements ExchangeFilterFunction {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        ClientRequest intercepted = ClientRequest.from(request)
                .body((outputMessage, context) -> request.body().insert(
                        new ClientHttpRequestDecorator(outputMessage) {
                            @Override
                            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                // Join all chunks so we can parse the complete JSON body.
                                return DataBufferUtils.join(Flux.from(body))
                                        .flatMap(joined -> {
                                            byte[] original = new byte[joined.readableByteCount()];
                                            joined.read(original);
                                            DataBufferUtils.release(joined);

                                            byte[] rewritten = rewriteSystemField(original);

                                            // Keep Content-Length consistent if it was set.
                                            long declared = getHeaders().getContentLength();
                                            if (declared > 0 && declared != rewritten.length) {
                                                getHeaders().setContentLength(rewritten.length);
                                            }

                                            DataBuffer newBuf = outputMessage.bufferFactory()
                                                    .wrap(rewritten);
                                            return super.writeWith(Mono.just(newBuf));
                                        });
                            }
                        }, context))
                .build();

        return next.exchange(intercepted);
    }

    private byte[] rewriteSystemField(byte[] body) {
        if (body == null || body.length == 0) return body;
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isObject()) return body;
            JsonNode systemNode = root.get("system");
            if (systemNode == null || !systemNode.isTextual()) return body;
            byte[] rewritten = objectMapper.writeValueAsBytes(
                    ClaudeCodeSystemArrayInterceptor.buildRewritten(
                            (ObjectNode) root, systemNode.asText()));
            log.debug("[ClaudeCodeSystem] rewrote system field from string to array ({} → {} bytes)",
                    body.length, rewritten.length);
            return rewritten;
        } catch (Exception e) {
            log.warn("[ClaudeCodeSystem] body rewrite failed, sending original: {}", e.getMessage());
            return body;
        }
    }
}
