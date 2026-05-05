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
 * Validates Feishu / Lark app credentials via the
 * {@code /open-apis/auth/v3/tenant_access_token/internal} endpoint. Mirrors
 * the same exchange that {@code FeishuChannelAdapter.refreshTenantAccessToken}
 * does on real startup, so a green Step 2 is a strong predictor of a green
 * channel post-save.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuVerifier implements ChannelVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "feishu";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String appId = string(request.config(), "app_id");
        String appSecret = string(request.config(), "app_secret");
        String domain = string(request.config(), "domain");
        if (domain == null || domain.isBlank()) domain = "feishu";

        if (appId == null || appId.isBlank()) {
            return VerificationResult.failed(0, "App ID is required",
                    "app_id", "Scan the QR (one-click app creation) — App ID is filled automatically.");
        }
        if (appSecret == null || appSecret.isBlank()) {
            return VerificationResult.failed(0, "App Secret is required",
                    "app_secret", "Scan the QR (one-click app creation) — App Secret is filled automatically.");
        }

        String apiBase = "lark".equalsIgnoreCase(domain)
                ? "https://open.larksuite.com"
                : "https://open.feishu.cn";

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "app_id", appId,
                    "app_secret", appSecret));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;

            JsonNode root = objectMapper.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                int expire = root.path("expire").asInt(7200);
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("accountId", appId);
                identity.put("region", "lark".equalsIgnoreCase(domain) ? "Lark (international)" : "Feishu (China)");
                identity.put("tokenTtl", expire + "s");
                String regionLabel = "lark".equalsIgnoreCase(domain) ? "Lark" : "Feishu";
                return VerificationResult.ok(ms,
                        "Connected to " + regionLabel + " — tenant_access_token issued",
                        identity);
            }
            String msg = root.path("msg").asText("auth failed");
            return VerificationResult.failed(ms,
                    "Feishu rejected the credentials (code " + code + "): " + msg,
                    invalidFieldFor(code),
                    hintFor(code, msg));
        } catch (java.net.http.HttpTimeoutException e) {
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Timed out talking to " + apiBase, null,
                    "Network couldn't reach Feishu in 5s. If you're on a corporate network, check egress to *.feishu.cn / *.larksuite.com.");
        } catch (Exception e) {
            log.debug("[feishu-verify] error: {}", e.getMessage());
            return VerificationResult.failed(System.currentTimeMillis() - t0,
                    "Could not reach Feishu: " + e.getClass().getSimpleName(), null, e.getMessage());
        }
    }

    private static String invalidFieldFor(int code) {
        // 10003 / 99991663 / 99991664 family: app credential / signature errors
        return switch (code) {
            case 10003 -> "app_secret";
            case 10012 -> "app_id";
            default -> code >= 10000 && code < 20000 ? "app_secret" : null;
        };
    }

    private static String hintFor(int code, String msg) {
        return switch (code) {
            case 10003 -> "App Secret rejected. Re-scan the QR — Feishu may have rotated the secret on app re-publish.";
            case 10012 -> "App ID not recognized. Verify you scanned the QR for the right tenant.";
            case 99991663 -> "Token cache stale. Re-scan to force a fresh credential pair.";
            default -> msg != null && !msg.isBlank()
                    ? "Feishu code " + code + " — " + msg
                    : "Feishu code " + code + ". Re-scanning the QR usually resolves credential drift.";
        };
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
