package vip.mate.agent.runtime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only Backstage surface: the global view of every in-flight agent
 * turn plus the controls to friendly-stop, force-recycle, or sweep stuck
 * runs. Distinct from {@code /api/v1/subagents/...} which is per-conversation
 * owner-scoped — this controller is intentionally cross-tenant for the
 * operator role.
 */
@Slf4j
@Tag(name = "Agent Runtime (Backstage)")
@RestController
@RequestMapping("/api/v1/admin/agent-runtime")
@RequiredArgsConstructor
public class AgentRuntimeController {

    private final AgentRuntimeAggregator aggregator;
    private final ChatStreamTracker streamTracker;
    private final SubagentRegistry subagentRegistry;
    private final AuditEventService auditEventService;

    @Operation(summary = "Snapshot of every in-flight agent turn")
    @GetMapping("/snapshot")
    public R<AgentRuntimeAggregator.RuntimeSnapshot> snapshot(Authentication auth) {
        requireAdmin(auth);
        return R.ok(aggregator.snapshot());
    }

    @Operation(summary = "Friendly stop — request the run to wind down at its next checkpoint")
    @PostMapping("/runs/{conversationId}/stop")
    public R<Map<String, Object>> stopFriendly(@PathVariable String conversationId,
                                               Authentication auth) {
        requireAdmin(auth);
        boolean ok = streamTracker.requestStop(conversationId);
        recordAudit(auth, "agent-runtime.stop", conversationId, Map.of("result", ok));
        return R.ok(Map.of("stopped", ok));
    }

    @Operation(summary = "Force recycle — dispose flux + drop RunState; use after friendly stop ignored")
    @PostMapping("/runs/{conversationId}/recycle")
    public R<Map<String, Object>> recycle(@PathVariable String conversationId,
                                          Authentication auth) {
        requireAdmin(auth);
        boolean ok = streamTracker.forceRecycle(conversationId);
        recordAudit(auth, "agent-runtime.recycle", conversationId, Map.of("result", ok));
        return R.ok(Map.of("recycled", ok));
    }

    @Operation(summary = "Interrupt one sub-agent (admin override of ownership check)")
    @PostMapping("/subagents/{subagentId}/interrupt")
    public R<Map<String, Object>> interruptSubagent(@PathVariable String subagentId,
                                                    Authentication auth) {
        requireAdmin(auth);
        boolean ok = subagentRegistry.interrupt(subagentId);
        recordAudit(auth, "agent-runtime.subagent.interrupt", subagentId, Map.of("result", ok));
        return R.ok(Map.of("interrupted", ok));
    }

    /**
     * Bulk recycle every run that the aggregator currently flags as stuck.
     * Returns the conversationIds that were touched so the caller can render
     * a confirmation toast without re-fetching.
     */
    @Operation(summary = "Recycle every run currently flagged as stuck")
    @PostMapping("/sweep")
    public R<Map<String, Object>> sweep(Authentication auth) {
        requireAdmin(auth);
        AgentRuntimeAggregator.RuntimeSnapshot snap = aggregator.snapshot();
        List<String> ids = snap.runs().stream()
                .filter(r -> r.stuckReason() != null)
                .map(AgentRuntimeAggregator.RunCard::conversationId)
                .toList();
        int recycled = 0;
        for (String cid : ids) {
            if (streamTracker.forceRecycle(cid)) recycled++;
        }
        recordAudit(auth, "agent-runtime.sweep", "all",
                Map.of("targets", ids, "recycled", recycled));
        return R.ok(Map.of("recycled", recycled, "ids", ids));
    }

    private void requireAdmin(Authentication auth) {
        if (auth == null) {
            throw new MateClawException(401, "authentication required");
        }
        boolean isAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        if (!isAdmin) {
            throw new MateClawException(403, "admin role required");
        }
    }

    private void recordAudit(Authentication auth, String action,
                             String resourceId, Map<String, Object> detail) {
        try {
            String username = auth != null ? auth.getName() : "anonymous";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("by", username);
            payload.putAll(detail);
            auditEventService.record(action, "agent-runtime", resourceId, resourceId,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("audit serialization failed for {}: {}", action, e.getMessage());
        }
    }
}
