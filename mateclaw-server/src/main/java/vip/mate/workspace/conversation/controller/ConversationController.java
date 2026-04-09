package vip.mate.workspace.conversation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.vo.ConversationVO;
import vip.mate.workspace.conversation.vo.MessageVO;

import java.util.List;
import java.util.Map;

/**
 * 会话管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "会话管理")
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatStreamTracker streamTracker;

    /**
     * 获取当前用户的会话列表
     * 返回 ConversationVO，包含 agentName / agentIcon / status 等前端展示字段
     */
    @Operation(summary = "获取会话列表")
    @GetMapping
    public R<List<ConversationVO>> list(
            Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        String username = auth != null ? auth.getName() : "anonymous";
        return R.ok(conversationService.listConversations(username, workspaceId));
    }

    /**
     * 获取指定会话的消息历史
     */
    @Operation(summary = "获取会话消息历史")
    @GetMapping("/{conversationId}/messages")
    public R<List<MessageVO>> listMessages(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权访问该会话");
        }
        return R.ok(conversationService.listMessageViews(conversationId));
    }

    /**
     * 删除会话（同时删除消息）
     */
    @Operation(summary = "删除会话")
    @DeleteMapping("/{conversationId}")
    public R<Void> delete(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权操作该会话");
        }
        conversationService.deleteConversation(conversationId);
        return R.ok();
    }

    /**
     * 重命名会话
     */
    @Operation(summary = "重命名会话")
    @PutMapping("/{conversationId}/title")
    public R<Void> rename(@PathVariable String conversationId, @RequestBody Map<String, String> body, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权操作该会话");
        }
        String title = body.getOrDefault("title", "").trim();
        if (title.isEmpty() || title.length() > 100) {
            return R.fail("标题不合法");
        }
        conversationService.renameConversation(conversationId, title);
        return R.ok();
    }

    /**
     * 清空会话消息（保留会话记录）
     */
    @Operation(summary = "清空会话消息")
    @DeleteMapping("/{conversationId}/messages")
    public R<Void> clearMessages(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权操作该会话");
        }
        conversationService.clearMessages(conversationId);
        return R.ok();
    }

    /**
     * 获取会话的流状态
     * 优先使用内存中的 StreamTracker，若无数据则回退到数据库持久化的 stream_status
     */
    @Operation(summary = "获取会话流状态")
    @GetMapping("/{conversationId}/status")
    public R<Map<String, String>> getStreamStatus(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权访问该会话");
        }
        if (streamTracker.isRunning(conversationId)) {
            return R.ok(Map.of("streamStatus", "running"));
        }
        // 回退到数据库持久化的 stream_status（处理服务重启/节点切换场景）
        String dbStatus = conversationService.getStreamStatus(conversationId);
        return R.ok(Map.of("streamStatus", dbStatus != null ? dbStatus : "idle"));
    }
}
