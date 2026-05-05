package vip.mate.llm.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vip.mate.exception.MateClawException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 2.0 Device Authorization Grant (RFC 8628) for the OpenAI Codex CLI client_id.
 *
 * <p>Used when MateClaw runs on a remote host and the browser cannot reach
 * {@code localhost:1455}. Authorization happens entirely on
 * {@code auth.openai.com}; we just poll a server-side endpoint until OpenAI hands
 * us an authorization code, then delegate the token exchange to
 * {@link OpenAIOAuthService#exchangeTokenWithVerifier}.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #start()} POSTs {@code client_id} to {@code /api/accounts/deviceauth/usercode}
 *       and returns {@code user_code} + verification URL for the user to open in any
 *       browser.</li>
 *   <li>The frontend polls {@link #poll(String)} every {@code interval} seconds.
 *       Each call POSTs {@code device_auth_id + user_code} to
 *       {@code /api/accounts/deviceauth/token}; OpenAI returns
 *       {@code authorization_pending} until the user authorizes, then returns
 *       {@code authorization_code + code_verifier}.</li>
 *   <li>On COMPLETED we exchange the code for tokens at {@code /oauth/token} using
 *       the device redirect URI {@code https://auth.openai.com/deviceauth/callback}
 *       and persist via the shared save path.</li>
 * </ol>
 *
 * <p>Sessions are kept in-memory; multi-instance deployments need sticky sessions
 * until a Redis-backed store is introduced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAIDeviceCodeService {

    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String DEVICE_USERCODE_URL =
            "https://auth.openai.com/api/accounts/deviceauth/usercode";
    static final String DEVICE_TOKEN_URL =
            "https://auth.openai.com/api/accounts/deviceauth/token";
    static final String DEVICE_REDIRECT_URI =
            "https://auth.openai.com/deviceauth/callback";
    static final String DEFAULT_VERIFICATION_URL =
            "https://auth.openai.com/codex/device";
    static final String DEFAULT_USER_AGENT = "codex_cli_rs/0.7.0";

    private final OpenAIOAuthService oauthService;
    private final ObjectMapper objectMapper;

    private RestClient restClient = RestClient.create();

    @Value("${mateclaw.oauth.openai.device.poll-min-interval-ms:3000}")
    private long pollMinIntervalMs;

    @Value("${mateclaw.oauth.openai.device.session-ttl-seconds:900}")
    private long defaultSessionTtlSeconds;

    @Value("${mateclaw.oauth.openai.device.user-agent:" + DEFAULT_USER_AGENT + "}")
    private String userAgent;

    private final ConcurrentHashMap<String, DeviceCodeSession> sessions = new ConcurrentHashMap<>();

    /** Test seam — replace the RestClient (e.g. with a WireMock-pointed instance). */
    void setRestClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Step 1: request a user_code and device_auth_id. */
    public DeviceCodeStartResult start() {
        String body = "client_id=" + enc(CLIENT_ID);
        JsonNode resp;
        try {
            String raw = restClient.post()
                    .uri(DEVICE_USERCODE_URL)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            resp = objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("Device code start failed: {}", e.getMessage());
            throw new MateClawException("err.llm.device_code_start_failed",
                    "Device code 申请失败: " + e.getMessage());
        }

        String deviceAuthId = resp.path("device_auth_id").asText(null);
        String userCode = resp.path("user_code").asText(null);
        int interval = resp.path("interval").asInt(5);
        int expiresIn = resp.path("expires_in").asInt((int) defaultSessionTtlSeconds);
        String verificationUrl = resp.path("verification_uri").asText(DEFAULT_VERIFICATION_URL);
        String verificationUrlComplete = resp.path("verification_uri_complete").asText(null);

        if (deviceAuthId == null || userCode == null) {
            throw new MateClawException("err.llm.device_code_start_failed",
                    "Device code 响应缺少必要字段");
        }

        long now = System.currentTimeMillis();
        sessions.put(deviceAuthId, new DeviceCodeSession(
                deviceAuthId, userCode,
                now + expiresIn * 1000L,
                now));

        log.info("Device code session started: deviceAuthId prefix={}, expires_in={}s",
                deviceAuthId.substring(0, Math.min(8, deviceAuthId.length())), expiresIn);

        return new DeviceCodeStartResult(deviceAuthId, userCode, verificationUrl,
                verificationUrlComplete, interval, expiresIn);
    }

    /** Step 2: poll OpenAI for completion. Returns PENDING / COMPLETED / EXPIRED. */
    public DeviceCodePollResult poll(String deviceAuthId) {
        if (deviceAuthId == null || deviceAuthId.isBlank()) {
            return DeviceCodePollResult.expired();
        }
        DeviceCodeSession session = sessions.get(deviceAuthId);
        if (session == null) {
            return DeviceCodePollResult.expired();
        }

        long now = System.currentTimeMillis();
        if (now > session.expiresAt()) {
            sessions.remove(deviceAuthId);
            return DeviceCodePollResult.expired();
        }

        // Rate-limit: refuse to hammer OpenAI faster than the configured floor.
        if (now - session.lastPollAt() < pollMinIntervalMs) {
            return DeviceCodePollResult.pending();
        }
        sessions.put(deviceAuthId, session.withLastPollAt(now));

        String body = "device_auth_id=" + enc(deviceAuthId)
                + "&user_code=" + enc(session.userCode());

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(DEVICE_TOKEN_URL)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            return classifyError(deviceAuthId, e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Device code poll transport failure (will retry): {}", e.getMessage());
            return DeviceCodePollResult.pending();
        }

        JsonNode resp;
        try {
            resp = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            log.warn("Device code poll: malformed JSON, treating as pending");
            return DeviceCodePollResult.pending();
        }

        String authorizationCode = resp.path("authorization_code").asText(null);
        String codeVerifier = resp.path("code_verifier").asText(null);
        if (authorizationCode == null) {
            // Some responses use 200 with an inline {error: authorization_pending}.
            String error = resp.path("error").asText(null);
            if ("expired_token".equals(error) || "access_denied".equals(error)) {
                sessions.remove(deviceAuthId);
                return DeviceCodePollResult.expired();
            }
            return DeviceCodePollResult.pending();
        }
        if (codeVerifier == null) {
            log.warn("Device code response missing code_verifier; cannot exchange token");
            sessions.remove(deviceAuthId);
            return DeviceCodePollResult.expired();
        }

        try {
            oauthService.exchangeTokenWithVerifier(authorizationCode, codeVerifier, DEVICE_REDIRECT_URI);
            sessions.remove(deviceAuthId);
            log.info("Device code session completed: deviceAuthId prefix={}",
                    deviceAuthId.substring(0, Math.min(8, deviceAuthId.length())));
            return DeviceCodePollResult.completed();
        } catch (Exception e) {
            sessions.remove(deviceAuthId);
            throw e;
        }
    }

    /** Caller-driven cancellation: drop the session so we stop polling. */
    public void cancel(String deviceAuthId) {
        if (deviceAuthId != null) {
            sessions.remove(deviceAuthId);
        }
    }

    /** Sweep expired sessions every 5 minutes. */
    @Scheduled(fixedDelay = 300_000L)
    void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> now > entry.getValue().expiresAt());
    }

    private DeviceCodePollResult classifyError(String deviceAuthId, int status, String body) {
        String error = extractErrorCode(body);
        if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
            return DeviceCodePollResult.pending();
        }
        if ("expired_token".equals(error) || "access_denied".equals(error)) {
            sessions.remove(deviceAuthId);
            return DeviceCodePollResult.expired();
        }
        log.warn("Device code poll unexpected error: status={}, error={}, body={}",
                status, error, body);
        // Conservative: keep the session alive so the user can retry; expiry will catch it.
        return DeviceCodePollResult.pending();
    }

    private String extractErrorCode(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(body);
            String error = node.path("error").asText(null);
            if (error != null) return error;
        } catch (Exception ignored) {
        }
        // Fallback for non-JSON bodies — match well-known error tokens defensively.
        if (body.contains("authorization_pending")) return "authorization_pending";
        if (body.contains("slow_down")) return "slow_down";
        if (body.contains("expired_token")) return "expired_token";
        if (body.contains("access_denied")) return "access_denied";
        return null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    int activeSessionCount() {
        return sessions.size();
    }

    private record DeviceCodeSession(
            String deviceAuthId,
            String userCode,
            long expiresAt,
            long lastPollAt) {

        DeviceCodeSession withLastPollAt(long now) {
            return new DeviceCodeSession(deviceAuthId, userCode, expiresAt, now);
        }
    }

    public record DeviceCodeStartResult(
            String deviceAuthId,
            String userCode,
            String verificationUrl,
            String verificationUrlComplete,
            int intervalSeconds,
            int expiresInSeconds) {
    }

    public record DeviceCodePollResult(Status status) {
        public enum Status { PENDING, COMPLETED, EXPIRED }

        public static DeviceCodePollResult pending() { return new DeviceCodePollResult(Status.PENDING); }
        public static DeviceCodePollResult completed() { return new DeviceCodePollResult(Status.COMPLETED); }
        public static DeviceCodePollResult expired() { return new DeviceCodePollResult(Status.EXPIRED); }
    }
}
