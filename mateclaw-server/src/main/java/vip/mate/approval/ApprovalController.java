package vip.mate.approval;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;

/**
 * Approval read-only endpoints.
 * <p>
 * Web approve / deny actions ride the SSE {@code POST /chat/stream} path with
 * {@code /approve} or {@code /deny} text commands ({@link vip.mate.channel.web.ChatController}
 * intercepts), so a write-style {@code POST /approve} REST endpoint was deleted
 * in RFC-067 PR 6 — it bypassed the unified workflow lifecycle and let any
 * future caller silently regress to the pre-RFC ghost-approval state.
 * <p>
 * Only {@link #getPendingApprovals} remains, used by the frontend for hydration
 * after page refresh.
 *
 * @author MateClaw Team
 */
@Tag(name = "工具审批")
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalWorkflowService approvalService;
    private final ConversationService conversationService;

    /**
     * Hydration query for page refresh: returns every pending approval still
     * waiting in the conversation. The frontend uses this to rebuild the
     * approval banner after a reload.
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
}
