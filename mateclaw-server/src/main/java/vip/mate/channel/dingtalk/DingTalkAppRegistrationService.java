package vip.mate.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉"一键应用注册"服务（OAuth Device Authorization Grant）
 * <p>
 * 钉钉 SDK 没把 Device Flow 端点包装进 dingtalk-stream（那个 SDK 只管 WebSocket 长连接）。
 * 三个 HTTP 端点是钉钉官方的 OAuth 2.0 Device Flow 标准协议：
 * <pre>
 *   POST /app/registration/init   body {source}              → {nonce}（5 分钟 TTL）
 *   POST /app/registration/begin  body {nonce}               → {device_code, verification_uri_complete}
 *   POST /app/registration/poll   body {device_code}         → {status: WAITING/SUCCESS/FAIL/EXPIRED, client_id?, client_secret?}
 * </pre>
 * <p>
 * 跟 {@code FeishuAppRegistrationService} 同构，但飞书走 SDK 阻塞调用 + 回调，钉钉这边纯 HTTP，
 * 我们自己起 worker 线程做轮询（每 5 秒一次直到终态）。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkAppRegistrationService {

    private static final String API_BASE = "https://oapi.dingtalk.com";
    private static final String SOURCE = "MATECLAW";
    private static final long POLL_INTERVAL_MS = 5000L;
    private static final long POLL_REQUEST_TIMEOUT_MS = 10_000L;
    private static final long INIT_REQUEST_TIMEOUT_MS = 15_000L;
    /** Session lifetime upper bound: device_code expires in ~5 min, give 7 min buffer for late polls. */
    private static final long SESSION_TTL_MS = 7 * 60_000L;
    /** Max wall-clock for the polling worker — kills runaway sessions even if DingTalk never returns terminal state. */
    private static final long WORKER_MAX_RUNTIME_MS = 6 * 60_000L;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** session_id → registration session */
    private final ConcurrentHashMap<String, RegistrationSession> sessions = new ConcurrentHashMap<>();

    /**
     * Kick off a registration: do init + begin synchronously to get the QR URL, then spawn a worker
     * that polls /poll until terminal. Returns immediately with the session id.
     */
    public RegistrationSession begin() throws Exception {
        evictExpiredSessions();

        // Step 1: init (synchronous, surfaces errors to caller before any session is recorded)
        Map<?, ?> init = postJson("/app/registration/init", Map.of("source", SOURCE), INIT_REQUEST_TIMEOUT_MS);
        Integer initCode = init.get("errcode") instanceof Number n ? n.intValue() : null;
        if (initCode != null && initCode != 0) {
            throw new IllegalStateException("DingTalk init failed: errcode=" + initCode + ", errmsg=" + init.get("errmsg"));
        }
        String nonce = (String) init.get("nonce");
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalStateException("DingTalk init returned empty nonce");
        }

        // Step 2: begin (exchanges nonce for device_code + QR URL)
        Map<?, ?> begin = postJson("/app/registration/begin", Map.of("nonce", nonce), INIT_REQUEST_TIMEOUT_MS);
        Integer beginCode = begin.get("errcode") instanceof Number n ? n.intValue() : null;
        if (beginCode != null && beginCode != 0) {
            throw new IllegalStateException("DingTalk begin failed: errcode=" + beginCode + ", errmsg=" + begin.get("errmsg"));
        }
        String deviceCode = (String) begin.get("device_code");
        String verificationUri = (String) begin.get("verification_uri_complete");
        if (deviceCode == null || deviceCode.isBlank() || verificationUri == null || verificationUri.isBlank()) {
            throw new IllegalStateException("DingTalk begin returned empty device_code or URI");
        }

        // Build session with QR URL ready, then spawn worker
        String sessionId = UUID.randomUUID().toString();
        RegistrationSession session = new RegistrationSession(sessionId);
        session.qrcodeUrl = verificationUri;
        session.status = Status.WAITING;
        sessions.put(sessionId, session);

        Thread worker = new Thread(() -> pollUntilTerminal(session, deviceCode),
                "dingtalk-register-" + sessionId.substring(0, 8));
        worker.setDaemon(true);
        worker.start();

        log.info("[dingtalk-register] session {} started (device_code suffix=...{})",
                sessionId, deviceCode.length() > 6 ? deviceCode.substring(deviceCode.length() - 6) : deviceCode);
        return session;
    }

    public RegistrationSession getSession(String sessionId) {
        evictExpiredSessions();
        return sessions.get(sessionId);
    }

    /**
     * Polling worker. Waits {@link #POLL_INTERVAL_MS} between polls until DingTalk returns a terminal
     * status, hard wall-clock cap of {@link #WORKER_MAX_RUNTIME_MS} so a server-side hang doesn't
     * leak threads.
     */
    private void pollUntilTerminal(RegistrationSession session, String deviceCode) {
        long startMs = System.currentTimeMillis();
        while (true) {
            if (System.currentTimeMillis() - startMs > WORKER_MAX_RUNTIME_MS) {
                session.status = Status.EXPIRED;
                session.errorMessage = "polling worker timed out";
                session.lastUpdateMs = System.currentTimeMillis();
                log.warn("[dingtalk-register] session {} timed out after {} ms",
                        session.sessionId, WORKER_MAX_RUNTIME_MS);
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Map<?, ?> poll = postJson("/app/registration/poll", Map.of("device_code", deviceCode), POLL_REQUEST_TIMEOUT_MS);
                String status = (String) poll.get("status");
                if (status == null) status = "WAITING";

                session.lastUpdateMs = System.currentTimeMillis();

                switch (status) {
                    case "SUCCESS" -> {
                        session.clientId = (String) poll.get("client_id");
                        session.clientSecret = (String) poll.get("client_secret");
                        session.status = Status.CONFIRMED;
                        log.info("[dingtalk-register] session {} confirmed, clientId={}",
                                session.sessionId, session.clientId);
                        return;
                    }
                    case "FAIL" -> {
                        Object failReason = poll.get("fail_reason");
                        session.errorMessage = failReason != null ? failReason.toString() : "unknown";
                        session.status = Status.DENIED;
                        log.info("[dingtalk-register] session {} denied: {}",
                                session.sessionId, session.errorMessage);
                        return;
                    }
                    case "EXPIRED" -> {
                        session.status = Status.EXPIRED;
                        log.info("[dingtalk-register] session {} expired", session.sessionId);
                        return;
                    }
                    case "WAITING" -> {
                        // keep polling
                    }
                    default -> {
                        log.debug("[dingtalk-register] session {} unknown status: {}", session.sessionId, status);
                    }
                }
            } catch (Exception e) {
                // Transient errors don't fail the session — keep polling, the device_code is still valid
                log.debug("[dingtalk-register] poll attempt failed (will retry): {}", e.getMessage());
            }
        }
    }

    private Map<?, ?> postJson(String path, Map<String, ?> body, long timeoutMs) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("DingTalk " + path + " HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    private void evictExpiredSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_TTL_MS;
        Iterator<Map.Entry<String, RegistrationSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().createdAtMs < cutoff) it.remove();
        }
    }

    public enum Status {
        /** init/begin done, polling for user scan + confirmation */
        WAITING,
        /** User confirmed; clientId/clientSecret returned */
        CONFIRMED,
        /** device_code expired before user finished */
        EXPIRED,
        /** User explicitly denied or DingTalk returned FAIL */
        DENIED
    }

    public static class RegistrationSession {
        public final String sessionId;
        final long createdAtMs = System.currentTimeMillis();

        public volatile Status status = Status.WAITING;
        public volatile String qrcodeUrl;
        /** Cached "data:image/png;base64,..." rendered from qrcodeUrl by the controller. */
        public volatile String qrcodeImgDataUri;
        public volatile String clientId;
        public volatile String clientSecret;
        public volatile String errorMessage;
        public volatile long lastUpdateMs = System.currentTimeMillis();

        RegistrationSession(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
