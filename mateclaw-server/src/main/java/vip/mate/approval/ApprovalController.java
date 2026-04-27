package vip.mate.approval;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;

/**
 * 工具执行审批接口
 * <p>
 * 提供 approve / deny 端点，供前端在收到 tool_approval_requested SSE 事件后调用。
 * 批准后自动触发工具重放，结果通过 SSE 流推送给前端。
 *
 * @author MateClaw Team
 */
@Tag(name = "工具审批")
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ConversationService conversationService;
    private final ChatStreamTracker streamTracker;

    /**
     * 批准或拒绝工具执行
     * <p>
     * 批准后自动触发工具重放（异步执行），结果通过已有的 SSE 连接推送给前端。
     */
    @Operation(summary = "审批工具执行")
    @PostMapping("/{conversationId}/approve")
    public R<String> approve(
            @PathVariable String conversationId,
            @RequestBody ApprovalRequest request,
            Authentication auth) {

        if (auth == null) {
            return R.fail(401, "未登录，请先登录");
        }
        String username = auth.getName();

        // 校验会话归属
        if (!conversationService.isConversationOwner(conversationId, username)) {
            log.warn("[Approval] Unauthorized: user={} is not owner of conversation={}", username, conversationId);
            return R.fail(403, "无权操作该会话");
        }

        // 校验 pendingId
        if (request.getPendingId() == null || request.getPendingId().isBlank()) {
            return R.fail("pendingId 不能为空");
        }

        // 校验 decision
        String decision = request.getDecision();
        if (decision == null || (!decision.equalsIgnoreCase("approved") && !decision.equalsIgnoreCase("denied"))) {
            return R.fail("decision 必须为 approved 或 denied");
        }

        try {
            approvalService.resolve(request.getPendingId(), username, decision);
            log.info("[Approval] User {} {} pending {} for conversation {}",
                    username, decision, request.getPendingId(), conversationId);

            // Persist the resolved status onto the assistant message metadata so a
            // subsequent page refresh doesn't hydrate a ghost approval banner from
            // the stale "pending_approval" status frozen at message-save time.
            conversationService.markPendingApprovalsResolved(
                    conversationId,
                    java.util.Set.of(request.getPendingId()),
                    "approved".equalsIgnoreCase(decision) ? "approved" : "denied");

            // Web 端的 replay 由前端发送 /approve 消息到 POST /stream 触发（ChatController 拦截）
            // 此端点只更新审批状态，保留给 IM 渠道（DingTalk/Feishu 等通过 ChannelMessageRouter 调用）

            // 拒绝时通过 SSE 通知前端（如果流还活着）
            if ("denied".equalsIgnoreCase(decision) && streamTracker.isRunning(conversationId)) {
                streamTracker.broadcastObject(conversationId, "tool_approval_resolved", Map.of(
                        "pendingId", request.getPendingId(),
                        "decision", "denied",
                        "timestamp", System.currentTimeMillis()
                ));
            }

            return R.ok("操作成功");
        } catch (IllegalArgumentException e) {
            log.warn("[Approval] Resolve failed: {}", e.getMessage());
            return R.fail(e.getMessage());
        }
    }

    /**
     * 查询指定会话下的待审批记录
     * <p>
     * 用于页面刷新后恢复审批卡片（hydration）。
     */
    @Operation(summary = "查询待审批记录")
    @GetMapping("/{conversationId}/pending-approvals")
    public R<List<Map<String, Object>>> getPendingApprovals(
            @PathVariable String conversationId,
            Authentication auth) {

        if (auth == null) {
            return R.fail(401, "未登录，请先登录");
        }
        String username = auth.getName();

        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权访问该会话");
        }

        List<Map<String, Object>> pending = approvalService.getPendingByConversation(conversationId);
        return R.ok(pending);
    }

    @Data
    public static class ApprovalRequest {
        private String pendingId;
        /** "approved" 或 "denied" */
        private String decision;
    }
}
