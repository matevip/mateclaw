package vip.mate.stt.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.stt.SttProvider;
import vip.mate.stt.SttRequest;
import vip.mate.stt.SttResult;
import vip.mate.stt.WavPcmExtractor;
import vip.mate.system.model.SystemSettingsDTO;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DashScope STT Provider — Paraformer Realtime via WebSocket.
 *
 * <p>DashScope's only sync-callable STT path is the realtime WebSocket API
 * — there is no <code>/audio/transcriptions</code> endpoint on either the
 * native or OpenAI-compatible HTTP surface (verified empirically, returns
 * 404). The earlier sync-HTTP version of this provider was speculative and
 * has been replaced by this one.
 *
 * <h2>Wire protocol</h2>
 * Documented at <i>Aliyun DashScope Realtime ASR</i>. Message exchange:
 * <ol>
 *   <li>Open WS to {@value #WS_URL} with {@code Authorization: bearer
 *       <api-key>} header.</li>
 *   <li>Client sends a {@code run-task} text frame with task_id +
 *       paraformer-realtime-v2 model + format/sample-rate parameters.</li>
 *   <li>Server replies with {@code task-started} text frame.</li>
 *   <li>Client streams raw 16-bit PCM bytes as binary frames (chunked at
 *       ~100ms each = {@value #CHUNK_BYTES} bytes for 16 kHz mono).</li>
 *   <li>Server emits {@code result-generated} events as transcripts come
 *       in. Each event carries a sentence keyed by {@code begin_time};
 *       later events with the same {@code begin_time} update the same
 *       sentence (interim → final).</li>
 *   <li>Client sends {@code finish-task} text frame; server replies with
 *       {@code task-finished}; both sides close.</li>
 * </ol>
 *
 * <p>The {@link SttProvider} interface is sync — we bridge the async WS
 * conversation to a blocking call via {@link CountDownLatch} (run-task ack
 * + task-finished ack) plus an overall hard timeout. The whole transcribe
 * call returns either a full transcript or a domain-typed
 * {@link SttResult#failure} after at most {@value #OVERALL_TIMEOUT_MS}ms.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeSttProvider implements SttProvider {

    /** DashScope WS endpoint for realtime inference (audio/text/multimodal). */
    static final URI WS_URL = URI.create("wss://dashscope.aliyuncs.com/api-ws/v1/inference/");

    /** Default model — paraformer-realtime-v2 is the canonical 2024+ realtime ASR. */
    static final String DEFAULT_MODEL = "paraformer-realtime-v2";

    /** Default sample rate in Hz. Must match the actual WAV — the helper reads it. */
    static final int DEFAULT_SAMPLE_RATE_HZ = 16_000;

    /** ~100ms of 16 kHz / 16-bit / mono PCM. DashScope recommends 100-300ms chunks. */
    static final int CHUNK_BYTES = 3200;

    /**
     * How long to sleep between chunks. Paraformer-Realtime expects audio to
     * arrive at roughly the natural recording rate; if we dump the whole clip
     * in tens of milliseconds the server discards the stream and replies with
     * task-finished + zero result-generated events. The official Python SDK
     * does the same with {@code time.sleep(0.1)} between chunks. Matches
     * {@link #CHUNK_BYTES} (100ms of audio → 100ms wall sleep).
     */
    static final long CHUNK_PACING_MS = 100L;

    /** How long to wait for the WS handshake + task-started ack before giving up. */
    static final long TASK_STARTED_TIMEOUT_MS = 10_000L;

    /** Overall budget for a single transcribe — beyond this we abort the WS. */
    static final long OVERALL_TIMEOUT_MS = 60_000L;

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;
    /** Shared HttpClient — JDK's WebSocket builder doesn't reuse the underlying
     *  connection pool when you allocate a fresh client per call, so making
     *  this a field saves a connection-pool spin-up on every transcribe. */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override public String id() { return "dashscope"; }
    @Override public String label() { return "DashScope (Paraformer Realtime)"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 150; }

    /**
     * Per-language priority. Paraformer is the strongest mainstream Chinese
     * STT, so push it ahead of Whisper on zh — see {@link SttProvider} javadoc
     * for the routing rationale.
     */
    @Override
    public int autoDetectOrder(String language) {
        if (language == null) return autoDetectOrder();
        String lang = language.toLowerCase();
        if (lang.startsWith("zh")) return 60;
        return autoDetectOrder();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured("dashscope");
        } catch (Exception e) {
            log.warn("[DashScope STT] availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = modelProviderService.getProviderConfig("dashscope").getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return SttResult.failure("DashScope API Key 未配置");
            }
            byte[] audio = request.getAudioData();
            if (audio == null || audio.length < WavPcmExtractor.CANONICAL_HEADER_BYTES) {
                return SttResult.failure("音频为空或过短");
            }
            byte[] pcm = WavPcmExtractor.extract(audio);
            int sampleRate = WavPcmExtractor.sampleRate(audio);
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;
            String taskId = UUID.randomUUID().toString().replace("-", "");

            // Peak/RMS check — the silence path is a failure mode worth its
            // own log line so users can tell "mic captured nothing" from
            // "DashScope rejected real audio". Successful calls log peak/rms
            // at DEBUG only; a healthy call shouldn't produce a per-request
            // INFO log every time the user holds the talk button.
            int[] peakRms = computePcmPeakRms(pcm);
            if (peakRms[0] == 0) {
                log.warn("[DashScope STT] PCM is silent (peak=0, bytes={}) — check mic permission / frontend recording",
                        pcm.length);
                return SttResult.failure(
                        "音频为静音（PCM peak=0）— 检查麦克风权限或前端录制实现");
            }
            log.debug("[DashScope STT] PCM stats — bytes={} samples={} peak={} rms={} sampleRate={}",
                    pcm.length, pcm.length / 2, peakRms[0], peakRms[1], sampleRate);

            DashScopeSession session = new DashScopeSession(taskId, objectMapper);
            WebSocket ws;
            try {
                ws = httpClient.newWebSocketBuilder()
                        .header("Authorization", "bearer " + apiKey)
                        .connectTimeout(Duration.ofMillis(TASK_STARTED_TIMEOUT_MS))
                        .buildAsync(WS_URL, session)
                        .get(TASK_STARTED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return SttResult.failure("DashScope WS 握手超时");
            }

            try {
                // 1. run-task. Envelope dumped at DEBUG only — the JSON is
                // identical across calls modulo task_id + language hint, so
                // logging it on every transcribe just clutters logs.
                String runTask = buildRunTask(taskId, model, sampleRate, request.getLanguage());
                log.debug("[DashScope STT] run-task envelope: {}", runTask);
                ws.sendText(runTask, true).get(TASK_STARTED_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                // 2. wait for task-started ack
                if (!session.awaitTaskStarted(TASK_STARTED_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return SttResult.failure("DashScope task-started 超时");
                }
                if (session.failed()) {
                    return SttResult.failure("DashScope: " + session.errorMessage());
                }

                // 3. stream PCM chunks at real-time pace. Paraformer-Realtime
                // is built for live mic input and silently drops audio when it
                // arrives faster than wall-clock — symptom is 0 chars
                // transcribed even though the protocol completes successfully
                // (no task-failed). Sleep 100ms between 100ms chunks so total
                // send time ≈ audio duration, matching what DashScope's own
                // SDK examples do (time.sleep(0.1) per chunk).
                int chunksSent = 0;
                long sendStart = System.currentTimeMillis();
                for (int offset = 0; offset < pcm.length; offset += CHUNK_BYTES) {
                    int len = Math.min(CHUNK_BYTES, pcm.length - offset);
                    ByteBuffer chunk = ByteBuffer.wrap(pcm, offset, len);
                    ws.sendBinary(chunk, true).get(TASK_STARTED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    chunksSent++;
                    Thread.sleep(CHUNK_PACING_MS);
                    // Cheap fail-fast: if the server already said we're done /
                    // failed mid-stream, stop sending so we don't waste seconds
                    // sleeping on a dead connection.
                    if (session.failed() || session.taskFinishedRaised()) break;
                }
                long sendDuration = System.currentTimeMillis() - sendStart;
                log.debug("[DashScope STT] streamed {} chunks ({} bytes) in {} ms",
                        chunksSent, pcm.length, sendDuration);

                // 4. finish-task
                ws.sendText(buildFinishTask(taskId), true).get(TASK_STARTED_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                // 5. wait for task-finished
                if (!session.awaitTaskFinished(OVERALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return SttResult.failure("DashScope task-finished 超时");
                }
                if (session.failed()) {
                    return SttResult.failure("DashScope: " + session.errorMessage());
                }

                String text = session.aggregatedText();
                log.info("[DashScope STT] Transcribed {} chars from {} result-events "
                                + "(model={}, sampleRate={}, pcmBytes={})",
                        text.length(), session.resultEventCount(), model, sampleRate, pcm.length);
                if (text.isEmpty() && session.resultEventCount() == 0) {
                    // Distinct failure mode: protocol completed cleanly but
                    // server never sent a single result-generated event.
                    // Almost always means the audio was discarded for
                    // pacing/format reasons. Surface as a typed failure so
                    // the fallback chain (Whisper) can still try.
                    return SttResult.failure(
                            "DashScope 收到 0 个识别事件——可能是音频格式或节奏问题");
                }
                return SttResult.success(text);
            } finally {
                // Best-effort close. abort() is fire-and-forget; we don't need to wait.
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                } catch (Exception ignored) {
                    ws.abort();
                }
            }
        } catch (TimeoutException e) {
            log.warn("[DashScope STT] timeout: {}", e.getMessage());
            return SttResult.failure("DashScope STT 超时: " + e.getMessage());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[DashScope STT] WS error: {}", cause.getMessage(), cause);
            return SttResult.failure("DashScope STT WS 错误: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SttResult.failure("DashScope STT 被中断");
        } catch (Exception e) {
            log.error("[DashScope STT] Error: {}", e.getMessage(), e);
            return SttResult.failure("DashScope STT 异常: " + e.getMessage());
        }
    }

    /* ====================================================================== */
    /* Wire-format helpers (package-private for unit testing).                 */
    /* ====================================================================== */

    String buildRunTask(String taskId, String model, int sampleRate, String language) throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", "pcm");
        parameters.put("sample_rate", sampleRate);
        // Language hint when supplied — paraformer-realtime-v2 supports
        // "zh", "en", "ja", "ko" via language_hints. Skip when null/blank
        // to let the model auto-detect.
        if (language != null && !language.isBlank()) {
            // Strip locale suffix (zh-CN → zh).
            String hint = language.toLowerCase();
            int dash = hint.indexOf('-');
            if (dash > 0) hint = hint.substring(0, dash);
            parameters.put("language_hints", new String[]{hint});
        }

        Map<String, Object> payload = Map.of(
                "task_group", "audio",
                "task", "asr",
                "function", "recognition",
                "model", model,
                "parameters", parameters,
                "input", Map.of());
        Map<String, Object> message = Map.of(
                "header", Map.of(
                        "action", "run-task",
                        "task_id", taskId,
                        "streaming", "duplex"),
                "payload", payload);
        return objectMapper.writeValueAsString(message);
    }

    /**
     * Compute peak (max absolute value) and RMS for 16-bit signed
     * little-endian PCM bytes. Returns {peak, rms} as ints for log-friendly
     * formatting. Both metrics are in raw int16 units (-32768..32767).
     *
     * <p>Reference values for 16-bit PCM at typical recording levels:
     * <ul>
     *   <li>Silence / muted mic: peak ≤ 5, rms ≤ 2</li>
     *   <li>Quiet speech: peak ≈ 1000-5000, rms ≈ 200-1000</li>
     *   <li>Normal speech: peak ≈ 5000-20000, rms ≈ 1000-5000</li>
     *   <li>Loud / close-mic: peak ≈ 20000-32000, rms ≈ 5000-15000</li>
     * </ul>
     */
    static int[] computePcmPeakRms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) {
            return new int[]{0, 0};
        }
        int peak = 0;
        long sumSq = 0;
        int sampleCount = pcm.length / 2;
        for (int i = 0; i < sampleCount; i++) {
            // Little-endian 16-bit signed: low byte first.
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];                  // signed
            int sample = (hi << 8) | lo;
            int abs = Math.abs(sample);
            if (abs > peak) peak = abs;
            sumSq += (long) sample * sample;
        }
        int rms = (int) Math.sqrt((double) sumSq / sampleCount);
        return new int[]{peak, rms};
    }

    String buildFinishTask(String taskId) throws Exception {
        Map<String, Object> message = Map.of(
                "header", Map.of(
                        "action", "finish-task",
                        "task_id", taskId,
                        "streaming", "duplex"),
                "payload", Map.of("input", Map.of()));
        return objectMapper.writeValueAsString(message);
    }

    /* ====================================================================== */
    /* WebSocket.Listener: collects events and signals task-started/finished.  */
    /* ====================================================================== */

    /**
     * State machine for one DashScope ASR conversation. Package-private so
     * unit tests can drive it with synthetic JSON without hitting the network.
     */
    static class DashScopeSession implements WebSocket.Listener {
        private final String taskId;
        private final ObjectMapper mapper;
        private final CountDownLatch taskStarted = new CountDownLatch(1);
        private final CountDownLatch taskFinished = new CountDownLatch(1);

        /**
         * Sentence buffer keyed by begin_time. DashScope emits multiple
         * {@code result-generated} events for the same sentence as it gets
         * refined (interim → final); each new event for a given begin_time
         * supersedes the previous text. LinkedHashMap preserves arrival
         * order, which roughly matches speech order, for the final concat.
         */
        private final Map<Long, String> sentencesByBeginTime = new LinkedHashMap<>();

        /** Buffer for fragmented text frames (WS allows partial messages). */
        private final StringBuilder textFrameBuf = new StringBuilder();

        private final AtomicReference<String> errorMessage = new AtomicReference<>();

        /** Counts result-generated events — distinguishes "server got our audio
         *  but recognised nothing" (>0 events, all empty text) from "server
         *  saw zero audio frames" (0 events). Helps diagnose pacing /
         *  format issues. */
        private int resultEventCount;

        DashScopeSession(String taskId, ObjectMapper mapper) {
            this.taskId = taskId;
            this.mapper = mapper;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textFrameBuf.append(data);
            if (last) {
                handleMessage(textFrameBuf.toString());
                textFrameBuf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            errorMessage.compareAndSet(null, "WS error: " + error.getMessage());
            taskStarted.countDown();
            taskFinished.countDown();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // If the server closes before task-finished, unblock waiters.
            if (taskFinished.getCount() > 0) {
                errorMessage.compareAndSet(null,
                        "WS closed before task-finished (status=" + statusCode + ", reason=" + reason + ")");
            }
            taskStarted.countDown();
            taskFinished.countDown();
            return null;
        }

        /** Package-private hook so unit tests can drive {@link DashScopeSession} without a real WebSocket. */
        void handleMessage(String json) {
            // Always trace the raw frame at DEBUG — this is invaluable when
            // the protocol completes "successfully" but produces no
            // transcripts. Without seeing every frame it's impossible to
            // tell whether DashScope sent us a status-update / warning we
            // ignored, or just stayed silent between task-started and
            // task-finished.
            log.debug("[DashScope STT] frame: {}", json);
            try {
                JsonNode node = mapper.readTree(json);
                String event = node.path("header").path("event").asText();
                switch (event) {
                    case "task-started" -> taskStarted.countDown();
                    case "result-generated" -> {
                        resultEventCount++;
                        JsonNode sentence = node.path("payload").path("output").path("sentence");
                        if (sentence.isObject()) {
                            long beginTime = sentence.path("begin_time").asLong(0L);
                            String text = sentence.path("text").asText("");
                            // Always overwrite — later events for the same begin_time
                            // carry the more-final transcript.
                            sentencesByBeginTime.put(beginTime, text);
                        }
                    }
                    case "task-finished" -> taskFinished.countDown();
                    case "task-failed" -> {
                        String msg = node.path("header").path("error_message").asText("unknown");
                        String code = node.path("header").path("error_code").asText("");
                        errorMessage.compareAndSet(null,
                                code.isEmpty() ? msg : (code + " — " + msg));
                        // Wake both latches so the caller can return the typed
                        // failure instead of timing out for the full budget.
                        taskStarted.countDown();
                        taskFinished.countDown();
                    }
                    // Anything else (status updates, model warnings, beta
                    // events) gets surfaced at INFO so it shows up without
                    // turning DEBUG on. If DashScope rolls out a new event
                    // type we should know about, this catches it.
                    default -> log.info("[DashScope STT] unhandled event '{}' frame={}", event, json);
                }
            } catch (Exception e) {
                log.warn("[DashScope STT] failed to parse WS message: {}", e.getMessage());
            }
        }

        boolean awaitTaskStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return taskStarted.await(timeout, unit);
        }

        boolean awaitTaskFinished(long timeout, TimeUnit unit) throws InterruptedException {
            return taskFinished.await(timeout, unit);
        }

        boolean failed() {
            return errorMessage.get() != null;
        }

        String errorMessage() {
            return errorMessage.get();
        }

        /** True once task-finished has been observed — used by the sender
         *  loop to bail out early instead of pacing through dead-WS sleeps. */
        boolean taskFinishedRaised() {
            return taskFinished.getCount() == 0;
        }

        int resultEventCount() {
            return resultEventCount;
        }

        String aggregatedText() {
            // Concat in begin_time order. Different sentences typically don't
            // need separator characters because Chinese text streams are
            // already glued; for safety against missed punctuation we leave
            // a soft join ("") rather than space — Whisper-style space
            // joining produces odd-looking Chinese transcripts.
            StringBuilder sb = new StringBuilder();
            sentencesByBeginTime.values().forEach(sb::append);
            return sb.toString();
        }
    }
}
