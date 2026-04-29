package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业微信智能机器人渠道适配器 — WebSocket 长连接模式
 * <p>
 * 基于企业微信「智能机器人」API 长连接协议（wecom-aibot-python-sdk 逆向）：
 * <ul>
 *   <li>WebSocket 连接 wss://openws.work.weixin.qq.com</li>
 *   <li>bot_id + secret 认证（aibot_subscribe 帧）</li>
 *   <li>30 秒心跳（ping 帧）</li>
 *   <li>aibot_msg_callback / aibot_event_callback 消息推送</li>
 *   <li>reply_stream 流式回复（覆盖更新"思考中..."）</li>
 *   <li>send_message 主动推送</li>
 * </ul>
 * <p>
 * 用户在企业微信后台创建「智能机器人」→ 选择「API 模式 → 配置长连接」
 * → 获得 bot_id 和 secret → 填入 MateClaw → 启动即可对话。
 * 无需公网 IP，无需回调 URL。
 * <p>
 * 配置项（configJson）：
 * <ul>
 *   <li>bot_id: 机器人 ID</li>
 *   <li>secret: 机器人 Secret</li>
 *   <li>welcome_text: 欢迎消息（可选）</li>
 *   <li>media_download_enabled: 是否下载媒体文件（默认 true）</li>
 *   <li>media_dir: 媒体文件保存目录（默认 data/media）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
