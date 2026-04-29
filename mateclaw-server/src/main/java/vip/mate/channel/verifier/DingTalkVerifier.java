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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates DingTalk app credentials via
 * {@code POST /v1.0/oauth2/accessToken} (the same handshake the production
 * adapter does to call Robot APIs). Mirrors {@code DingTalkChannelAdapter
 * .getDingTalkAccessToken} so a green Step 2 maps to a green channel
 * post-save.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkVerifier implements ChannelVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String API_URL = "https://api.dingtalk.com/v1.0/oauth2/accessToken";

    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "dingtalk";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String clientId = string(request.config(), "client_id");
        String clientSecret = string(request.config(), "client_secret");

        if (clientId == null || clientId.isBlank()) {
            return VerificationResult.failed(0, "Client ID (AppKey) is required",
                    "client_id", "Scan the DingTalk QR (one-click bot creation) — Client ID is filled automatically.");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            return VerificationResult.failed(0, "Client Secret is required",
                    "client_secret", "Scan the DingTalk QR — Client Secret is filled automatically.");
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "appKey", clientId,
                    "appSecret", clientSecret));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;

            JsonNode root = objectMapper.readTree(resp.body());

            // DingTalk returns {accessToken, expireIn} on success, or
            // {code: "InvalidAuthentication"/..., message: "...", requestid: "..."} on failure.
            String accessToken = root.path("accessToken").asText("");
            if (resp.statusCode() == 200 && !accessToken.isBlank()) {
                int expire = root.path("expireIn").asInt(7200);
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("accountId", clientId);
                identity.put("transport", "DingTalk OAuth2 v1.0");
                identity.put("tokenTtl", expire + "s");
                return VerificationResult.ok(ms,
                        "Connected — DingTalk issued an access token",
                        identity);
            }

            String code = root.path("code").asText("");
            String msg = root.path("message").asText("auth failed");
            return VerificationResult.failed(ms,
                    "DingTalk rejected the credentials (" + code + "): " + msg,
                    invalidFieldFor(code),
                    hintFor(code, msg));
        } catch (java.net.http.HttpTimeoutException e) {
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Timed out talking to api.dingtalk.com", null,
                    "Network couldn't reach DingTalk in 5s. Check egress to api.dingtalk.com (port 443).");
        } catch (Exception e) {
            log.debug("[dingtalk-verify] error: {}", e.getMessage());
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Could not reach DingTalk: " + e.getClass().getSimpleName(), null, e.getMessage());
        }
    }

    private static String invalidFieldFor(String code) {
        if (code == null) return null;
        return switch (code) {
            case "InvalidAuthentication", "AccessKeyError" -> "client_secret";
            case "InvalidParameter.AppKey", "AppNotExist" -> "client_id";
            default -> null;
        };
    }

    private static String hintFor(String code, String msg) {
        if (code == null || code.isBlank()) {
            return msg != null && !msg.isBlank() ? msg : "DingTalk auth failed.";
        }
        return switch (code) {
            case "InvalidAuthentication" ->
                    "Client Secret rejected. Re-scan the QR — DingTalk rotates secrets on app re-publish.";
            case "AppNotExist" ->
                    "App not found in your tenant. Verify the QR was for the right corporation.";
            case "AccessKeyError" ->
                    "Authentication signature mismatch. Re-scan to refresh the credential pair.";
            default -> "DingTalk error " + code + " — " + msg;
        };
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
