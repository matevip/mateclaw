package vip.mate.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.workspace.conversation.ConversationService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审批工作流服务（write-through: 内存 + DB 双写）
 * <p>
 * 在现有 ApprovalService（内存层）之上，增加 DB 持久化。
 * 所有写操作先走 ApprovalService，再写 DB。
 * 启动时从 DB 恢复 PENDING 状态到内存。
 */
@Slf4j
@Service
@Order(55) // Schema 由 Flyway 管理，在 Flyway 迁移完成后执行
@RequiredArgsConstructor
public class ApprovalWorkflowService implements ApplicationRunner {

    private final ApprovalService approvalService;
    private final ToolApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;

    /**
     * GC scheduler — owns the 5-minute clock for the entire approval state machine
     * (RFC-067 §4.4). Lives on the workflow rather than {@link ApprovalService} so
     * timeout / overflow eviction goes through the same DB+metadata+memory two-phase
     * path as approve / deny — the in-memory map can no longer drift ahead of DB.
     */
    private ScheduledExecutorService gcScheduler;

    @Override
    public void run(ApplicationArguments args) {
        recoverFromDb();
    }

    @PostConstruct
    void initGc() {
        gcScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-gc");
            t.setDaemon(true);
            return t;
        });
        gcScheduler.scheduleAtFixedRate(this::garbageCollect, 5, 5, TimeUnit.MINUTES);
        log.info("[ApprovalWorkflow] GC scheduler started (interval=5min)");
    }

    @PreDestroy
    void shutdownGc() {
        if (gcScheduler != null) {
            gcScheduler.shutdownNow();
        }
    }

    /**
     * Reconstruct in-memory pending approvals from DB at startup, preserving the
     * original {@code pendingId} and {@code createdAt} so subsequent resolve / GC
     * paths stay consistent with the persisted row.
     * <p>
     * Effective expiration follows {@code expireAt != null ? expireAt : createdAt + PENDING_TTL},
     * so legacy / test rows whose {@code expireAt} column is NULL still time out. Expired
     * rows are reconciled (DB → TIMEOUT, message metadata → DENIED) and skipped from
     * the in-memory map. See RFC-067 §4.1.
     */
    void recoverFromDb() {
        try {
            List<ToolApprovalEntity> pendingRecords = approvalMapper.selectList(
                    new LambdaQueryWrapper<ToolApprovalEntity>()
                            .eq(ToolApprovalEntity::getStatus, "PENDING")
                            .orderByAsc(ToolApprovalEntity::getCreatedAt)
            );

            int recovered = 0;
            int expired = 0;
            Instant now = Instant.now();
            for (ToolApprovalEntity entity : pendingRecords) {
                // Defensive null handling: a row with neither createdAt nor expireAt is
                // treated as freshly created so the next GC tick can revisit it instead
                // of being silently lost.
                Instant createdAt = entity.getCreatedAt() != null
                        ? entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()
                        : now;
                Instant effectiveExpireAt = entity.getExpireAt() != null
                        ? entity.getExpireAt().atZone(ZoneId.systemDefault()).toInstant()
                        : createdAt.plus(ApprovalService.PENDING_TTL);

                if (now.isAfter(effectiveExpireAt)) {
                    expireRecoveredRow(entity);
                    expired++;
                    continue;
                }

                PendingApproval snapshot = new PendingApproval(
                        entity.getPendingId(),
                        entity.getConversationId(),
                        entity.getUserId(),
                        entity.getToolName(),
                        entity.getToolArguments(),
                        entity.getSummary(),
                        createdAt,
                        "pending"
                );
                snapshot.setToolCallPayload(entity.getToolCallPayload());
                snapshot.setSiblingToolCalls(entity.getSiblingToolCalls());
                snapshot.setAgentId(entity.getAgentId());
                snapshot.setChannelType(entity.getChannelType());
                snapshot.setRequesterName(entity.getRequesterName());
                snapshot.setReplyTarget(entity.getReplyTarget());
                snapshot.setFindingsJson(entity.getFindingsJson());
                snapshot.setMaxSeverity(entity.getMaxSeverity());
                snapshot.setSummary(entity.getSummary());

                approvalService.registerRecovered(snapshot);
                recovered++;
            }

            if (recovered > 0 || expired > 0) {
                log.info("[ApprovalWorkflow] DB recovery: recovered={}, expired={}", recovered, expired);
            }
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to recover from DB (table may not exist yet): {}", e.getMessage());
        }
    }

    /**
     * Move an expired DB row to TIMEOUT and reconcile message metadata so the UI does
     * not hydrate a ghost approval after restart.
     * <p>
     * Order matters: metadata writes are gated on DB success. If {@code updateById}
     * throws or affects zero rows, we skip the metadata flip so the three persistence
     * loci (DB / message metadata / in-memory map) cannot drift apart — DB stuck on
     * PENDING + metadata flipped to DENIED is the worst-case ghost state because the
     * next recoverFromDb would re-revive the approval while the UI insists it was
     * already settled.
     */
    private void expireRecoveredRow(ToolApprovalEntity entity) {
        int rowsUpdated;
        try {
            entity.setStatus("TIMEOUT");
            entity.setResolvedAt(LocalDateTime.now());
            rowsUpdated = approvalMapper.updateById(entity);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to mark expired row {} as TIMEOUT: {}",
                    entity.getPendingId(), e.getMessage());
            return;
        }
        if (rowsUpdated == 0) {
            log.warn("[ApprovalWorkflow] Expire skipped: DB row for pending {} affected 0 rows " +
                    "(concurrent resolve?); leaving metadata untouched", entity.getPendingId());
            return;
        }
        try {
            conversationService.markPendingApprovalsResolved(
                    entity.getConversationId(),
                    Set.of(entity.getPendingId()),
                    MetadataDecision.DENIED);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to reconcile metadata for expired pending {}: {}",
                    entity.getPendingId(), e.getMessage());
        }
    }

    /**
     * 创建待审批记录（增强版，含 GuardEvaluation）
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason,
                                String toolCallPayload, String siblingToolCalls, String agentId,
                                GuardEvaluation evaluation) {
        // 1. 内存层
        String pendingId = approvalService.createPending(
                conversationId, userId, toolName, toolArguments, reason,
                toolCallPayload, siblingToolCalls, agentId);

        // 2. 增强内存记录
        approvalService.getPending(pendingId).ifPresent(pending -> {
            if (evaluation != null) {
                pending.setFindingsJson(serializeFindings(evaluation.findings()));
                pending.setMaxSeverity(evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
                pending.setSummary(evaluation.summary());
            }
        });

        // 3. DB 层
        persistToDb(pendingId, conversationId, userId, toolName, toolArguments,
                toolCallPayload, siblingToolCalls, agentId, evaluation);

        return pendingId;
    }

    /**
     * 创建待审批记录（基础版，向后兼容）
     */
    public String createPending(String conversationId, String userId,
                                String toolName, String toolArguments, String reason,
                                String toolCallPayload, String siblingToolCalls, String agentId) {
        return createPending(conversationId, userId, toolName, toolArguments, reason,
                toolCallPayload, siblingToolCalls, agentId, null);
    }

    /**
     * Resolve a pending approval (approve / deny) following the RFC-067 §4.2 two-phase
     * contract: snapshot → DB UPDATE conditional on {@code status='PENDING'} →
     * metadata reconciliation → memory mutation queued for after-commit.
     * <p>
     * Idempotent under concurrent resolve: when the DB UPDATE affects 0 rows (because
     * another caller — IM channel, GC, recoverFromDb — already moved the row off
     * PENDING), this method returns {@link ResolveOutcome#alreadyResolved(String)}
     * without touching metadata or in-memory state. Callers should treat this as a
     * silent no-op; do not surface a user-facing error.
     * <p>
     * On DB / metadata write failure the transaction rolls back and in-memory state
     * stays untouched, so a retry from the next GC tick can recover. Memory mutation
     * is registered as an {@code afterCommit} synchronization, never inline, so a
     * post-update commit failure cannot leave memory ahead of DB.
     *
     * @param pendingId target approval id
     * @param userId    actor performing the resolution (for audit)
     * @param decision  case-insensitive {@code "approved"} or {@code "denied"}
     * @return {@link ResolveOutcome} carrying the resolved snapshot + DB / metadata
     *         counters; idempotent return on no-op
     */
    @Transactional
    public ResolveOutcome resolve(String pendingId, String userId, String decision) {
        boolean approved = "approved".equalsIgnoreCase(decision);
        String dbStatus = approved ? "APPROVED" : "DENIED";
        MetadataDecision metaDecision = approved ? MetadataDecision.APPROVED : MetadataDecision.DENIED;
        String snapshotStatus = approved ? "approved" : "denied";

        return performResolve(pendingId, userId, dbStatus, metaDecision, snapshotStatus,
                /* removeFromMap */ false);
    }

    /**
     * Atomically resolve {@code approved} and consume the snapshot for replay.
     * Same two-phase contract as {@link #resolve}, additionally removing the
     * pending entry from the in-memory map after commit so a subsequent
     * {@link #findPendingByConversation(String)} returns null and consume is
     * single-shot. The returned {@link ResolveOutcome#consumedSnapshot()} carries
     * {@code toolCallPayload} for replay.
     */
    @Transactional
    public ResolveOutcome resolveAndConsume(String pendingId, String userId) {
        return performResolve(pendingId, userId, "CONSUMED", MetadataDecision.APPROVED,
                "consumed", /* removeFromMap */ true);
    }

    /**
     * Consume the earliest already-{@code approved} record for the conversation +
     * tool — used when an out-of-band approval (e.g. /approve text command flow that
     * resolved the record) needs to be redeemed for replay.
     */
    @Transactional
    public ResolveOutcome consumeApproved(String conversationId, String toolName) {
        PendingApproval target = approvalService.findApprovedForConsume(conversationId, toolName);
        if (target == null) {
            return ResolveOutcome.alreadyResolved(null);
        }
        return performResolveOnSnapshot(target, null, "CONSUMED", MetadataDecision.APPROVED,
                "consumed", /* removeFromMap */ true);
    }

    /**
     * Bulk-deny every pending approval in the conversation (RFC-067 §4.4.1). Used by
     * the Web Stop endpoint to clear orphaned approvals when the user halts a turn
     * mid-stream; without this sweep, in-flight pendings linger in the map and
     * resurrect via metadata after refresh / restart.
     * <p>
     * Two-phase per row: DB → {@code DENIED}, message metadata → {@code DENIED},
     * map removed. Per-row failures are logged and the sweep continues; the returned
     * list contains only the outcomes that successfully advanced through DB.
     *
     * @return outcome per pending that successfully transitioned to {@code DENIED}
     */
    @Transactional
    public List<ResolveOutcome> denyAllByConversation(String conversationId, String userId) {
        List<PendingApproval> targets = approvalService.snapshotPendingByConversation(
                conversationId, /* excludePendingId */ null);
        if (targets.isEmpty()) return List.of();
        List<ResolveOutcome> outcomes = new java.util.ArrayList<>(targets.size());
        for (PendingApproval target : targets) {
            try {
                ResolveOutcome outcome = performResolveOnSnapshot(target, userId, "DENIED",
                        MetadataDecision.DENIED, "denied", /* removeFromMap */ true);
                if (outcome.dbSynced()) outcomes.add(outcome);
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] denyAll: failed to deny {}: {}",
                        target.getPendingId(), e.getMessage());
            }
        }
        return outcomes;
    }

    /**
     * Cancel every other pending approval in the conversation (excluding optional
     * {@code excludePendingId}) — used when a user submits a fresh message and the
     * old approval is implicitly abandoned. Each cancelled record goes through the
     * same two-phase contract; metadata flips to {@code DENIED} (per RFC-067 §4.4.1
     * state mapping for {@code superseded}).
     *
     * @return one outcome per pending that was actually moved off PENDING (empty list
     *         if there was nothing to cancel)
     */
    @Transactional
    public List<ResolveOutcome> cancelStalePending(String conversationId, String excludePendingId) {
        List<PendingApproval> targets = approvalService.snapshotPendingByConversation(
                conversationId, excludePendingId);
        if (targets.isEmpty()) return List.of();
        List<ResolveOutcome> outcomes = new java.util.ArrayList<>(targets.size());
        for (PendingApproval target : targets) {
            ResolveOutcome outcome = performResolveOnSnapshot(target, null, "SUPERSEDED",
                    MetadataDecision.DENIED, "superseded", /* removeFromMap */ true);
            if (outcome.dbSynced()) outcomes.add(outcome);
        }
        return outcomes;
    }

    /**
     * Time out a single pending approval (RFC-067 §4.4): same two-phase contract as
     * {@link #resolve} but with DB → {@code TIMEOUT} and metadata → {@code DENIED}
     * (per RFC-067 §4.4.1 state mapping). Called by the GC scheduler for entries
     * past {@link ApprovalService#PENDING_TTL} or beyond {@link ApprovalService#MAX_PENDING}.
     * Package-private — not part of the external resolve API.
     */
    @Transactional
    ResolveOutcome markTimeout(String pendingId) {
        return performResolve(pendingId, null, "TIMEOUT", MetadataDecision.DENIED, "timeout",
                /* removeFromMap */ true);
    }

    /**
     * GC tick (5-minute cadence; runs in {@code approval-gc} daemon thread).
     * <ol>
     *   <li>Phase A — pending older than {@link ApprovalService#PENDING_TTL} time out
     *       through the full DB+metadata+memory contract.</li>
     *   <li>Phase B — when total pending count exceeds {@link ApprovalService#MAX_PENDING},
     *       evict the oldest excess via the same {@code markTimeout} path.</li>
     *   <li>Phase C — already-resolved entries (DB row already terminal) past
     *       {@link ApprovalService#RESOLVED_TTL} or beyond
     *       {@link ApprovalService#MAX_RESOLVED} are dropped from the map only —
     *       the DB does not need touching, nor does message metadata.</li>
     * </ol>
     * Each pending entry's transition runs in its own transaction (markTimeout is
     * @Transactional) so a single bad row doesn't block the rest of the sweep.
     */
    public void garbageCollect() {
        Instant now = Instant.now();

        // Phase A — TTL-expired pending. Snapshot first so we don't mutate a map
        // we're iterating; markTimeout handles its own DB+metadata+memory contract.
        int timedOut = 0;
        for (PendingApproval expired : approvalService.snapshotExpiredPending(now)) {
            try {
                ResolveOutcome outcome = markTimeout(expired.getPendingId());
                if (outcome.dbSynced()) timedOut++;
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] GC: markTimeout failed for {}: {}",
                        expired.getPendingId(), e.getMessage());
            }
        }

        // Phase B — pending overflow eviction. Same path; just driven by a count cap.
        int evictedPending = 0;
        for (PendingApproval excess : approvalService.snapshotExcessPending(ApprovalService.MAX_PENDING)) {
            try {
                ResolveOutcome outcome = markTimeout(excess.getPendingId());
                if (outcome.dbSynced()) evictedPending++;
            } catch (Exception e) {
                log.warn("[ApprovalWorkflow] GC: overflow markTimeout failed for {}: {}",
                        excess.getPendingId(), e.getMessage());
            }
        }

        // Phase C — resolved cleanup. Memory-only; DB rows for these entries are
        // already terminal (CONSUMED / DENIED / TIMEOUT / SUPERSEDED) so nothing
        // would change in DB or metadata.
        int droppedResolved = approvalService.dropResolvedExceedingLimits(now);

        if (timedOut > 0 || evictedPending > 0 || droppedResolved > 0) {
            log.info("[ApprovalWorkflow] GC: timed-out {}, evicted-pending {}, dropped-resolved {}, remaining={}",
                    timedOut, evictedPending, droppedResolved, approvalService.size());
        }
    }

    // ---------- shared two-phase machinery ----------

    private ResolveOutcome performResolve(String pendingId, String userId,
                                          String dbStatus, MetadataDecision metaDecision,
                                          String snapshotStatus, boolean removeFromMap) {
        PendingApproval snapshot = approvalService.getPending(pendingId).orElse(null);
        if (snapshot == null || !"pending".equals(snapshot.getStatus())) {
            log.debug("[ApprovalWorkflow] resolve {}: not pending (snapshot={}, status={})",
                    pendingId, snapshot != null, snapshot != null ? snapshot.getStatus() : "n/a");
            return ResolveOutcome.alreadyResolved(pendingId);
        }
        return performResolveOnSnapshot(snapshot, userId, dbStatus, metaDecision,
                snapshotStatus, removeFromMap);
    }

    private ResolveOutcome performResolveOnSnapshot(PendingApproval snapshot, String userId,
                                                    String dbStatus, MetadataDecision metaDecision,
                                                    String snapshotStatus, boolean removeFromMap) {
        // Phase 1 — DB UPDATE (conditional). The eq("PENDING") guard makes the call
        // idempotent: if another path already won, we get rows=0 and bail without
        // touching metadata or memory.
        int rows;
        try {
            LambdaUpdateWrapper<ToolApprovalEntity> wrapper = new LambdaUpdateWrapper<ToolApprovalEntity>()
                    .eq(ToolApprovalEntity::getPendingId, snapshot.getPendingId())
                    .eq(ToolApprovalEntity::getStatus, "PENDING")
                    .set(ToolApprovalEntity::getStatus, dbStatus)
                    .set(ToolApprovalEntity::getResolvedAt, LocalDateTime.now());
            if (userId != null) {
                wrapper.set(ToolApprovalEntity::getResolvedBy, userId);
            }
            rows = approvalMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] DB UPDATE failed for {} -> {}: {}",
                    snapshot.getPendingId(), dbStatus, e.getMessage());
            // Re-throw so @Transactional rolls back any partial state and the caller sees the failure.
            throw e;
        }
        if (rows == 0) {
            log.info("[ApprovalWorkflow] resolve no-op for {}: DB row not in PENDING (concurrent resolve)",
                    snapshot.getPendingId());
            return ResolveOutcome.alreadyResolved(snapshot.getPendingId());
        }

        // Phase 2 — metadata. Same transaction. If this throws, @Transactional rolls back DB.
        int rewritten = conversationService.markPendingApprovalsResolved(
                snapshot.getConversationId(),
                Set.of(snapshot.getPendingId()),
                metaDecision);

        // Phase 3 — memory mutation, deferred until after commit. Registering inside
        // a @Transactional method binds the hook to the active tx; if the tx rolls
        // back (post-method but pre-commit failure, e.g. constraint violation at
        // flush), the hook never fires and memory stays consistent with DB.
        Instant resolvedAt = Instant.now();
        afterCommit(() -> {
            snapshot.setStatus(snapshotStatus);
            snapshot.setResolvedAt(resolvedAt);
            if (userId != null) snapshot.setResolvedBy(userId);
            if (removeFromMap) approvalService.removeFromMap(snapshot.getPendingId());
        });

        boolean consumed = "consumed".equals(snapshotStatus);
        ResolveOutcome outcome = consumed
                ? ResolveOutcome.consumed(snapshot, true, rewritten)
                : ResolveOutcome.resolved(snapshot,
                        "superseded".equals(snapshotStatus) ? "superseded" : snapshotStatus,
                        true, rewritten);
        log.info("[ApprovalWorkflow] resolved id={}, decision={}, dbStatus={}, messagesRewritten={}",
                snapshot.getPendingId(), outcome.decision(), dbStatus, rewritten);
        return outcome;
    }

    /**
     * Run a memory mutation only after the surrounding {@code @Transactional} method's
     * tx commits. When called outside a transaction (e.g. unit tests that bypass the
     * proxy), executes immediately to keep test ergonomics simple.
     */
    private void afterCommit(Runnable hook) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    hook.run();
                }
            });
        } else {
            hook.run();
        }
    }

    /**
     * 代理查询方法
     */
    public PendingApproval findPendingByConversation(String conversationId) {
        return approvalService.findPendingByConversation(conversationId);
    }

    public List<Map<String, Object>> getPendingByConversation(String conversationId) {
        return approvalService.getPendingByConversation(conversationId);
    }

    // ==================== 内部方法 ====================

    private void persistToDb(String pendingId, String conversationId, String userId,
                             String toolName, String toolArguments,
                             String toolCallPayload, String siblingToolCalls, String agentId,
                             GuardEvaluation evaluation) {
        try {
            ToolApprovalEntity entity = new ToolApprovalEntity();
            entity.setPendingId(pendingId);
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setAgentId(agentId);
            entity.setToolName(toolName);
            entity.setToolArguments(toolArguments);
            entity.setToolCallPayload(toolCallPayload);
            entity.setSiblingToolCalls(siblingToolCalls);
            entity.setStatus("PENDING");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setExpireAt(LocalDateTime.now().plusMinutes(30));

            if (evaluation != null) {
                entity.setFindingsJson(serializeFindings(evaluation.findings()));
                entity.setMaxSeverity(evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
                entity.setSummary(evaluation.summary());

                if (toolCallPayload != null) {
                    entity.setToolCallHash(String.valueOf(toolCallPayload.hashCode()));
                }
            }

            approvalMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to persist approval to DB: {}", e.getMessage());
        }
    }

    private void updateDbStatus(String pendingId, String status, String resolvedBy) {
        try {
            LambdaUpdateWrapper<ToolApprovalEntity> wrapper = new LambdaUpdateWrapper<ToolApprovalEntity>()
                    .eq(ToolApprovalEntity::getPendingId, pendingId)
                    .set(ToolApprovalEntity::getStatus, status)
                    .set(ToolApprovalEntity::getResolvedAt, LocalDateTime.now());

            if (resolvedBy != null) {
                wrapper.set(ToolApprovalEntity::getResolvedBy, resolvedBy);
            }
            approvalMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("[ApprovalWorkflow] Failed to update DB status: {}", e.getMessage());
        }
    }

    private String serializeFindings(List<GuardFinding> findings) {
        if (findings == null || findings.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(
                    findings.stream().map(GuardFinding::toMap).toList()
            );
        } catch (JsonProcessingException e) {
            log.warn("[ApprovalWorkflow] Failed to serialize findings: {}", e.getMessage());
            return null;
        }
    }
}
