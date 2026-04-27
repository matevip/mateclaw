package vip.mate.approval;

import java.time.Instant;

/**
 * 待审批记录（消息驱动版）
 * <p>
 * 不再持有 CompletableFuture，状态流转由 status 字段驱动。
 * 包含工具调用重放所需的全部信息。
 */
public class PendingApproval {

    private final String pendingId;
    private final String conversationId;
    private final String userId;
    private final String toolName;
    private final String toolArguments;
    private final String reason;
    private final Instant createdAt;

    // === 状态 ===
    // pending → approved → consumed / denied / timeout / superseded
    private volatile String status;

    // === 重放相关字段 ===

    /** 发起审批的渠道类型 */
    private String channelType;

    /** 发送者名称（审计日志） */
    private String requesterName;

    /** 回复目标标识（飞书 chatId、钉钉 conversationId 等） */
    private String replyTarget;

    /** 完整的 tool call 载荷（JSON），用于 replay 重放 */
    private String toolCallPayload;

    /** 同一轮中其他被阻塞的 tool calls（JSON 数组） */
    private String siblingToolCalls;

    /** Agent ID，重放时需要知道用哪个 Agent */
    private String agentId;

    /** 审批解决时间 */
    private Instant resolvedAt;

    /** 审批解决者 userId */
    private String resolvedBy;

    // === 增强字段（Phase 2: 结构化风险信息）===

    /** Guard findings JSON（结构化风险发现列表） */
    private String findingsJson;

    /** 最高风险等级 */
    private String maxSeverity;

    /** 风险摘要 */
    private String summary;

    public PendingApproval(String pendingId, String conversationId, String userId,
                           String toolName, String toolArguments, String reason) {
        this.pendingId = pendingId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.toolName = toolName;
        this.toolArguments = toolArguments;
        this.reason = reason;
        this.createdAt = Instant.now();
        this.status = "pending";
    }

    /**
     * INTERNAL — recovery constructor for {@code ApprovalWorkflowService.recoverFromDb}.
     * Preserves the persisted {@code createdAt} and {@code status} so TTL/GC keep working
     * across JVM restarts. Do not use from business paths.
     */
    PendingApproval(String pendingId, String conversationId, String userId,
                    String toolName, String toolArguments, String reason,
                    Instant createdAt, String status) {
        this.pendingId = pendingId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.toolName = toolName;
        this.toolArguments = toolArguments;
        this.reason = reason;
        this.createdAt = createdAt;
        this.status = status;
    }

    // === Getters ===

    public String getPendingId() { return pendingId; }
    public String getConversationId() { return conversationId; }
    public String getUserId() { return userId; }
    public String getToolName() { return toolName; }
    public String getToolArguments() { return toolArguments; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getChannelType() { return channelType; }
    public String getRequesterName() { return requesterName; }
    public String getReplyTarget() { return replyTarget; }
    public String getToolCallPayload() { return toolCallPayload; }
    public String getSiblingToolCalls() { return siblingToolCalls; }
    public String getAgentId() { return agentId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public String getFindingsJson() { return findingsJson; }
    public String getMaxSeverity() { return maxSeverity; }
    public String getSummary() { return summary; }

    // === Setters ===

    public void setStatus(String status) { this.status = status; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public void setRequesterName(String requesterName) { this.requesterName = requesterName; }
    public void setReplyTarget(String replyTarget) { this.replyTarget = replyTarget; }
    public void setToolCallPayload(String toolCallPayload) { this.toolCallPayload = toolCallPayload; }
    public void setSiblingToolCalls(String siblingToolCalls) { this.siblingToolCalls = siblingToolCalls; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public void setFindingsJson(String findingsJson) { this.findingsJson = findingsJson; }
    public void setMaxSeverity(String maxSeverity) { this.maxSeverity = maxSeverity; }
    public void setSummary(String summary) { this.summary = summary; }
}
