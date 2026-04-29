package vip.mate.channel.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates WeCom (企业微信) smart-bot credentials by performing a real
 * {@code aibot_subscribe} handshake against {@code wss://openws.work.weixin.qq.com}.
 * <p>
 * Why a full WS handshake instead of a cheaper REST probe: WeCom's smart-bot
 * API has no REST equivalent — the long-connection subscribe is the only way
 * to find out whether a {@code (bot_id, secret)} pair will actually connect.
 * Skimping here would let bad credentials through Step 2 and surface as a red
 * dot in production, which is exactly the failure mode the wizard exists to
 * eliminate.
 * <p>
 * The probe is short-lived: connect → send subscribe → wait ≤5s for ack →
 * close. No heartbeat, no message handling, no retained state.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeComVerifier implements ChannelVerifier {

    private static final String WS_URL = "wss://openws.work.weixin.qq.com";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long ACK_TIMEOUT_MS = 5_000L;

    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "wecom";
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        long t0 = System.currentTimeMillis();
        String botId = string(request.config(), "bot_id");
        String secret = string(request.config(), "secret");

        if (botId == null || botId.isBlank()) {
            return VerificationResult.failed(0, "Bot ID is required",
                    "bot_id", "Scan the QR in WeCom to fetch the Bot ID and Secret automatically.");
        }
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failed(0, "Secret is required",
                    "secret", "Scan the QR in WeCom to fetch the Bot ID and Secret automatically.");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        AckWaiter waiter = new AckWaiter();
        WebSocket ws = null;
        try {
            ws = httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(URI.create(WS_URL), waiter)
                    .get(7, TimeUnit.SECONDS);

            String reqId = "aibot_subscribe-" + UUID.randomUUID();
            Map<String, Object> frame = Map.of(
                    "cmd", "aibot_subscribe",
                    "headers", Map.of("req_id", reqId),
                    "body", Map.of("bot_id", botId, "secret", secret)
            );
            ws.sendText(objectMapper.writeValueAsString(frame), true)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();

            Map<String, Object> ack = waiter.awaitAck(ACK_TIMEOUT_MS);
            long ms = System.currentTimeMillis() - t0;

            Object errcodeObj = ack.get("errcode");
            int errcode = errcodeObj instanceof Number n ? n.intValue() : 0;
            if (errcode == 0) {
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("accountId", maskBotId(botId));
                identity.put("transport", "WebSocket (openws.work.weixin.qq.com)");
                return VerificationResult.ok(ms, "Connected — WeCom accepted the bot credentials", identity);
            }

            String errmsg = String.valueOf(ack.getOrDefault("errmsg", "unknown error"));
            return VerificationResult.failed(ms,
                    "WeCom rejected the credentials: " + errmsg,
                    invalidFieldFor(errcode),
                    hintFor(errcode, errmsg));
        } catch (TimeoutException e) {
            long ms = System.currentTimeMillis() - t0;
            // Timed out either on connect (handled by buildAsync) or on the ack wait.
            String headline = waiter.opened()
                    ? "WeCom did not respond to the subscribe frame in 5s"
                    : "Could not reach openws.work.weixin.qq.com in 5s";
            return VerificationResult.failed(ms, headline, null,
                    "Check network egress to *.work.weixin.qq.com (port 443). If you are behind a corporate proxy, the WebSocket upgrade may be blocked.");
        } catch (Exception e) {
            log.debug("[wecom-verify] error: {}", e.getMessage());
            long ms = System.currentTimeMillis() - t0;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return VerificationResult.failed(ms,
                    "Could not reach WeCom: " + cause.getClass().getSimpleName(),
                    null, cause.getMessage());
        } finally {
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "verify done")
                            .orTimeout(2, TimeUnit.SECONDS)
                            .exceptionally(ex -> null)
                            .join();
                } catch (Exception ignored) {
                    // best-effort close
                }
            }
        }
    }

    /** Map common WeCom errcodes to the form field most likely to fix them. */
    private static String invalidFieldFor(int errcode) {
        // 40001..40015 family is "invalid credentials / signature" — the
        // smart-bot API does not document a stable taxonomy, so we apply a
        // conservative bucket: everything not network-class points at secret,
        // because bot_id format errors are rare (the QR flow guarantees it).
        return errcode >= 40000 && errcode < 50000 ? "secret" : null;
    }

    private static String hintFor(int errcode, String errmsg) {
        return switch (errcode) {
            case 40001, 40014 -> "Secret rejected. Re-scan the QR in WeCom — your Secret may have been rotated.";
            case 40013 -> "Bot ID is malformed. Re-scan the QR to refresh it.";
            case 41001 -> "Authorization expired. Re-scan to obtain a fresh token.";
            default -> errmsg != null && !errmsg.isBlank()
                    ? "WeCom errcode " + errcode + " — " + errmsg
                    : "WeCom errcode " + errcode + ". Re-scanning the QR usually resolves transient signature issues.";
        };
    }

    private static String maskBotId(String botId) {
        if (botId.length() <= 8) return botId;
        return botId.substring(0, 6) + "…" + botId.substring(botId.length() - 4);
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * Minimal WebSocket listener that buffers text frames and resolves the
     * first JSON frame whose {@code req_id} starts with {@code aibot_subscribe}.
     * Other frames are ignored — the verifier never enters the message loop.
     */
    private final class AckWaiter implements WebSocket.Listener {

        private final StringBuilder buf = new StringBuilder();
        private final CompletableFuture<Map<String, Object>> ackFuture = new CompletableFuture<>();
        private final AtomicReference<Boolean> openedFlag = new AtomicReference<>(false);

        @Override
        public void onOpen(WebSocket webSocket) {
            openedFlag.set(true);
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String full = buf.toString();
                buf.setLength(0);
                tryResolve(full);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            buf.append(new String(bytes));
            if (last) {
                String full = buf.toString();
                buf.setLength(0);
                tryResolve(full);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!ackFuture.isDone()) {
                ackFuture.completeExceptionally(new RuntimeException(
                        "WebSocket closed before ack: code=" + statusCode + ", reason=" + reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!ackFuture.isDone()) ackFuture.completeExceptionally(error);
        }

        @SuppressWarnings("unchecked")
        private void tryResolve(String json) {
            try {
                Map<String, Object> frame = objectMapper.readValue(json, Map.class);
                Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
                String reqId = String.valueOf(headers.getOrDefault("req_id", ""));
                if (reqId.startsWith("aibot_subscribe")) {
                    ackFuture.complete(frame);
                }
                // Other frames (heartbeat ack, server-pushed events) — ignored.
            } catch (Exception e) {
                // Malformed frame is not fatal — keep waiting until the timeout fires.
                log.debug("[wecom-verify] non-JSON or unparseable frame ignored: {}", e.getMessage());
            }
        }

        Map<String, Object> awaitAck(long timeoutMs) throws Exception {
            try {
                return ackFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }
        }

        boolean opened() {
            return Boolean.TRUE.equals(openedFlag.get());
        }
    }
}
