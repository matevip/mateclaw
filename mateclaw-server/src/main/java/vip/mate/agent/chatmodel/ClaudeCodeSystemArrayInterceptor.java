package vip.mate.agent.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestClient interceptor that rewrites the Anthropic {@code system} field from
 * a plain string to a two-element content-block array before the request hits
 * the wire.
 *
 * <p>Anthropic's OAuth anti-abuse gate accepts the Claude Code identity prefix
 * as a string ONLY when it is the sole content.  The moment we append the
 * agent's actual system prompt the gate returns 429.  Sending the same two
 * pieces as separate array elements always passes (verified 2026-04-25).
 *
 * <p>Spring AI's {@code AnthropicChatModel} can emit array format natively
 * via prompt caching, but {@code ModelOptionsUtils.copyToTarget} (Jackson-
 * based) drops the {@code @JsonIgnore cacheOptions} field, making it
 * impossible to activate through the normal options path.  This interceptor
 * works at the HTTP layer and is immune to Spring AI's internal option-merging.
 *
 * <p>Sync (RestClient) variant; the WebFlux equivalent is
 * {@link ClaudeCodeSystemArrayExchangeFilter}.
 */
@Slf4j
@RequiredArgsConstructor
class ClaudeCodeSystemArrayInterceptor implements ClientHttpRequestInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        return execution.execute(request, rewriteSystemField(body));
    }

    /**
     * If {@code body} is a JSON object whose {@code system} field is a string,
     * replace it with a two-element content-block array:
     * <pre>
     * [ {"type":"text","text":"You are Claude Code..."}, {"type":"text","text":"<rest>"} ]
     * </pre>
     * Returns {@code body} unchanged on any error or if rewrite is not needed.
     */
    byte[] rewriteSystemField(byte[] body) {
        if (body == null || body.length == 0) return body;
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isObject()) return body;
            JsonNode systemNode = root.get("system");
            if (systemNode == null || !systemNode.isTextual()) return body; // absent or already array
            byte[] rewritten = objectMapper.writeValueAsBytes(
                    buildRewritten((ObjectNode) root, systemNode.asText()));
            log.debug("[ClaudeCodeSystem] rewrote system field from string to array ({} → {} bytes)",
                    body.length, rewritten.length);
            return rewritten;
        } catch (Exception e) {
            log.warn("[ClaudeCodeSystem] body rewrite failed, sending original: {}", e.getMessage());
            return body;
        }
    }

    static ObjectNode buildRewritten(ObjectNode root, String systemText) {
        String identity = ClaudeCodeIdentityChatModelDecorator.CLAUDE_CODE_SYSTEM_PREFIX;
        ArrayNode arr = root.arrayNode();

        ObjectNode identityBlock = arr.objectNode();
        identityBlock.put("type", "text");
        identityBlock.put("text", identity);
        arr.add(identityBlock);

        // Strip the identity prefix and leading newlines to get the rest.
        if (!systemText.equals(identity) && systemText.startsWith(identity)) {
            String rest = systemText.substring(identity.length()).replaceFirst("^\n+", "");
            if (!rest.isBlank()) {
                ObjectNode contentBlock = arr.objectNode();
                contentBlock.put("type", "text");
                contentBlock.put("text", rest);
                arr.add(contentBlock);
            }
        }

        ObjectNode copy = root.deepCopy();
        copy.set("system", arr);
        return copy;
    }
}
