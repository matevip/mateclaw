package vip.mate.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory approval store (RFC-067).
 * <p>
 * INTERNAL — do not call mutating methods directly. Business code must go through
 * {@link ApprovalWorkflowService}, which owns the DB / message-metadata / memory
 * three-way state machine. This class only exposes:
 * <ul>
 *   <li>{@link #createPending} — used by tool guard to register a new approval;
 *       paired with {@link ApprovalWorkflowService#createPending} for DB persistence</li>
 *   <li>read-only queries: {@link #getPending}, {@link #findPendingByConversation},
 *       {@link #getPendingByConversation}</li>
 *   <li>package-private snapshot / mutate helpers consumed by {@link ApprovalWorkflowService}
 *       (recovery, GC, two-phase resolve)</li>
 * </ul>
 * Public mutating methods (resolve / resolveAndConsume / consumeApproved /
 * cancelStalePending / denyAllByConversation) were removed in PR-4 once all
 * callers migrated to the workflow service. Reintroducing them is a regression —
 * they bypass DB and message-metadata writes, which is the original ghost-approval
 * source.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class ApprovalService {

    private final ConcurrentHashMap<String, PendingApproval> pendingMap = new ConcurrentHashMap<>();

    /** GC constants. Package-visible so {@link ApprovalWorkflowService}'s GC loop
     *  (RFC-067 §4.4) can apply the same TTL / cap thresholds while owning the
     *  scheduler clock + DB+metadata sync. */
    static final Duration PENDING_TTL = Duration.ofMinutes(30);
    static final Duration RESOLVED_TTL = Duration.ofHours(1);
    static final int MAX_PENDING = 200;
    static final int MAX_RESOLVED = 500;

    // ==================== 创建 ====================

    /**
     * 创建待审批记录（基础版，向后兼容）
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason) {
        return createPending(conversationId, userId, toolName, toolArguments, reason,
                null, null, null);
    }

    /**
     * 创建待审批记录（增强版，含重放载荷）
     *
     * @param toolCallPayload  序列化的 tool call JSON
     * @param siblingToolCalls 序列化的 sibling tool calls JSON 数组
     * @param agentId          发起审批的 Agent ID
     * @return pendingId
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason,
                                String toolCallPayload, String siblingToolCalls, String agentId) {
        String pendingId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        PendingApproval pending = new PendingApproval(
                pendingId, conversationId, userId, toolName, toolArguments, reason);
        pending.setToolCallPayload(toolCallPayload);
        pending.setSiblingToolCalls(siblingToolCalls);
        pending.setAgentId(agentId);
        pendingMap.put(pendingId, pending);
        log.info("[Approval] Created pending: id={}, tool={}, agent={}, conversation={}",
                pendingId, toolName, agentId, conversationId);
        return pendingId;
    }

    /**
     * INTERNAL — drop a pending entry from the map without changing its status.
     * Used by {@link ApprovalWorkflowService} as the final memory-mutation step
     * after DB + metadata writes commit. Status is mutated separately by the
     * caller so consume / resolve flows can keep the {@code consumed} /
     * {@code resolved} terminal state visible on the snapshot they return.
     * <p>
     * Only {@code ApprovalWorkflowService} should call this.
     */
    void removeFromMap(String pendingId) {
        if (pendingId == null) return;
        pendingMap.remove(pendingId);
    }

    /**
     * INTERNAL — register a {@link PendingApproval} reconstructed from DB during JVM startup.
     * Bypasses id generation and pre-existing-entry checks; the snapshot's {@code pendingId}
     * must already match the DB row. Idempotent: if the same id already lives in the map
     * (concurrent recovery path), the second call is logged and dropped.
     * <p>
     * Only {@code ApprovalWorkflowService.recoverFromDb} should call this.
     */
    void registerRecovered(PendingApproval snapshot) {
        if (snapshot == null || snapshot.getPendingId() == null) {
            log.warn("[Approval] registerRecovered: ignoring null snapshot");
            return;
        }
        PendingApproval existing = pendingMap.putIfAbsent(snapshot.getPendingId(), snapshot);
        if (existing != null) {
            log.warn("[Approval] registerRecovered: pending id {} already in map, skipping",
                    snapshot.getPendingId());
            return;
        }
        log.info("[Approval] Recovered pending from DB: id={}, tool={}, conversation={}",
                snapshot.getPendingId(), snapshot.getToolName(), snapshot.getConversationId());
    }

    // ==================== 查询 ====================

    /**
     * 获取待审批记录
     */
    public Optional<PendingApproval> getPending(String pendingId) {
        return Optional.ofNullable(pendingMap.get(pendingId));
    }

    /**
     * 查找指定会话最早的 pending 审批（FIFO 语义）
     * 用于 ChannelMessageRouter 在处理新消息前检查是否有待审批
     */
    public PendingApproval findPendingByConversation(String conversationId) {
        return pendingMap.values().stream()
                .filter(p -> conversationId.equals(p.getConversationId()))
                .filter(p -> "pending".equals(p.getStatus()))
                .min(Comparator.comparing(PendingApproval::getCreatedAt))
                .orElse(null);
    }

    /**
     * 获取指定会话下所有 pending 状态的审批记录（供前端 hydration）
     */
    public List<Map<String, Object>> getPendingByConversation(String conversationId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PendingApproval pending : pendingMap.values()) {
            if (conversationId.equals(pending.getConversationId())
                    && "pending".equals(pending.getStatus())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("pendingId", pending.getPendingId());
                entry.put("toolName", pending.getToolName());
                entry.put("toolArguments", pending.getToolArguments() != null ? pending.getToolArguments() : "");
                entry.put("reason", pending.getReason() != null ? pending.getReason() : "");
                entry.put("status", pending.getStatus());
                entry.put("createdAt", pending.getCreatedAt().toString());
                // 增强字段（Phase 5: 结构化风险信息）
                if (pending.getFindingsJson() != null) {
                    entry.put("findingsJson", pending.getFindingsJson());
                }
                if (pending.getMaxSeverity() != null) {
                    entry.put("maxSeverity", pending.getMaxSeverity());
                }
                if (pending.getSummary() != null) {
                    entry.put("summary", pending.getSummary());
                }
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * INTERNAL — return the earliest {@code approved} pending matching the conversation +
     * tool, WITHOUT removing it. Workflow uses this to take a snapshot before the
     * two-phase DB / metadata write; the actual map removal happens via
     * {@link #removeFromMap(String)} after commit.
     */
    PendingApproval findApprovedForConsume(String conversationId, String toolName) {
        return pendingMap.values().stream()
                .filter(p -> conversationId.equals(p.getConversationId()))
                .filter(p -> "approved".equals(p.getStatus()))
                .filter(p -> toolName == null || toolName.equals(p.getToolName()))
                .min(Comparator.comparing(PendingApproval::getCreatedAt))
                .orElse(null);
    }

    /**
     * INTERNAL — return a list snapshot of every {@code pending} record in the
     * conversation, optionally excluding one id. Read-only; no map mutation.
     * Workflow iterates this list and runs the two-phase resolve on each.
     */
    List<PendingApproval> snapshotPendingByConversation(String conversationId,
                                                        String excludePendingId) {
        List<PendingApproval> out = new ArrayList<>();
        for (PendingApproval p : pendingMap.values()) {
            if (!conversationId.equals(p.getConversationId())) continue;
            if (!"pending".equals(p.getStatus())) continue;
            if (excludePendingId != null && excludePendingId.equals(p.getPendingId())) continue;
            out.add(p);
        }
        return out;
    }

    // ==================== GC snapshot helpers (used by ApprovalWorkflowService) ====================

    /**
     * INTERNAL — return a list snapshot of {@code pending} records whose age
     * exceeds {@link #PENDING_TTL}. Read-only; the workflow GC loop iterates this
     * list and runs the two-phase {@code markTimeout} on each.
     */
    List<PendingApproval> snapshotExpiredPending(Instant now) {
        List<PendingApproval> out = new ArrayList<>();
        for (PendingApproval p : pendingMap.values()) {
            if (!"pending".equals(p.getStatus())) continue;
            if (Duration.between(p.getCreatedAt(), now).compareTo(PENDING_TTL) > 0) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * INTERNAL — when total pending count is over {@code maxPending}, return the
     * oldest excess entries so the workflow GC loop can {@code markTimeout} each.
     * Read-only; sorts by createdAt ascending.
     */
    List<PendingApproval> snapshotExcessPending(int maxPending) {
        List<PendingApproval> pending = pendingMap.values().stream()
                .filter(p -> "pending".equals(p.getStatus()))
                .sorted(Comparator.comparing(PendingApproval::getCreatedAt))
                .toList();
        if (pending.size() <= maxPending) return List.of();
        return new ArrayList<>(pending.subList(0, pending.size() - maxPending));
    }

    /**
     * INTERNAL — drop already-resolved (non-{@code pending}) entries that exceed
     * either the resolved-TTL or the resolved-cap. Memory-only: these rows are
     * already terminal in DB, so no DB / metadata sync is required.
     *
     * @return number of map entries dropped
     */
    int dropResolvedExceedingLimits(Instant now) {
        int dropped = 0;
        // TTL-based drops first
        List<String> ttlExpired = new ArrayList<>();
        for (PendingApproval p : pendingMap.values()) {
            if ("pending".equals(p.getStatus())) continue;
            Instant resolvedAt = p.getResolvedAt() != null ? p.getResolvedAt() : p.getCreatedAt();
            if (Duration.between(resolvedAt, now).compareTo(RESOLVED_TTL) > 0) {
                ttlExpired.add(p.getPendingId());
            }
        }
        ttlExpired.forEach(pendingMap::remove);
        dropped += ttlExpired.size();

        // Cap-based drops second
        List<PendingApproval> resolved = pendingMap.values().stream()
                .filter(p -> !"pending".equals(p.getStatus()))
                .sorted(Comparator.comparing(PendingApproval::getCreatedAt))
                .toList();
        if (resolved.size() > MAX_RESOLVED) {
            int toEvict = resolved.size() - MAX_RESOLVED;
            for (int i = 0; i < toEvict; i++) {
                pendingMap.remove(resolved.get(i).getPendingId());
            }
            dropped += toEvict;
        }
        return dropped;
    }

    /**
     * INTERNAL — current pending-map size, used by GC summary logs.
     */
    int size() {
        return pendingMap.size();
    }
}
