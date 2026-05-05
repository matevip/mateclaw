package vip.mate.channel.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Validates a WeChat iLink Bot token by hitting
 * {@code GET /ilink/bot/getupdates} on the configured base URL.
 * <p>
 * iLink's getupdates is a long-poll (server holds the connection up to ~35s
 * waiting for a message). For the verify probe we set a short HTTP request
 * timeout — if the server reaches the long-poll wait, that already proves
 * the bearer token was accepted, so a clean {@link java.net.http.HttpTimeoutException}
 * is treated as success. 401/403 means the token is dead.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeixinVerifier implements ChannelVerifier {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "weixin";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String botToken = string(request.config(), "bot_token");
        String baseUrl = string(request.config(), "base_url");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DEFAULT_BASE_URL;
        baseUrl = baseUrl.replaceAll("/+$", "");

        if (botToken == null || botToken.isBlank()) {
            return VerificationResult.failed(0, "Bot Token is required",
                    "bot_token", "Scan the WeChat QR — Bot Token is filled automatically once you confirm in WeChat.");
        }

        try {
            // X-WECHAT-UIN: base64(str(random_uint32)) — iLink's anti-replay header.
            // Mirrors what ILinkClient.makeHeaders does on every real request.
            long uinVal = new Random().nextLong(0, 0xFFFFFFFFL + 1);
            String uin = Base64.getEncoder().encodeToString(
                    String.valueOf(uinVal).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/ilink/bot/getupdates"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", uin)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"cursor\":\"\"}"))
                    .build();

            HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            return interpretStatus(resp, ms, botToken, baseUrl);
        } catch (java.net.http.HttpTimeoutException e) {
            // Long-poll held the connection — implies the bearer token was
            // accepted (the server only enters the poll loop after auth).
            long ms = System.currentTimeMillis() - t0;
            return VerificationResult.ok(ms,
                    "Connected — WeChat iLink accepted the bot token",
                    identityFor(botToken, baseUrl));
        } catch (Exception e) {
            log.debug("[weixin-verify] error: {}", e.getMessage());
            long ms = System.currentTimeMillis() - t0;
            return VerificationResult.failed(ms,
                    "Could not reach iLink: " + e.getClass().getSimpleName(),
                    null, e.getMessage());
        }
    }

    private VerificationResult interpretStatus(HttpResponse<String> resp, long ms,
                                               String botToken, String baseUrl) {
        int status = resp.statusCode();
        if (status == 200) {
            // Server returned an immediate update before our timeout — token is fine.
            return VerificationResult.ok(ms,
                    "Connected — WeChat iLink accepted the bot token",
                    identityFor(botToken, baseUrl));
        }
        if (status == 401 || status == 403) {
            return VerificationResult.failed(ms,
                    "iLink rejected the Bot Token (HTTP " + status + ")",
                    "bot_token",
                    "The token has been revoked or expired. Re-scan the QR to obtain a fresh one.");
        }
        // Other 4xx/5xx — surface the body snippet for debugging without exposing the token.
        String snippet = resp.body() != null && !resp.body().isBlank()
                ? truncate(resp.body(), 160) : "(empty response body)";
        // Try to parse iLink JSON error envelope.
        try {
            JsonNode root = objectMapper.readTree(resp.body());
            int errcode = root.path("errcode").asInt(0);
            String errmsg = root.path("errmsg").asText("");
            if (errcode != 0 && !errmsg.isBlank()) {
                return VerificationResult.failed(ms,
                        "iLink errcode " + errcode + ": " + errmsg,
                        errcode == 401 || errcode == 403 ? "bot_token" : null,
                        "If this persists, re-scan the QR to refresh credentials.");
            }
        } catch (Exception ignored) {
            // Non-JSON body — fall through to generic message.
        }
        return VerificationResult.failed(ms,
                "iLink returned HTTP " + status, null, snippet);
    }

    private static Map<String, Object> identityFor(String botToken, String baseUrl) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("accountId", maskToken(botToken));
        identity.put("baseUrl", baseUrl);
        identity.put("transport", "iLink long-polling");
        return identity;
    }

    private static String maskToken(String token) {
        if (token.length() <= 8) return token;
        return token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
