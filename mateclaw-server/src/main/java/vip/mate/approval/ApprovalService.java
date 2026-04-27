package vip.mate.approval;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行审批服务（消息驱动版 — 非阻塞）
 * <p>
 * 核心变化：不再阻塞线程等待审批。
 * <ul>
 *   <li>{@link #createPending} 创建待审批记录后立即返回</li>
 *   <li>{@link #resolve} 更新状态为 approved/denied</li>
 *   <li>{@link #findPendingByConversation} 查找会话最早的 pending（FIFO）</li>
 *   <li>{@link #consumeApproved} 一次性消费已批准记录供重放</li>
 *   <li>{@link #garbageCollect} 定时清理过期记录</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class ApprovalService {

    private final ConcurrentHashMap<String, PendingApproval> pendingMap = new ConcurrentHashMap<>();

    /** GC 常量 */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);
    private static final Duration RESOLVED_TTL = Duration.ofHours(1);
    private static final int MAX_PENDING = 200;
    private static final int MAX_RESOLVED = 500;

    private ScheduledExecutorService gcScheduler;

    @PostConstruct
    void initGc() {
        gcScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-gc");
            t.setDaemon(true);
            return t;
        });
        gcScheduler.scheduleAtFixedRate(this::garbageCollect, 5, 5, TimeUnit.MINUTES);
        log.info("[Approval] GC scheduler started (interval=5min)");
    }

    @PreDestroy
    void shutdownGc() {
        if (gcScheduler != null) {
            gcScheduler.shutdownNow();
        }
    }

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

    // ==================== 解决 ====================

    /**
     * 解决审批（批准或拒绝）
     *
     * @param pendingId 待审批 ID
     * @param userId    操作用户
     * @param decision  "approved" 或 "denied"
     * @throws IllegalArgumentException 如果 pending 不存在
     */
    public void resolve(String pendingId, String userId, String decision) {
        PendingApproval pending = pendingMap.get(pendingId);
        if (pending == null) {
            throw new IllegalArgumentException("审批记录不存在或已过期: " + pendingId);
        }

        if ("approved".equalsIgnoreCase(decision)) {
            pending.setStatus("approved");
        } else {
            pending.setStatus("denied");
        }
        pending.setResolvedAt(Instant.now());
        pending.setResolvedBy(userId);

        log.info("[Approval] Resolved: id={}, decision={}, by={}", pendingId, decision, userId);
    }

    /**
     * Bulk-deny every pending approval still in {@code pending} status for this
     * conversation. Used by the Stop endpoint to clear orphaned approvals so
     * subsequent UI refreshes don't keep popping the "approve write_file?"
     * banner forever, and so a `findPendingByConversation` lookup right after
     * Stop returns null.
     * <p>
     * Returns the list of {@link PendingApproval} records that were marked
     * denied — callers typically use this list to update the corresponding
     * {@code mate_message.metadata.pendingApproval.status} entries in DB
     * (otherwise a page refresh re-hydrates ghost approvals from message
     * metadata even after the in-memory map is cleared).
     */
    public List<PendingApproval> denyAllByConversation(String conversationId, String userId) {
        Instant now = Instant.now();
        List<PendingApproval> resolved = new ArrayList<>();
        for (PendingApproval pending : pendingMap.values()) {
            if (!conversationId.equals(pending.getConversationId())) continue;
            if (!"pending".equals(pending.getStatus())) continue;
            pending.setStatus("denied");
            pending.setResolvedAt(now);
            pending.setResolvedBy(userId);
            resolved.add(pending);
        }
        if (!resolved.isEmpty()) {
            log.info("[Approval] Bulk-denied {} pending approvals for conversation {}",
                    resolved.size(), conversationId);
        }
        return resolved;
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

    // ==================== 原子解决+消费（IM 渠道 /approve 命令） ====================

    /**
     * 原子地 resolve 并 consume 审批记录（用于 IM 渠道 /approve 命令）
     * <p>
     * 合并 resolve() + consumeApproved() 为单一操作，消除 race condition。
     *
     * @param pendingId 待审批 ID
     * @param userId    操作用户
     * @return 已消费的 PendingApproval（含 toolCallPayload），不存在或已处理返回 null
     */
    public synchronized PendingApproval resolveAndConsume(String pendingId, String userId) {
        PendingApproval pending = pendingMap.get(pendingId);
        if (pending == null || !"pending".equals(pending.getStatus())) {
            log.warn("[Approval] resolveAndConsume: not found or not pending: id={}", pendingId);
            return null;
        }
        pending.setStatus("consumed");
        pending.setResolvedAt(Instant.now());
        pending.setResolvedBy(userId);
        pendingMap.remove(pendingId);
        log.info("[Approval] Resolved and consumed atomically: id={}, tool={}", pendingId, pending.getToolName());
        return pending;
    }

    // ==================== 消费（重放时调用） ====================

    /**
     * 消费已批准的审批记录（一次性消费）
     * <p>
     * 验证 toolName 匹配（如果指定），防止参数替换攻击。
     * 移除记录并返回 PendingApproval 供重放。
     *
     * @param conversationId 会话 ID
     * @param toolName       要验证的工具名（null 跳过验证）
     * @return 已消费的 PendingApproval，或 null 如果无匹配
     */
    public PendingApproval consumeApproved(String conversationId, String toolName) {
        return consumeApproved(conversationId, toolName, null);
    }

    /**
     * 消费一条已审批的记录（带参数匹配校验，防止审批后参数替换攻击）
     */
    public PendingApproval consumeApproved(String conversationId, String toolName, String toolArguments) {
        PendingApproval target = pendingMap.values().stream()
                .filter(p -> conversationId.equals(p.getConversationId()))
                .filter(p -> "approved".equals(p.getStatus()))
                .filter(p -> toolName == null || toolName.equals(p.getToolName()))
                .filter(p -> toolArguments == null || toolArguments.equals(p.getToolArguments()))
                .min(Comparator.comparing(PendingApproval::getCreatedAt))
                .orElse(null);

        if (target == null) {
            return null;
        }

        target.setStatus("consumed");
        pendingMap.remove(target.getPendingId());
        log.info("[Approval] Consumed approved: id={}, tool={}, conversation={}",
                target.getPendingId(), target.getToolName(), conversationId);
        return target;
    }

    // ==================== 取消与清理 ====================

    /**
     * 取消指定会话的所有 pending（用户发新消息时旧 pending 自动取消）
     *
     * @param conversationId  会话 ID
     * @param excludePendingId 排除的 pendingId（当前正在创建的，可为 null）
     */
    public void cancelStalePending(String conversationId, String excludePendingId) {
        pendingMap.values().stream()
                .filter(p -> conversationId.equals(p.getConversationId()))
                .filter(p -> "pending".equals(p.getStatus()))
                .filter(p -> !p.getPendingId().equals(excludePendingId))
                .forEach(p -> {
                    p.setStatus("superseded");
                    p.setResolvedAt(Instant.now());
                    pendingMap.remove(p.getPendingId());
                    log.info("[Approval] Cancelled stale pending: id={}", p.getPendingId());
                });
    }

    /**
     * 定时清理过期记录
     * <ul>
     *   <li>pending 超过 30 分钟 → 标记 TIMEOUT 并清除</li>
     *   <li>resolved（非 pending）超过 1 小时 → 清除</li>
     *   <li>上限：pending 200 条，resolved 500 条</li>
     * </ul>
     */
    public void garbageCollect() {
        Instant now = Instant.now();
        int expiredPending = 0;
        int expiredResolved = 0;

        List<String> toRemove = new ArrayList<>();

        for (PendingApproval p : pendingMap.values()) {
            if ("pending".equals(p.getStatus())) {
                if (Duration.between(p.getCreatedAt(), now).compareTo(PENDING_TTL) > 0) {
                    p.setStatus("timeout");
                    p.setResolvedAt(now);
                    toRemove.add(p.getPendingId());
                    expiredPending++;
                }
            } else {
                // 已解决的记录
                Instant resolvedAt = p.getResolvedAt() != null ? p.getResolvedAt() : p.getCreatedAt();
                if (Duration.between(resolvedAt, now).compareTo(RESOLVED_TTL) > 0) {
                    toRemove.add(p.getPendingId());
                    expiredResolved++;
                }
            }
        }

        toRemove.forEach(pendingMap::remove);

        // 上限检查
        enforceLimit("pending", MAX_PENDING);
        enforceLimit("resolved", MAX_RESOLVED);

        if (expiredPending > 0 || expiredResolved > 0) {
            log.info("[Approval] GC: expired {} pending, {} resolved, remaining={}",
                    expiredPending, expiredResolved, pendingMap.size());
        }
    }

    private void enforceLimit(String statusType, int maxCount) {
        boolean isPending = "pending".equals(statusType);
        List<PendingApproval> matching = pendingMap.values().stream()
                .filter(p -> isPending ? "pending".equals(p.getStatus()) : !"pending".equals(p.getStatus()))
                .sorted(Comparator.comparing(PendingApproval::getCreatedAt))
                .toList();

        if (matching.size() > maxCount) {
            int toEvict = matching.size() - maxCount;
            for (int i = 0; i < toEvict; i++) {
                PendingApproval oldest = matching.get(i);
                if (isPending) {
                    oldest.setStatus("timeout");
                    oldest.setResolvedAt(Instant.now());
                }
                pendingMap.remove(oldest.getPendingId());
            }
            log.info("[Approval] Evicted {} {} records (exceeded limit {})", toEvict, statusType, maxCount);
        }
    }
}
