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
 * Validates a Discord bot token via {@code GET /api/v10/users/@me} with
 * {@code Authorization: Bot <token>}. Same proxy semantics as
 * {@link TelegramVerifier}.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordVerifier implements ChannelVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String API_URL = "https://discord.com/api/v10/users/@me";

    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "discord";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String botToken = string(request.config(), "bot_token");
        if (botToken == null || botToken.isBlank()) {
            return VerificationResult.failed(0, "Bot Token is required",
                    "bot_token", "Get one from the Discord Developer Portal under Bot → Token.");
        }

        HttpClient.Builder cb = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        applyProxy(cb, string(request.config(), "http_proxy"));
        HttpClient client = cb.build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(TIMEOUT)
                .header("Authorization", "Bot " + botToken)
                .header("User-Agent", "MateClaw-Verifier/1.0 (+https://claw.mate.vip)")
                .GET()
                .build();

        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            if (resp.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(resp.body());
                String username = body.path("username").asText("");
                String discriminator = body.path("discriminator").asText("0");
                String displayName = "0".equals(discriminator) ? username : username + "#" + discriminator;
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("accountId", body.path("id").asText());
                identity.put("accountName", displayName);
                identity.put("isBot", body.path("bot").asBoolean(true));
                identity.put("verified", body.path("verified").asBoolean(false));
                return VerificationResult.ok(ms, "Connected as " + displayName, identity);
            }
            if (resp.statusCode() == 401) {
                return VerificationResult.failed(ms, "Discord rejected the bot token (401 Unauthorized)",
                        "bot_token", "Bot Token is invalid. Regenerate it in the Discord Developer Portal.");
            }
            return VerificationResult.failed(ms, "Discord returned HTTP " + resp.statusCode(),
                    null, "Unexpected response from Discord. Check the bot exists and the token has not been revoked.");
        } catch (java.net.http.HttpTimeoutException e) {
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Timed out talking to discord.com", null,
                    "Network couldn't reach Discord in 5s. Set HTTP Proxy in advanced settings if needed.");
        } catch (Exception e) {
            log.debug("[discord-verify] error: {}", e.getMessage());
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Could not reach Discord: " + e.getClass().getSimpleName(), null, e.getMessage());
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
