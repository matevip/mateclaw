package vip.mate.acp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.acp.client.AcpStdioClient;
import vip.mate.acp.model.AcpEndpointEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-090 Phase 7 — connection tester for ACP endpoints.
 *
 * <p>Runs the {@code initialize} + {@code session/new} handshake, with
 * a generous-but-bounded timeout, and persists the outcome on the row.
 * The wired CLI doesn't have to be installed for the user to add a row;
 * they can install it later and re-run the test.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpConnectionTester {

    /** Hard cap so a hung CLI doesn't block the request thread forever. */
    private static final long INITIALIZE_TIMEOUT_MS = 15_000L;
    private static final long SESSION_NEW_TIMEOUT_MS = 10_000L;

    private final ObjectMapper objectMapper;
    private final AcpEndpointService endpointService;
    private final AcpRuntimeSupport runtimeSupport;

    /**
     * Spawn the configured agent, exchange initialize + session/new,
     * tear it down, and return a structured result. The endpoint row
     * is updated with {@code last_status / last_tested_at / last_error}.
     */
    public Map<String, Object> testEndpoint(AcpEndpointEntity endpoint) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", endpoint.getName());
        result.put("command", endpoint.getCommand());

        List<String> args = endpointService.parseArgs(endpoint);
        Map<String, String> env = endpointService.parseEnv(endpoint);
        result.put("args", args);
        // Same as AcpDelegationService — Zed's ACP server requires a
        // non-blank cwd at session/new, so the connection test must
        // also default it. The "Test" button used to fail at session/new
        // with -32602 even when the CLI itself was healthy.
        String resolvedCwd = runtimeSupport.resolveCwd(endpoint, null);

        AcpStdioClient client;
        try {
            client = AcpStdioClient.spawn(objectMapper, endpoint.getCommand(),
                    args, env, resolvedCwd);
        } catch (Exception e) {
            return persistAndReturn(endpoint, result, "ERROR",
                    "Spawn failed: " + e.getMessage(), started);
        }

        try (AcpStdioClient autoClose = client) {
            JsonNode initResp;
            try {
                initResp = autoClose.initialize(INITIALIZE_TIMEOUT_MS);
            } catch (Exception e) {
                return persistAndReturn(endpoint, result, "ERROR",
                        "Initialize failed: " + e.getMessage(), started);
            }
            if (initResp == null) {
                return persistAndReturn(endpoint, result, "ERROR",
                        "Initialize returned no result", started);
            }
            int agentProtocolVersion = initResp.path("protocolVersion").asInt(-1);
            result.put("protocolVersion", agentProtocolVersion);
            if (agentProtocolVersion != AcpStdioClient.PROTOCOL_VERSION) {
                String msg = "Protocol mismatch: agent=" + agentProtocolVersion
                        + ", client=" + AcpStdioClient.PROTOCOL_VERSION;
                return persistAndReturn(endpoint, result, "ERROR", msg, started);
            }
            // Capture agent capabilities for diagnostics — the UI can
            // surface this as "supports: file_system, terminal, …".
            JsonNode agentCaps = initResp.path("agentCapabilities");
            if (!agentCaps.isMissingNode() && !agentCaps.isNull()) {
                result.put("agentCapabilities", agentCaps);
            }

            // session/new validates that the agent really stands up a
            // working session, not just initialize handshake.
            try {
                JsonNode sessionResp = autoClose.newSession(resolvedCwd, SESSION_NEW_TIMEOUT_MS);
                if (sessionResp != null && sessionResp.has("sessionId")) {
                    result.put("sessionId", sessionResp.path("sessionId").asText(""));
                }
            } catch (Exception e) {
                // session/new may fail for legitimate reasons (e.g. agent
                // requires auth flow first). Still report OK on initialize
                // but flag in the message — translated when it smells
                // like an auth error so the test page UI shows actionable
                // text instead of raw JSON-RPC.
                String authHint = runtimeSupport.translateAuthError(endpoint, e.getMessage());
                result.put("sessionWarning", authHint != null ? authHint : e.getMessage());
            }
        } catch (Exception e) {
            return persistAndReturn(endpoint, result, "ERROR",
                    "Connection test crashed: " + e.getMessage(), started);
        }

        long elapsed = System.currentTimeMillis() - started;
        result.put("elapsedMs", elapsed);
        return persistAndReturn(endpoint, result, "OK", null, started);
    }

    private Map<String, Object> persistAndReturn(AcpEndpointEntity endpoint,
                                                  Map<String, Object> result,
                                                  String status,
                                                  String error,
                                                  long started) {
        endpointService.recordTestResult(endpoint.getId(), status, error);
        result.put("status", status);
        if (error != null) result.put("error", error);
        result.putIfAbsent("elapsedMs", System.currentTimeMillis() - started);
        return result;
    }
}
