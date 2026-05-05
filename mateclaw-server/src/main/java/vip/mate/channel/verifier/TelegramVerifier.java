package vip.mate.channel.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates a Telegram bot token via {@code GET /bot{token}/getMe}. Honors
 * the {@code http_proxy} field so Chinese users — who almost always need a
 * proxy to reach api.telegram.org — get a representative result instead of
 * a misleading timeout.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramVerifier implements ChannelVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "telegram";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String botToken = string(request.config(), "bot_token");
        if (botToken == null || botToken.isBlank()) {
            return VerificationResult.failed(0, "Bot Token is required",
                    "bot_token", "Get one from @BotFather and paste it here.");
        }

        HttpClient.Builder cb = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        applyProxy(cb, string(request.config(), "http_proxy"));
        HttpClient client = cb.build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + botToken + "/getMe"))
                .timeout(TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            JsonNode body = objectMapper.readTree(resp.body());
            if (resp.statusCode() == 200 && body.path("ok").asBoolean(false)) {
                JsonNode r = body.path("result");
                String username = r.path("username").asText("");
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("accountId", r.path("id").asLong());
                identity.put("accountName", "@" + username);
                identity.put("firstName", r.path("first_name").asText(""));
                identity.put("canJoinGroups", r.path("can_join_groups").asBoolean());
                identity.put("canReadAllGroupMessages", r.path("can_read_all_group_messages").asBoolean());
                return VerificationResult.ok(ms, "Connected as @" + username, identity);
            }
            // Telegram returns 401/404 for bad tokens with a JSON body containing "description"
            String description = body.path("description").asText("Telegram rejected the token");
            return VerificationResult.failed(ms, "Telegram says: " + description,
                    "bot_token", "Bot Token rejected. Check the full string from @BotFather, including the colon.");
        } catch (java.net.http.HttpTimeoutException e) {
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Timed out talking to api.telegram.org", null,
                    "Network couldn't reach Telegram in 5s. If you're in mainland China, set HTTP Proxy in advanced settings.");
        } catch (Exception e) {
            log.debug("[telegram-verify] error: {}", e.getMessage());
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Could not reach Telegram: " + e.getClass().getSimpleName(), null, e.getMessage());
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static void applyProxy(HttpClient.Builder cb, String httpProxy) {
        if (httpProxy == null || httpProxy.isBlank()) return;
        try {
            URI uri = URI.create(httpProxy);
            if (uri.getHost() != null && uri.getPort() > 0) {
                cb.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
            }
        } catch (Exception ignored) {
            // best effort — invalid proxy falls back to direct
        }
    }
}