public class WeComChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "wecom";

    /** 企业微信智能机器人 WebSocket 地址 */
    private static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";

    /** 心跳间隔 30 秒 */
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    /** 连续未收到 pong 的最大次数（超过则认为连接已死） */
    private static final int MAX_MISSED_PONG = 2;

    /** 回复 ACK 等待超时 5 秒 */
    private static final long REPLY_ACK_TIMEOUT_MS = 5_000;

    /** 消息去重：最大记录数 */
    private static final int PROCESSED_IDS_MAX = 2000;

    // ==================== WebSocket 命令常量 ====================

    private static final String CMD_SUBSCRIBE = "aibot_subscribe";
    private static final String CMD_HEARTBEAT = "ping";
    private static final String CMD_RESPONSE = "aibot_respond_msg";
    private static final String CMD_RESPONSE_WELCOME = "aibot_respond_welcome_msg";
    private static final String CMD_SEND_MSG = "aibot_send_msg";
    private static final String CMD_CALLBACK = "aibot_msg_callback";
    private static final String CMD_EVENT_CALLBACK = "aibot_event_callback";

    // ==================== 媒体上传命令常量 ====================

    private static final String CMD_UPLOAD_INIT = "aibot_upload_media_init";
    private static final String CMD_UPLOAD_CHUNK = "aibot_upload_media_chunk";
    private static final String CMD_UPLOAD_FINISH = "aibot_upload_media_finish";

    /** 上传分块大小：512KB */
    private static final int UPLOAD_CHUNK_SIZE = 512 * 1024;

    /** 上传 ACK 超时：30 秒（大文件上传需要更长超时） */
    private static final long UPLOAD_ACK_TIMEOUT_MS = 30_000;

    // ==================== 运行时状态 ====================

    private HttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile Thread wsThread;

    /** 心跳定时任务 */
    private volatile ScheduledFuture<?> heartbeatFuture;

    /** 连续未收到 pong 的计数 */
    private final AtomicInteger missedPongCount = new AtomicInteger(0);

    /** 消息去重集合 */
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    /** 回复 ACK 等待：reqId -> CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pendingAcks = new ConcurrentHashMap<>();

    /** 回复队列：reqId -> 串行队列（保证同一 reqId 的回复按序发送） */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<ReplyTask>> replyQueues = new ConcurrentHashMap<>();

    /** 回复队列处理线程池 */
    private final ExecutorService replyExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wecom-reply");
        t.setDaemon(true);
        return t;
    });

    /** WebSocket 消息碎片缓冲区 */
    private final StringBuilder wsBuffer = new StringBuilder();

    /** 请求 ID 计数器 */
    private final AtomicInteger reqIdCounter = new AtomicInteger(0);

    /** 记录消息中 reqId -> frame 的映射，用于 reply_stream 回复 */
    private final ConcurrentHashMap<String, Map<String, Object>> pendingFrames = new ConcurrentHashMap<>();

    /** 媒体上传串行锁（每个适配器实例同一时间只允许一个上传） */
    private final Semaphore uploadLock = new Semaphore(1);

    /** 回复上下文：replyToken -> (frameReqId, processingStreamId)，用于 sendContentParts 回写 */
    private final ConcurrentHashMap<String, WeComReplyContext> replyContexts = new ConcurrentHashMap<>();

    private record WeComReplyContext(String frameReqId, String processingStreamId) {}

    /**
     * Single-flight guard for failure signals. JDK WebSocket can fire onClose
     * AND onError for the same outage, plus connect-exception and heartbeat
     * timeout, all routing to the disconnect path. The first one wins; the
     * rest are deduped. Cleared at the start of each new connect attempt and
     * after auth_succeed in markReady().
     */
    private final AtomicBoolean disconnectInflight = new AtomicBoolean(false);

    public WeComChannelAdapter(ChannelEntity channelEntity,
                               ChannelMessageRouter messageRouter,
                               ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
        // Default to 8 bounded attempts (~4 minutes total at 2s..30s exponential)
        // so the UI eventually settles in ERROR instead of getting stuck in
        // RECONNECTING forever. User config still overrides (-1 = infinite).
        int maxAttempts = 8;
        Object val = config.get("max_reconnect_attempts");
        if (val instanceof Number n) {
            maxAttempts = n.intValue();
        } else if (val instanceof String s) {
            try { maxAttempts = Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        this.backoff = new ExponentialBackoff(2000, 30000, 2.0, maxAttempts);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void doStart() {
        String botId = getConfigString("bot_id");
        String secret = getConfigString("secret");

        if (botId == null || botId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("WeCom bot channel requires bot_id and secret in configJson");
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        connectWebSocket(botId, secret);

        log.info("[wecom] WeCom bot channel initialized: botId={}, maxReconnectAttempts={}",
                botId.length() > 12 ? botId.substring(0, 12) + "..." : botId, backoff.getMaxAttempts());
    }

    @Override
    protected void doStop() {
        releaseConnectionResources("stopped");
        // doStop also clears history that survives reconnects (processedMessageIds)
        processedMessageIds.clear();
        log.info("[wecom] WeCom bot channel stopped");
    }

    @Override
    protected void doReconnect() {
        log.info("[wecom] Reconnecting WebSocket...");
        releaseConnectionResources("reconnecting");

        // releaseConnectionResources nulls httpClient — rebuild a fresh one.
        // This is the core fix: each reconnect starts from a clean SSL/I-O
        // surface, mirroring what manual stop+start has been doing in the field.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String botId = getConfigString("bot_id");
        String secret = getConfigString("secret");
        connectWebSocket(botId, secret);
    }

    /**
     * Release every per-connection resource so a fresh HttpClient + WebSocket
     * are always built next. Shared by doStop (channel teardown) and
     * doReconnect (auto-recovery cycle).
     *
     * <p>Why drop {@code httpClient} too: the JDK HttpClient caches SSL
     * sessions and keeps an async selector loop. After certain WS error paths
     * the SSL session can be poisoned, causing every subsequent handshake to
     * be RST'd by the server ("Remote host terminated the handshake"). Dropping
     * the client forces a clean rebuild — this is exactly what manual
     * "Disable + Enable" did to recover.
     */
    private void releaseConnectionResources(String reason) {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                        .orTimeout(2, TimeUnit.SECONDS)
                        .exceptionally(ex -> null)
                        .join();
            } catch (Exception e) {
                log.debug("[wecom] release: ws close: {}", e.getMessage());
            }
            webSocket = null;
        }
        if (wsThread != null) {
            wsThread.interrupt();
            try {
                wsThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            wsThread = null;
        }
        pendingAcks.forEach((k, f) ->
                f.completeExceptionally(new RuntimeException("Channel " + reason)));
        pendingAcks.clear();
        replyQueues.clear();
        pendingFrames.clear();
        replyContexts.clear();
        missedPongCount.set(0);

        this.httpClient = null;
    }

    // ==================== WebSocket 连接 ====================

    /**
     * 在守护线程中建立 WebSocket 连接
     */
    private void connectWebSocket(String botId, String secret) {
        // Fresh attempt — allow new failure signals to register again.
        disconnectInflight.set(false);

        wsThread = new Thread(() -> {
            try {
                log.info("[wecom] WebSocket connecting to {}...", DEFAULT_WS_URL);

                CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(DEFAULT_WS_URL), new WeComWebSocketListener());

                webSocket = wsFuture.get(20, TimeUnit.SECONDS);
                log.info("[wecom] WebSocket connected, sending auth...");

                // 发送认证帧
                sendAuth(botId, secret);

            } catch (Exception e) {
                log.error("[wecom] WebSocket connection failed: {}", e.getMessage(), e);
                handleFailure("WebSocket connection failed: " + e.getMessage());
            }
        }, "wecom-ws-" + channelEntity.getId());
        wsThread.setDaemon(true);
        wsThread.start();
    }

    /**
     * Single-flight failure handler. JDK WebSocket can fire onClose AND
     * onError for the same outage, plus connect-exception and heartbeat
     * timeout, all wanting to trigger reconnect. Without dedup this fans out
     * into 2-4 concurrent reconnect attempts, which collide on shared state
     * (httpClient, wsThread) and amplify failure into a storm.
     *
     * <p>Only the first signal of a given outage gets through; later signals
     * are logged at debug level and dropped. The flag is cleared at the start
     * of each new connect attempt and on auth success (markReady).
     */
    private void handleFailure(String reason) {
        if (!disconnectInflight.compareAndSet(false, true)) {
            log.debug("[wecom] handleFailure dedup: {}", reason);
            return;
        }
        if (!running.get()) {
            return;
        }
        onDisconnected(reason);
    }

    /**
     * Suppressed at the framework's call site. {@link AbstractChannelAdapter}
     * invokes this immediately after {@code doReconnect()} returns, but our
     * doReconnect only fire-and-forget schedules an async WS connect — the
     * connection isn't actually ready yet. Letting the framework reset
     * {@code backoff} here causes attempts to stall at #1 forever.
     *
     * <p>Real reset happens in {@link #markReady()} when the WeCom
     * {@code aibot_subscribe} auth response confirms the session is up.
     */
    @Override
    protected void onReconnectSuccess() {
        // intentionally empty
    }

    /**
     * Called when WeCom auth_succeed frame is received — the only point at
     * which the connection is genuinely usable. Resets backoff and clears
     * the failure dedup flag so the next outage (if any) can register.
     */
    private void markReady() {
        super.onReconnectSuccess();
        disconnectInflight.set(false);
    }

    /**
     * WebSocket 监听器：接收消息帧并分发处理
     */
    private class WeComWebSocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("[wecom] WebSocket onOpen");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            wsBuffer.append(data);
            if (last) {
                String fullMessage = wsBuffer.toString();
                wsBuffer.setLength(0);
                handleWebSocketFrame(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            wsBuffer.append(new String(bytes));
            if (last) {
                String fullMessage = wsBuffer.toString();
                wsBuffer.setLength(0);
                handleWebSocketFrame(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("[wecom] WebSocket closed: code={}, reason={}", statusCode, reason);
            handleFailure("WebSocket closed: code=" + statusCode + ", reason=" + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("[wecom] WebSocket error: {}", error.getMessage());
            handleFailure("WebSocket error: " + error.getMessage());
        }
    }

    // ==================== 帧处理 ====================

    /**
     * 处理收到的 WebSocket JSON 帧
     */
    @SuppressWarnings("unchecked")
    private void handleWebSocketFrame(String jsonStr) {
        try {
            Map<String, Object> frame = objectMapper.readValue(jsonStr, Map.class);
            String cmd = (String) frame.get("cmd");

            // 消息推送
            if (CMD_CALLBACK.equals(cmd)) {
                handleMessageCallback(frame);
                return;
            }

            // 事件推送
            if (CMD_EVENT_CALLBACK.equals(cmd)) {
                handleEventCallback(frame);
                return;
            }

            // 无 cmd 的帧：认证响应、心跳响应或回复 ACK
            Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
            String reqId = (String) headers.getOrDefault("req_id", "");

            // 检查是否是回复消息的 ACK
            CompletableFuture<Map<String, Object>> ackFuture = pendingAcks.remove(reqId);
            if (ackFuture != null) {
                Integer errcode = frame.get("errcode") instanceof Number n ? n.intValue() : null;
                if (errcode != null && errcode != 0) {
                    ackFuture.completeExceptionally(new RuntimeException(
                            "Reply ACK error: errcode=" + errcode + ", errmsg=" + frame.get("errmsg")));
                } else {
                    ackFuture.complete(frame);
                }
                return;
            }

            // 认证响应
            if (reqId.startsWith(CMD_SUBSCRIBE)) {
                Integer errcode = frame.get("errcode") instanceof Number n ? n.intValue() : null;
                if (errcode != null && errcode != 0) {
                    log.error("[wecom] Authentication failed: errcode={}, errmsg={}", errcode, frame.get("errmsg"));
                    lastError = "Authentication failed: " + frame.get("errmsg");
                    // RFC-080 §8 follow-up: route auth_succeed errcode!=0 through
                    // the same single-flight failure path the transport-level
                    // failures use. Without this the WS stays open as a zombie
                    // (no heartbeat, no reconnect) and connectionState stays
                    // CONNECTED — the UI then shows "已连接" while no message
                    // can ever arrive. Letting handleFailure run flips the state
                    // to RECONNECTING and (after maxAttempts) to ERROR so the
                    // green dot stops lying.
                    handleFailure("Authentication failed: errcode=" + errcode
                            + ", errmsg=" + frame.get("errmsg"));
                    return;
                }
                log.info("[wecom] Authentication successful");
                missedPongCount.set(0);
                markReady();
                startHeartbeat();
                return;
            }

            // 心跳响应
            if (reqId.startsWith(CMD_HEARTBEAT)) {
                Integer errcode = frame.get("errcode") instanceof Number n ? n.intValue() : null;
                if (errcode != null && errcode != 0) {
                    log.warn("[wecom] Heartbeat ACK error: errcode={}", errcode);
                    return;
                }
                missedPongCount.set(0);
                log.debug("[wecom] Heartbeat ACK received");
                return;
            }

            log.debug("[wecom] Received unknown frame: {}", jsonStr.length() > 200 ? jsonStr.substring(0, 200) : jsonStr);

        } catch (Exception e) {
            log.error("[wecom] Failed to handle WebSocket frame: {}", e.getMessage(), e);
        }
    }

    // ==================== 认证 & 心跳 ====================

    private void sendAuth(String botId, String secret) {
        String reqId = generateReqId(CMD_SUBSCRIBE);
        Map<String, Object> frame = Map.of(
                "cmd", CMD_SUBSCRIBE,
                "headers", Map.of("req_id", reqId),
                "body", Map.of("bot_id", botId, "secret", secret)
        );
        sendFrame(frame);
        log.info("[wecom] Auth frame sent");
    }

    private void startHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        heartbeatFuture = ensureReconnectScheduler().scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            try {
                sendHeartbeat();
            } catch (Exception e) {
                log.warn("[wecom] Heartbeat send failed: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.debug("[wecom] Heartbeat started (interval={}ms)", HEARTBEAT_INTERVAL_MS);
    }

    private void sendHeartbeat() {
        if (missedPongCount.get() >= MAX_MISSED_PONG) {
            log.warn("[wecom] No heartbeat ACK for {} consecutive pings, connection considered dead",
                    missedPongCount.get());
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
                heartbeatFuture = null;
            }
            handleFailure("Heartbeat timeout: " + missedPongCount.get() + " missed pongs");
            return;
        }

        missedPongCount.incrementAndGet();
        String reqId = generateReqId(CMD_HEARTBEAT);
        sendFrame(Map.of(
                "cmd", CMD_HEARTBEAT,
                "headers", Map.of("req_id", reqId)
        ));
        log.debug("[wecom] Heartbeat sent (missed={})", missedPongCount.get());
    }

    // ==================== 消息接收 ====================

    /**
     * 处理消息推送回调 (aibot_msg_callback)
     */
    @SuppressWarnings("unchecked")
    private void handleMessageCallback(Map<String, Object> frame) {
        try {
            Map<String, Object> body = (Map<String, Object>) frame.getOrDefault("body", Map.of());
            Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
            String frameReqId = (String) headers.getOrDefault("req_id", "");

            String msgType = (String) body.getOrDefault("msgtype", "");
            Map<String, Object> fromMap = (Map<String, Object>) body.getOrDefault("from", Map.of());
            String senderId = (String) fromMap.getOrDefault("userid", "");
            String chatId = (String) body.getOrDefault("chatid", "");
            String chatType = (String) body.getOrDefault("chattype", "single");
            String msgId = (String) body.getOrDefault("msgid", "");

            // 补充 msgId（如果为空则用 senderId + send_time 合成）
            if (msgId.isBlank()) {
                msgId = senderId + "_" + body.getOrDefault("send_time", System.currentTimeMillis());
            }

            // 消息去重
            if (!msgId.isBlank() && !processedMessageIds.add(msgId)) {
                log.debug("[wecom] Duplicate msgId: {}, skipping", msgId);
                return;
            }
            // 去重集合超限清理
            if (processedMessageIds.size() > PROCESSED_IDS_MAX) {
                int toRemove = processedMessageIds.size() / 2;
                var it = processedMessageIds.iterator();
                while (it.hasNext() && toRemove > 0) { it.next(); it.remove(); toRemove--; }
            }

            // 保存 frame 用于 reply_stream
            pendingFrames.put(frameReqId, frame);

            List<MessageContentPart> contentParts = new ArrayList<>();
            String textContent = null;
            boolean hasVoice = false;

            switch (msgType) {
                case "text" -> {
                    Map<String, Object> textBody = (Map<String, Object>) body.getOrDefault("text", Map.of());
                    textContent = ((String) textBody.getOrDefault("content", "")).trim();
                    if (!textContent.isBlank()) {
                        contentParts.add(MessageContentPart.text(textContent));
                    }
                }
                case "image" -> {
                    Map<String, Object> imgBody = (Map<String, Object>) body.getOrDefault("image", Map.of());
                    String url = (String) imgBody.getOrDefault("url", "");
                    String aesKey = (String) imgBody.getOrDefault("aeskey", "");
                    if (getConfigBoolean("media_download_enabled", true) && !url.isBlank()) {
                        String localPath = downloadAndDecryptMedia(url, aesKey, msgId, "image.jpg");
                        if (localPath != null) {
                            contentParts.add(MessageContentPart.image(localPath, url));
                        } else {
                            contentParts.add(MessageContentPart.image(url, url));
                        }
                    } else if (!url.isBlank()) {
                        contentParts.add(MessageContentPart.image(url, url));
                    }
                    textContent = "[图片]";
                }
                case "voice" -> {
                    hasVoice = true;
                    Map<String, Object> voiceBody = (Map<String, Object>) body.getOrDefault("voice", Map.of());
                    String asrText = ((String) voiceBody.getOrDefault("content", "")).trim();
                    if (!asrText.isBlank()) {
                        contentParts.add(MessageContentPart.text(asrText));
                        textContent = asrText;
                    } else {
                        textContent = "[语音消息]";
                    }
                }
                case "file" -> {
                    Map<String, Object> fileBody = (Map<String, Object>) body.getOrDefault("file", Map.of());
                    String url = (String) fileBody.getOrDefault("url", "");
                    String aesKey = (String) fileBody.getOrDefault("aeskey", "");
                    String filename = (String) fileBody.getOrDefault("filename", "file.bin");
                    if (getConfigBoolean("media_download_enabled", true) && !url.isBlank()) {
                        String localPath = downloadAndDecryptMedia(url, aesKey, msgId, filename);
                        if (localPath != null) {
                            contentParts.add(MessageContentPart.file(localPath, filename, null));
                        }
                    }
                    textContent = "[文件: " + filename + "]";
                }
                case "mixed" -> {
                    Map<String, Object> mixedBody = (Map<String, Object>) body.getOrDefault("mixed", Map.of());
                    List<Map<String, Object>> items = (List<Map<String, Object>>) mixedBody.getOrDefault("msg_item", List.of());
                    StringBuilder textBuilder = new StringBuilder();
                    for (Map<String, Object> item : items) {
                        String itemType = (String) item.getOrDefault("msgtype", "");
                        if ("text".equals(itemType)) {
                            Map<String, Object> t = (Map<String, Object>) item.getOrDefault("text", Map.of());
                            String txt = ((String) t.getOrDefault("content", "")).trim();
                            if (!txt.isBlank()) {
                                textBuilder.append(txt).append('\n');
                            }
                        } else if ("image".equals(itemType)) {
                            // 与独立 image 消息对齐：下载 + AES 解密
                            Map<String, Object> img = (Map<String, Object>) item.getOrDefault("image", Map.of());
                            String url = (String) img.getOrDefault("url", "");
                            String aesKey = (String) img.getOrDefault("aeskey", "");
                            if (getConfigBoolean("media_download_enabled", true) && !url.isBlank()) {
                                String localPath = downloadAndDecryptMedia(url, aesKey, msgId, "mixed_image.jpg");
                                if (localPath != null) {
                                    contentParts.add(MessageContentPart.image(localPath, url));
                                } else {
                                    contentParts.add(MessageContentPart.image(url, url));
                                }
                            } else if (!url.isBlank()) {
                                contentParts.add(MessageContentPart.image(url, url));
                            }
                        } else if ("voice".equals(itemType)) {
                            Map<String, Object> v = (Map<String, Object>) item.getOrDefault("voice", Map.of());
                            String asrText = ((String) v.getOrDefault("content", "")).trim();
                            if (!asrText.isBlank()) {
                                hasVoice = true;
                                textBuilder.append(asrText).append('\n');
                            }
                        }
                    }
                    textContent = textBuilder.toString().trim();
                    if (!textContent.isBlank()) {
                        contentParts.add(0, MessageContentPart.text(textContent));
                    }
                }
                default -> {
                    log.debug("[wecom] Ignoring unsupported message type: {}", msgType);
                    return;
                }
            }

            if (contentParts.isEmpty()) {
                if (textContent != null && !textContent.isBlank()) {
                    contentParts.add(MessageContentPart.text(textContent));
                } else {
                    return;
                }
            }

            // 发送"🤔 思考中..."处理指示器
            String processingStreamId = "";
            if (textContent != null && !textContent.isBlank()) {
                processingStreamId = generateReqId("stream");
                try {
                    replyStream(frameReqId, processingStreamId, "🤔 思考中...", false);
                } catch (Exception e) {
                    log.debug("[wecom] Failed to send processing indicator: {}", e.getMessage());
                }
            }

            boolean isGroup = "group".equals(chatType);
            String effectiveChatId = isGroup ? chatId : null;

            // conversationId 格式：wecom:{userid} 或 wecom:group:{chatid}
            // 由 ChannelMessageRouter.buildConversationId() 根据 channelType + chatId/senderId 构建

            ChannelMessage channelMessage = ChannelMessage.builder()
                    .messageId(msgId)
                    .channelType(CHANNEL_TYPE)
                    .senderId(senderId)
                    .senderName(senderId)
                    .chatId(effectiveChatId)
                    .content(textContent != null ? textContent.trim() : "")
                    .contentType(msgType)
                    .contentParts(contentParts)
                    .inputMode(hasVoice ? "voice" : "text")
                    .timestamp(LocalDateTime.now())
                    .replyToken(isGroup ? chatId : senderId)
                    .rawPayload(Map.of(
                            "wecom_frame_req_id", frameReqId,
                            "wecom_processing_stream_id", processingStreamId,
                            "wecom_chat_type", chatType,
                            "wecom_chatid", chatId
                    ))
                    .build();

            // 保存回复上下文，供 sendContentParts / renderAndSend 使用
            String replyToken = isGroup ? chatId : senderId;
            replyContexts.put(replyToken, new WeComReplyContext(frameReqId, processingStreamId));

            log.info("[wecom] Received message: sender={}, chatType={}, msgType={}, textLen={}",
                    senderId.length() > 20 ? senderId.substring(0, 20) : senderId,
                    chatType, msgType,
                    textContent != null ? textContent.length() : 0);

            onMessage(channelMessage);

        } catch (Exception e) {
            log.error("[wecom] Failed to handle message callback: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理事件推送回调 (aibot_event_callback)
     */
    @SuppressWarnings("unchecked")
    private void handleEventCallback(Map<String, Object> frame) {
        try {
            Map<String, Object> body = (Map<String, Object>) frame.getOrDefault("body", Map.of());
            Map<String, Object> event = body.get("event") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String eventType = (String) event.getOrDefault("eventtype", "");

            if ("enter_chat".equals(eventType)) {
                String welcomeText = getConfigString("welcome_text", "");
                if (!welcomeText.isBlank()) {
                    try {
                        Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
                        String reqId = (String) headers.getOrDefault("req_id", "");
                        replyWelcome(reqId, welcomeText);
                        log.info("[wecom] Welcome message sent");
                    } catch (Exception e) {
                        log.warn("[wecom] Failed to send welcome message: {}", e.getMessage());
                    }
                }
                return;
            }

            log.debug("[wecom] Ignoring event type: {}", eventType);
        } catch (Exception e) {
            log.error("[wecom] Failed to handle event callback: {}", e.getMessage(), e);
        }
    }

    // ==================== 消息发送 ====================

    @Override
    public void sendMessage(String targetId, String content) {
        if (webSocket == null) {
            log.warn("[wecom] Channel not started, cannot send message");
            return;
        }

        // 检查是否有 pending frame（用于 reply_stream 覆盖"思考中..."）
        // sendMessage 被 renderAndSend 调用时，尝试用 reply_stream 覆盖
        // 但由于 rawPayload 信息在 ChannelMessageRouter 层已丢失，
        // 这里走 send_message 主动推送路径
        sendMessageToChat(targetId, content);
    }

    /**
     * 通过 WebSocket send_message 命令主动推送消息
     */
    private void sendMessageToChat(String chatId, String content) {
        if (webSocket == null || content == null || content.isBlank()) return;
        try {
            String reqId = generateReqId(CMD_SEND_MSG);
            Map<String, Object> frame = Map.of(
                    "cmd", CMD_SEND_MSG,
                    "headers", Map.of("req_id", reqId),
                    "body", Map.of(
                            "chatid", chatId,
                            "msgtype", "markdown",
                            "markdown", Map.of("content", content)
                    )
            );
            sendFrameWithAck(reqId, frame);
        } catch (Exception e) {
            log.error("[wecom] Failed to send message to {}: {}", chatId, e.getMessage(), e);
        }
    }

    /**
     * 覆写 renderAndSend：如果有 processing_stream_id 则用 reply_stream 覆盖"思考中..."
     */
    @Override
    public void renderAndSend(String targetId, String content) {
        // 消费回复上下文（如果有的话）
        WeComReplyContext ctx = replyContexts.remove(targetId);

        // 先进行正常的内容渲染（过滤 thinking、分割长文本）
        boolean filterThinking = getConfigBoolean("filter_thinking", true);
        boolean filterToolMessages = getConfigBoolean("filter_tool_messages", true);
        String format = getConfigString("message_format", "auto");
        int maxLen = vip.mate.channel.ChannelMessageRenderer.PLATFORM_LIMITS.getOrDefault(getChannelType(), 2048);

        List<String> segments = vip.mate.channel.ChannelMessageRenderer.renderForChannel(
                content, filterThinking, filterToolMessages, format, maxLen);

        boolean first = true;
        for (String rawSegment : segments) {
            // WeCom 专用：格式化 Markdown 表格，统一列宽后在企微渲染正确
            String segment = formatMarkdownTables(rawSegment);
            // 第一条分段用 processingStreamId 覆盖"思考中..."
            if (first && ctx != null && ctx.processingStreamId() != null
                    && !ctx.processingStreamId().isBlank()) {
                replyStream(ctx.frameReqId(), ctx.processingStreamId(), segment, true);
                first = false;
            } else {
                sendMessage(targetId, segment);
            }
        }
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        WeComReplyContext ctx = replyContexts.remove(targetId);
        boolean sentText = false;
        boolean firstText = true;

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            try {
                switch (part.getType()) {
                    case "text" -> {
                        if (part.getText() != null && !part.getText().isBlank()) {
                            // 第一条文本用 processingStreamId 覆盖"思考中..."
                            if (firstText && ctx != null && ctx.processingStreamId() != null
                                    && !ctx.processingStreamId().isBlank()) {
                                replyStream(ctx.frameReqId(), ctx.processingStreamId(), part.getText(), true);
                                firstText = false;
                            } else {
                                sendMessage(targetId, part.getText());
                            }
                            sentText = true;
                        }
                    }
                    case "refusal" -> {
                        // 模型拒绝回复（如内容策略限制），以文本形式发送
                        String refusalText = part.getText();
                        if (refusalText != null && !refusalText.isBlank()) {
                            sendMessage(targetId, "⚠️ " + refusalText);
                            sentText = true;
                        }
                    }
                    case "image" -> sendImagePart(targetId, part, ctx);
                    case "audio" -> sendAudioPart(targetId, part, ctx);
                    case "file" -> sendFilePart(targetId, part, ctx);
                    default -> {
                        if (part.getText() != null) {
                            sendMessage(targetId, part.getText());
                            sentText = true;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[wecom] Failed to send content part ({}): {}", part.getType(), e.getMessage());
                sendFallbackText(targetId, part);
            }
        }

        // 如果没有发送文本但有处理指示器，清除"思考中..."
        if (!sentText && ctx != null && ctx.processingStreamId() != null
                && !ctx.processingStreamId().isBlank()) {
            try {
                replyStream(ctx.frameReqId(), ctx.processingStreamId(), "✅ Done", true);
            } catch (Exception e) {
                log.debug("[wecom] Failed to clear processing indicator: {}", e.getMessage());
            }
        }
    }

    /**
     * 发送图片部分：压缩 → 上传 → 发送 media_id
     */
    private void sendImagePart(String targetId, MessageContentPart part, WeComReplyContext ctx) {
        byte[] imageBytes = resolveFileBytes(part);
        if (imageBytes == null) {
            sendFallbackText(targetId, part);
            return;
        }

        String fileName = part.getFileName() != null ? part.getFileName() : "image.jpg";
        imageBytes = WeComImageCompressor.compressIfNeeded(imageBytes, fileName);

        String mediaId = uploadMedia(imageBytes, fileName, "image");
        if (mediaId != null) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            sendMediaMessage(targetId, mediaId, "image", frameReqId);
        } else {
            sendFallbackText(targetId, part);
        }
    }

    /**
     * 发送文件部分：上传 → 发送 media_id
     */
    private void sendFilePart(String targetId, MessageContentPart part, WeComReplyContext ctx) {
        byte[] fileBytes = resolveFileBytes(part);
        if (fileBytes == null) {
            sendFallbackText(targetId, part);
            return;
        }

        String fileName = part.getFileName() != null ? part.getFileName() : "file.bin";
        String mediaId = uploadMedia(fileBytes, fileName, "file");
        if (mediaId != null) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            sendMediaMessage(targetId, mediaId, "file", frameReqId);
        } else {
            sendFallbackText(targetId, part);
        }
    }

    /**
     * 发送音频部分：读取字节 → 上传 → 发送
     * <p>
     * WeCom 原生语音消息要求 AMR 格式。TTS 输出为 MP3，
     * Phase 1 以 file 类型发送（用户可点击播放），避免引入 AMR 转码依赖。
     * 非 AMR 格式走 file 类型而非 voice 类型，避免企微语音播放兼容问题。
     */
    private void sendAudioPart(String targetId, MessageContentPart part, WeComReplyContext ctx) {
        byte[] audioBytes = resolveFileBytes(part);
        if (audioBytes == null) {
            sendFallbackText(targetId, part);
            return;
        }

        String fileName = part.getFileName() != null ? part.getFileName() : "voice_reply.mp3";
        boolean isAmr = fileName.toLowerCase().endsWith(".amr");

        // AMR 格式：以原生 voice 类型发送（语音气泡）
        // 其他格式（MP3 等）：以 file 类型发送（文件卡片，可点击播放）
        String uploadType = isAmr ? "voice" : "file";
        String mediaId = uploadMedia(audioBytes, fileName, uploadType);
        if (mediaId != null) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            sendMediaMessage(targetId, mediaId, uploadType, frameReqId);
            log.info("[wecom] Audio sent as {}: {} ({}KB)", uploadType, fileName, audioBytes.length / 1024);
        } else {
            sendFallbackText(targetId, part);
        }
    }

    /**
     * 从 MessageContentPart 解析文件字节：优先本地路径，其次 URL 下载
     */
    private byte[] resolveFileBytes(MessageContentPart part) {
        // 本地路径
        if (part.getPath() != null && !part.getPath().isBlank()) {
            try {
                Path p = Path.of(part.getPath());
                if (Files.exists(p)) {
                    return Files.readAllBytes(p);
                }
            } catch (Exception e) {
                log.debug("[wecom] Failed to read local file {}: {}", part.getPath(), e.getMessage());
            }
        }
        // URL 下载
        String url = part.getFileUrl();
        if (url != null && !url.isBlank() && httpClient != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return response.body();
                }
            } catch (Exception e) {
                log.debug("[wecom] Failed to download file from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 降级发送：上传失败时退回 Markdown 文本
     */
    private void sendFallbackText(String targetId, MessageContentPart part) {
        switch (part.getType()) {
            case "image" -> {
                String url = part.getFileUrl() != null ? part.getFileUrl() : part.getMediaId();
                if (url != null) sendMessage(targetId, "![image](" + url + ")");
            }
            case "file" -> {
                String name = part.getFileName() != null ? part.getFileName() : "file";
                sendMessage(targetId, "[文件: " + name + "]");
            }
            case "audio" -> sendMessage(targetId, "[语音回复]");
            default -> { if (part.getText() != null) sendMessage(targetId, part.getText()); }
        }
    }

    // ==================== reply_stream 协议实现 ====================

    /**
     * 发送流式回复（reply_stream）
     * <p>
     * 通过 WebSocket 回复通道，使用相同 stream_id 可以覆盖更新已发送的消息。
     *
     * @param originalReqId 原始消息的 reqId（用于路由回复）
     * @param streamId      流式消息 ID（相同 ID 会覆盖之前的消息）
     * @param content       回复内容（支持 Markdown）
     * @param finish        是否结束流式消息
     */
    private void replyStream(String originalReqId, String streamId, String content, boolean finish) {
        Map<String, Object> streamBody = new LinkedHashMap<>();
        streamBody.put("id", streamId);
        streamBody.put("finish", finish);
        streamBody.put("content", content);

        Map<String, Object> body = Map.of(
                "msgtype", "stream",
                "stream", streamBody
        );

        Map<String, Object> frame = Map.of(
                "cmd", CMD_RESPONSE,
                "headers", Map.of("req_id", originalReqId),
                "body", body
        );

        sendFrameWithAck(originalReqId, frame);
    }

    /**
     * 发送欢迎消息
     */
    private void replyWelcome(String reqId, String text) {
        Map<String, Object> body = Map.of(
                "msgtype", "text",
                "text", Map.of("content", text)
        );
        Map<String, Object> frame = Map.of(
                "cmd", CMD_RESPONSE_WELCOME,
                "headers", Map.of("req_id", reqId),
                "body", body
        );
        sendFrameWithAck(reqId, frame);
    }

    // ==================== 媒体上传协议 ====================

    /**
     * 通过 WebSocket 分块上传文件到企业微信
     * <p>
     * 三阶段协议：
     *   1. Init: 发送文件元数据 → 获得 upload_id
     *   2. Chunks: 发送 base64 编码的 512KB 分块
     *   3. Finish: 完成上传 → 获得 media_id
     *
     * @param fileBytes  文件内容
     * @param fileName   文件名
     * @param mediaType  媒体类型："image" / "file" / "voice" / "video"
     * @return media_id，失败返回 null
     */
    @SuppressWarnings("unchecked")
    private String uploadMedia(byte[] fileBytes, String fileName, String mediaType) {
        if (webSocket == null || fileBytes == null || fileBytes.length == 0) {
            return null;
        }
        boolean acquired = false;
        try {
            acquired = uploadLock.tryAcquire(60, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[wecom] Upload lock timeout, another upload may be in progress");
                return null;
            }

            String md5 = md5Hex(fileBytes);
            int totalChunks = (int) Math.ceil((double) fileBytes.length / UPLOAD_CHUNK_SIZE);

            // Phase 1: Init
            String initReqId = generateReqId(CMD_UPLOAD_INIT);
            Map<String, Object> initBody = new LinkedHashMap<>();
            initBody.put("type", mediaType);
            initBody.put("filename", fileName);
            initBody.put("total_size", fileBytes.length);
            initBody.put("total_chunks", totalChunks);
            initBody.put("md5", md5);

            Map<String, Object> initFrame = Map.of(
                    "cmd", CMD_UPLOAD_INIT,
                    "headers", Map.of("req_id", initReqId),
                    "body", initBody
            );
            Map<String, Object> initAck = sendFrameWithAckBlocking(initReqId, initFrame, UPLOAD_ACK_TIMEOUT_MS);
            Map<String, Object> initAckBody = (Map<String, Object>) initAck.getOrDefault("body", Map.of());
            String uploadId = (String) initAckBody.getOrDefault("upload_id", "");
            if (uploadId.isBlank()) {
                log.error("[wecom] Upload init failed: empty upload_id");
                return null;
            }
            log.debug("[wecom] Upload init: upload_id={}, chunks={}", uploadId.substring(0, Math.min(20, uploadId.length())), totalChunks);

            // Phase 2: Chunks
            for (int i = 0; i < totalChunks; i++) {
                int offset = i * UPLOAD_CHUNK_SIZE;
                int length = Math.min(UPLOAD_CHUNK_SIZE, fileBytes.length - offset);
                byte[] chunk = Arrays.copyOfRange(fileBytes, offset, offset + length);
                String base64Data = Base64.getEncoder().encodeToString(chunk);

                String chunkReqId = generateReqId(CMD_UPLOAD_CHUNK);
                Map<String, Object> chunkBody = new LinkedHashMap<>();
                chunkBody.put("upload_id", uploadId);
                chunkBody.put("chunk_index", i);
                chunkBody.put("data", base64Data);

                Map<String, Object> chunkFrame = Map.of(
                        "cmd", CMD_UPLOAD_CHUNK,
                        "headers", Map.of("req_id", chunkReqId),
                        "body", chunkBody
                );
                sendFrameWithAckBlocking(chunkReqId, chunkFrame, UPLOAD_ACK_TIMEOUT_MS);
            }

            // Phase 3: Finish
            String finishReqId = generateReqId(CMD_UPLOAD_FINISH);
            Map<String, Object> finishFrame = Map.of(
                    "cmd", CMD_UPLOAD_FINISH,
                    "headers", Map.of("req_id", finishReqId),
                    "body", Map.of("upload_id", uploadId)
            );
            Map<String, Object> finishAck = sendFrameWithAckBlocking(finishReqId, finishFrame, UPLOAD_ACK_TIMEOUT_MS);
            Map<String, Object> finishAckBody = (Map<String, Object>) finishAck.getOrDefault("body", Map.of());
            String mediaId = (String) finishAckBody.getOrDefault("media_id", "");
            if (mediaId.isBlank()) {
                log.error("[wecom] Upload finish failed: empty media_id");
                return null;
            }

            log.info("[wecom] Upload completed: media_id={}, type={}, size={}KB",
                    mediaId.substring(0, Math.min(20, mediaId.length())), mediaType, fileBytes.length / 1024);
            return mediaId;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[wecom] Upload interrupted");
            return null;
        } catch (Exception e) {
            log.error("[wecom] Upload failed for {}: {}", fileName, e.getMessage(), e);
            return null;
        } finally {
            if (acquired) {
                uploadLock.release();
            }
        }
    }

    /**
     * 发送帧并阻塞等待 ACK 响应（用于上传协议需要读取返回值的场景）
     *
     * @return ACK 帧 Map
     * @throws RuntimeException 超时或错误
     */
    private Map<String, Object> sendFrameWithAckBlocking(String reqId, Map<String, Object> frame, long timeoutMs) {
        CompletableFuture<Map<String, Object>> ackFuture = new CompletableFuture<>();
        pendingAcks.put(reqId, ackFuture);
        sendFrame(frame);
        try {
            return ackFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingAcks.remove(reqId);
            throw new RuntimeException("Upload ACK timeout for reqId=" + reqId, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Upload ACK error for reqId=" + reqId, e.getCause());
        } catch (InterruptedException e) {
            pendingAcks.remove(reqId);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Upload interrupted", e);
        }
    }

    /**
     * 使用 media_id 发送媒体消息
     *
     * @param targetId   目标 ID（userId 或 chatId）
     * @param mediaId    上传后获得的 media_id
     * @param mediaType  媒体类型（image / file / voice / video）
     * @param frameReqId 原始消息 frameReqId（非 null 时通过 reply 路径，null 时通过主动推送）
     */
    private void sendMediaMessage(String targetId, String mediaId, String mediaType, String frameReqId) {
        Map<String, Object> mediaBody = new LinkedHashMap<>();
        mediaBody.put("msgtype", mediaType);
        mediaBody.put(mediaType, Map.of("media_id", mediaId));

        if (frameReqId != null && !frameReqId.isBlank()) {
            // Reply 路径：使用 aibot_respond_msg
            Map<String, Object> frame = Map.of(
                    "cmd", CMD_RESPONSE,
                    "headers", Map.of("req_id", frameReqId),
                    "body", mediaBody
            );
            sendFrameWithAck(frameReqId, frame);
        } else {
            // 主动推送路径：使用 aibot_send_msg
            mediaBody.put("chatid", targetId);
            String reqId = generateReqId(CMD_SEND_MSG);
            Map<String, Object> frame = Map.of(
                    "cmd", CMD_SEND_MSG,
                    "headers", Map.of("req_id", reqId),
                    "body", mediaBody
            );
            sendFrameWithAck(reqId, frame);
        }
    }

    // ==================== Markdown 表格格式化 ====================

    /**
     * 格式化 GFM Markdown 表格，使其在企业微信中对齐显示。
     * <p>
     * 企业微信要求表格列宽一致才能正确渲染。此方法解析表格，
     * 计算每列最大宽度，统一填充空格对齐。
     * 代码块内的表格不做处理。
     */
    static String formatMarkdownTables(String text) {
        if (text == null || !text.contains("|")) return text;

        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        int i = 0;
        boolean inCodeFence = false;

        while (i < lines.length) {
            String line = lines[i];
            String stripped = line.strip();

            // 跟踪代码块（``` 内的内容不处理）
            if (stripped.startsWith("```")) {
                inCodeFence = !inCodeFence;
                result.add(line);
                i++;
                continue;
            }
            if (inCodeFence) {
                result.add(line);
                i++;
                continue;
            }

            // 检测表格开始（含 | 的行）
            if (line.contains("|")) {
                List<String> tableLines = new ArrayList<>();
                while (i < lines.length && lines[i].contains("|")
                        && !lines[i].strip().startsWith("```")) {
                    tableLines.add(lines[i]);
                    i++;
                }
                if (!tableLines.isEmpty()) {
                    result.addAll(formatTable(tableLines));
                }
                continue;
            }

            result.add(line);
            i++;
        }
        return String.join("\n", result);
    }

    /**
     * 格式化单个 Markdown 表格
     */
    private static List<String> formatTable(List<String> lines) {
        if (lines.isEmpty()) return lines;

        // 检测第二行是否为分隔行（只含 -, :, |, 空格）
        boolean hasSeparator = lines.size() >= 2
                && lines.get(1).strip().matches("[\\s\\-:|]+");

        // 解析单元格（跳过分隔行，后面会重建）
        List<List<String>> rows = new ArrayList<>();
        for (int idx = 0; idx < lines.size(); idx++) {
            if (hasSeparator && idx == 1) continue;
            String[] cells = lines.get(idx).split("\\|", -1);
            List<String> trimmed = new ArrayList<>();
            for (String cell : cells) {
                trimmed.add(cell.strip());
            }
            // 去掉首尾空元素（由前导/尾随 | 产生）
            if (!trimmed.isEmpty() && trimmed.getFirst().isEmpty()) trimmed.removeFirst();
            if (!trimmed.isEmpty() && trimmed.getLast().isEmpty()) trimmed.removeLast();
            if (!trimmed.isEmpty()) rows.add(trimmed);
        }

        if (rows.isEmpty()) return lines;

        // 计算每列最大宽度
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[colCount];
        for (List<String> row : rows) {
            for (int j = 0; j < colCount; j++) {
                String cell = j < row.size() ? row.get(j) : "";
                widths[j] = Math.max(widths[j], cell.length());
            }
        }

        // 构建格式化结果
        List<String> formatted = new ArrayList<>();
        for (int idx = 0; idx < rows.size(); idx++) {
            List<String> row = rows.get(idx);
            StringBuilder sb = new StringBuilder("| ");
            for (int j = 0; j < colCount; j++) {
                String cell = j < row.size() ? row.get(j) : "";
                sb.append(padRight(cell, widths[j]));
                if (j < colCount - 1) sb.append(" | ");
            }
            sb.append(" |");
            formatted.add(sb.toString());

            // 头部行后插入分隔行
            if (idx == 0) {
                StringBuilder sep = new StringBuilder("| ");
                for (int j = 0; j < colCount; j++) {
                    sep.append("-".repeat(Math.max(3, widths[j])));
                    if (j < colCount - 1) sep.append(" | ");
                }
                sep.append(" |");
                formatted.add(sep.toString());
            }
        }
        return formatted;
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    // ==================== 帧发送基础设施 ====================

    /**
     * 发送 WebSocket 帧（fire and forget）
     */
    private void sendFrame(Map<String, Object> frame) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            log.warn("[wecom] WebSocket not connected, cannot send frame");
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(frame);
            ws.sendText(json, true);
        } catch (Exception e) {
            log.error("[wecom] Failed to send frame: {}", e.getMessage(), e);
        }
    }

    /**
     * 串行队列发送帧，等待 ACK（带超时）
     * <p>
     * 同一 reqId 的消息按顺序发送，每条等待 ACK 后再发下一条。
     */
    private void sendFrameWithAck(String reqId, Map<String, Object> frame) {
        CompletableFuture<Map<String, Object>> ackFuture = new CompletableFuture<>();

        // 注册 ACK 等待
        pendingAcks.put(reqId, ackFuture);

        // 发送帧
        sendFrame(frame);

        // 等待 ACK（超时 5 秒，不阻塞当前线程 — fire and forget）
        ackFuture.orTimeout(REPLY_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> {
                    pendingAcks.remove(reqId);
                    if (ex != null) {
                        log.debug("[wecom] Reply ACK timeout or error for reqId={}: {}", reqId, ex.getMessage());
                    }
                });
    }

    // ==================== 媒体文件下载与 AES 解密 ====================

    /**
     * 下载并解密企业微信媒体文件
     * <p>
     * AES-256-CBC 解密：base64 decode aesKey → IV = 前 16 字节 → PKCS#7 去填充
     *
     * @param url          文件下载 URL
     * @param aesKey       Base64 编码的 AES-256 密钥
     * @param msgId        消息 ID（用于生成文件名）
     * @param fileNameHint 文件名提示
     * @return 本地文件路径，失败返回 null
     */
    private String downloadAndDecryptMedia(String url, String aesKey, String msgId, String fileNameHint) {
        try {
            String mediaDir = getConfigString("media_dir", "data/media");
            Path mediaDirPath = Path.of(mediaDir);
            Files.createDirectories(mediaDirPath);

            // 1. HTTP GET 下载文件
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] encryptedData = response.body().readAllBytes();

            byte[] fileData;
            // 2. AES 解密（如果提供了 aesKey）
            if (aesKey != null && !aesKey.isBlank()) {
                fileData = decryptAes256Cbc(encryptedData, aesKey);
            } else {
                fileData = encryptedData;
            }

            // 3. 保存到本地
            String urlHash = md5Hex(url).substring(0, 8);
            String safeName = fileNameHint.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safeName.isBlank()) safeName = "media";
            Path filePath = mediaDirPath.resolve("wecom_" + urlHash + "_" + safeName);
            Files.write(filePath, fileData);

            log.info("[wecom] Media downloaded: {} ({} bytes)", filePath, fileData.length);
            return filePath.toAbsolutePath().toString();

        } catch (Exception e) {
            log.error("[wecom] Failed to download media: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * AES-256-CBC 解密（对齐 wecom-aibot-python-sdk crypto_utils.py）
     * <p>
     * 1. Base64 decode aesKey（自动补齐 padding）
     * 2. IV = decoded key 前 16 字节
     * 3. AES-256-CBC 解密
     * 4. PKCS#7 去填充
     */
    private byte[] decryptAes256Cbc(byte[] encryptedData, String aesKeyBase64) throws Exception {
        // 补齐 Base64 padding
        int padCount = (4 - aesKeyBase64.length() % 4) % 4;
        String padded = aesKeyBase64 + "=".repeat(padCount);
        byte[] keyBytes = Base64.getDecoder().decode(padded);

        // IV = 前 16 字节
        byte[] iv = Arrays.copyOf(keyBytes, 16);

        // 确保数据是 16 字节的倍数
        int blockSize = 16;
        int remainder = encryptedData.length % blockSize;
        if (remainder != 0) {
            encryptedData = Arrays.copyOf(encryptedData, encryptedData.length + (blockSize - remainder));
        }

        // AES-256-CBC 解密（NoPadding — 手动去 PKCS#7）
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decrypted = cipher.doFinal(encryptedData);

        // PKCS#7 去填充
        int padLen = decrypted[decrypted.length - 1] & 0xFF;
        if (padLen < 1 || padLen > 32 || padLen > decrypted.length) {
            throw new IllegalArgumentException("Invalid PKCS#7 padding value: " + padLen);
        }
        for (int i = decrypted.length - padLen; i < decrypted.length; i++) {
            if ((decrypted[i] & 0xFF) != padLen) {
                throw new IllegalArgumentException("Invalid PKCS#7 padding: bytes mismatch");
            }
        }
        return Arrays.copyOf(decrypted, decrypted.length - padLen);
    }

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    @Override
    public void proactiveSend(String targetId, String content) {
        sendMessageToChat(targetId, content);
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    // ==================== 工具方法 ====================

    /**
     * 生成唯一请求 ID：{prefix}_{timestamp}_{counter}
     */
    private String generateReqId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + reqIdCounter.incrementAndGet();
    }

    /**
     * MD5 哈希（hex 字符串）
     */
    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            return bytesToHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * MD5 哈希（字节数组输入）
     */
    private String md5Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input);
            return bytesToHex(hash);
        } catch (Exception e) {
            return "0";
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== 回复队列内部类 ====================

    private record ReplyTask(Map<String, Object> frame, CompletableFuture<Map<String, Object>> future) {}
}
