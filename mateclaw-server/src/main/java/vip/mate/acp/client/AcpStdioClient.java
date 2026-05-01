package vip.mate.acp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * RFC-090 Phase 7 — minimal Java ACP (Agent Communication Protocol)
 * client over stdio.
 *
 * <p>Implements just enough of the JSON-RPC 2.0 framing to:
 * <ol>
 *   <li>Spawn the agent process ({@code command} + {@code args}).</li>
 *   <li>Send {@code initialize} and capture {@code agentCapabilities}
 *       /  {@code protocolVersion}.</li>
 *   <li>Optionally open a {@code session/new} handshake.</li>
 *   <li>Tear the process down cleanly.</li>
 * </ol>
 *
 * <p>This is intentionally a one-shot connection tester (RFC §10.2 Q3
 * recommended starting order: codex → claude → opencode → qwen). Full
 * bidirectional session prompting / streaming / permission requests is
 * a future increment — that needs a proper async bus and ties into the
 * agent graph layer.
 *
 * <p>Why not the official {@code acp} Python SDK: MateClaw runs on the
 * JVM. The protocol is JSON-RPC 2.0 line-delimited over stdio (per the
 * QwenPaw reference at {@code C:/codes/QwenPaw}); the surface we need
 * for "test connection" is small enough to implement directly.
 *
 * <p>Each {@link AcpStdioClient} instance owns one Process. Use
 * try-with-resources or call {@link #close()} explicitly.
 */
@Slf4j
public class AcpStdioClient implements AutoCloseable {

    /** ACP protocol version we advertise (matches QwenPaw v1 + Zed agents). */
    public static final int PROTOCOL_VERSION = 1;

    private final ObjectMapper mapper;
    private final Process process;
    private final Writer stdin;
    private final BufferedReader stdout;
    private final Thread readerThread;
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * RFC-090 Phase 7b — invoked when the agent sends a JSON-RPC
     * notification (no id). Notification objects passed in have shape
     * {@code {jsonrpc, method, params}}; the most common is
     * {@code session/update} carrying agent message chunks.
     *
     * <p>Default no-op so existing test-only callers don't need to set
     * a handler. {@link AcpDelegationService} installs an accumulator
     * that scrapes {@code agent_message_chunk} text into a
     * {@code StringBuilder}.
     */
    private volatile Consumer<JsonNode> notificationHandler = msg -> { /* drop */ };

    /**
     * RFC-090 Phase 7b — invoked when the agent sends a JSON-RPC
     * request (has id). The handler returns the JSON-RPC
     * {@code result} object (or null to send back -32601 method-not-
     * implemented). Used for {@code session/request_permission};
     * trusted endpoints auto-allow, untrusted ones cancel.
     */
    private volatile Function<JsonNode, JsonNode> requestHandler = msg -> null;

    private AcpStdioClient(ObjectMapper mapper, Process process) {
        this.mapper = mapper;
        this.process = process;
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.readerThread = new Thread(this::readLoop, "acp-stdio-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /**
     * Spawn the configured agent process. Caller is responsible for
     * closing the returned client; failure to do so leaks a child
     * process.
     */
    public static AcpStdioClient spawn(ObjectMapper mapper,
                                        String command,
                                        List<String> args,
                                        Map<String, String> envOverrides,
                                        String cwd)
            throws IOException {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("ACP command is required");
        }
        java.util.List<String> cmdline = new java.util.ArrayList<>();
        cmdline.add(command);
        if (args != null) cmdline.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmdline);
        Map<String, String> env = pb.environment();
        if (envOverrides != null) env.putAll(envOverrides);
        if (cwd != null && !cwd.isBlank()) {
            pb.directory(new java.io.File(cwd));
        }
        // Keep stderr separate from stdout so we don't poison JSON-RPC
        // framing when the child agent writes a banner / log line.
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        // Drain stderr in the background — many CLIs print diagnostics
        // there (e.g. Zed agents print version on startup).
        Thread errDrain = new Thread(() -> drainStream(proc.getErrorStream()), "acp-stdio-stderr");
        errDrain.setDaemon(true);
        errDrain.start();
        return new AcpStdioClient(mapper, proc);
    }

    private static void drainStream(java.io.InputStream in) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (log.isDebugEnabled()) log.debug("[acp-stderr] {}", line);
            }
        } catch (IOException ignore) {
            // Process exited; nothing to do.
        }
    }

    /**
     * Send {@code initialize} and wait for the response. Returns the
     * response payload's {@code result} object, or throws on protocol
     * mismatch / timeout.
     */
    public JsonNode initialize(long timeoutMillis) throws IOException, InterruptedException {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        // ClientCapabilities — we don't yet implement any client-side
        // optional features. Send an empty object so strict agents
        // don't reject the request.
        params.set("clientCapabilities", mapper.createObjectNode());
        ObjectNode info = mapper.createObjectNode();
        info.put("name", "mateclaw-acp-client");
        info.put("version", "1.0.0");
        params.set("clientInfo", info);
        return sendRequest("initialize", params, timeoutMillis);
    }

    /**
     * Send {@code session/new} — establishes a session for prompting.
     * For the connection-test path we don't actually prompt, just
     * verify the server accepts the handshake.
     *
     * <p>The {@code cwd} parameter is always written into the request
     * body. Zed's ACP Zod schema (used by {@code @zed-industries/claude-
     * agent-acp} and the codex variant) marks {@code cwd} as a required
     * string and returns {@code -32602 Invalid params} when it's
     * missing. If the caller passes null/blank we substitute the JVM
     * working directory — a workspace-aware default lives in
     * {@code AcpRuntimeSupport#resolveCwd}, but this fallback ensures
     * the protocol never sees {@code undefined} regardless of caller.
     */
    public JsonNode newSession(String cwd, long timeoutMillis)
            throws IOException, InterruptedException {
        ObjectNode params = mapper.createObjectNode();
        String safeCwd = (cwd == null || cwd.isBlank())
                ? System.getProperty("user.dir", ".")
                : cwd;
        params.put("cwd", safeCwd);
        params.set("mcpServers", mapper.createArrayNode());
        return sendRequest("session/new", params, timeoutMillis);
    }

    /**
     * Lower-level request helper. Synchronously awaits the response
     * matching the request id. Server-pushed requests (e.g. permission
     * prompts) are dropped — the test-only connection path doesn't need
     * to handle them.
     */
    public JsonNode sendRequest(String method, JsonNode params, long timeoutMillis)
            throws IOException, InterruptedException {
        if (closed) throw new IOException("ACP client is closed");
        long id = nextRequestId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.set("params", params);

        synchronized (stdin) {
            stdin.write(mapper.writeValueAsString(envelope));
            stdin.write('\n');
            stdin.flush();
        }

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("ACP request failed: " + (cause != null ? cause.getMessage() : "unknown"));
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(id);
            throw new IOException("ACP request timed out after " + timeoutMillis + "ms");
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = stdout.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    JsonNode msg = mapper.readTree(line);
                    routeMessage(msg);
                } catch (Exception e) {
                    log.warn("ACP malformed line, skipping: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("ACP stdio reader closed: {}", e.getMessage());
            }
        } finally {
            // If the process exited mid-await, fail every pending future.
            for (Map.Entry<Long, CompletableFuture<JsonNode>> entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(
                        new IOException("ACP process exited before responding"));
            }
            pending.clear();
        }
    }

    private void routeMessage(JsonNode msg) {
        JsonNode idNode = msg.get("id");
        boolean hasId = idNode != null && !idNode.isNull();
        boolean hasMethod = msg.has("method");

        // (1) Response to one of *our* outbound requests.
        if (hasId && idNode.isNumber() && !hasMethod) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future != null) {
                JsonNode error = msg.get("error");
                if (error != null && !error.isNull()) {
                    future.completeExceptionally(
                            new IOException("ACP error: " + error.toString()));
                } else {
                    future.complete(msg.get("result"));
                }
                return;
            }
        }

        // (2) Server-initiated request — has both method and id.
        if (hasMethod && hasId) {
            JsonNode result = null;
            try {
                result = requestHandler.apply(msg);
            } catch (Exception e) {
                log.warn("ACP requestHandler threw on method '{}': {}",
                        msg.path("method").asText(""), e.getMessage());
            }
            sendReplyTo(idNode, result, msg.path("method").asText(""));
            return;
        }

        // (3) Notification — has method but no id.
        if (hasMethod) {
            try {
                notificationHandler.accept(msg);
            } catch (Exception e) {
                log.warn("ACP notificationHandler threw on method '{}': {}",
                        msg.path("method").asText(""), e.getMessage());
            }
        }
    }

    private void sendReplyTo(JsonNode idNode, JsonNode result, String method) {
        try {
            ObjectNode reply = mapper.createObjectNode();
            reply.put("jsonrpc", "2.0");
            reply.set("id", idNode);
            if (result != null) {
                reply.set("result", result);
            } else {
                ObjectNode error = mapper.createObjectNode();
                error.put("code", -32601);
                error.put("message", "Method not implemented: " + method);
                reply.set("error", error);
            }
            synchronized (stdin) {
                stdin.write(mapper.writeValueAsString(reply));
                stdin.write('\n');
                stdin.flush();
            }
        } catch (IOException e) {
            log.debug("ACP failed to reply to server-initiated request '{}': {}", method, e.getMessage());
        }
    }

    /**
     * Replace the notification handler. Pass {@code null} to fall back
     * to the no-op default.
     */
    public void setNotificationHandler(Consumer<JsonNode> handler) {
        this.notificationHandler = handler != null ? handler : msg -> {};
    }

    /**
     * Replace the server-request handler. Pass {@code null} to fall
     * back to the default which returns -32601 for every method.
     */
    public void setRequestHandler(Function<JsonNode, JsonNode> handler) {
        this.requestHandler = handler != null ? handler : msg -> null;
    }

    @Override
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignore) {
            /* best effort */
        }
        try {
            // Give the agent ~1s to exit gracefully after EOF on stdin.
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            stdout.close();
        } catch (IOException ignore) {
            /* best effort */
        }
    }

    /** Convenience for callers that just want a fresh empty env map. */
    public static Map<String, String> emptyEnv() {
        return new HashMap<>();
    }
}
