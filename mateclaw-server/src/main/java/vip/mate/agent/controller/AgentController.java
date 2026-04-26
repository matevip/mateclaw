package vip.mate.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.Utf8SseEmitter;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentState;
import vip.mate.agent.model.AgentEntity;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "Agent管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AuditEventService auditEventService;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @Operation(summary = "获取Agent列表")
    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<AgentEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // 无 header 时强制使用默认 workspace，不返回全局数据
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(agentService.listAgentsByWorkspace(wsId));
    }

    @Operation(summary = "获取Agent详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("viewer")
    public R<AgentEntity> get(@PathVariable Long id,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent.getWorkspaceId(), workspaceId);
        return R.ok(agent);
    }

    @Operation(summary = "创建Agent")
    @PostMapping
    @RequireWorkspaceRole("member")
    public R<AgentEntity> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody AgentEntity agent) {
        // 始终注入 workspace_id，无 header 时使用默认
        agent.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        AgentEntity created = agentService.createAgent(agent);
        auditEventService.record("CREATE", "AGENT", String.valueOf(created.getId()), created.getName(), null);
        return R.ok(created);
    }

    @Operation(summary = "更新Agent")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<AgentEntity> update(@PathVariable Long id, @RequestBody AgentEntity agent,
                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity existing = agentService.getAgent(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
        agent.setId(id);
        agent.setWorkspaceId(existing.getWorkspaceId()); // 不允许跨 workspace 迁移
        AgentEntity updated = agentService.updateAgent(agent);
        auditEventService.record("UPDATE", "AGENT", String.valueOf(id), updated.getName(), null);
        return R.ok(updated);
    }

    @Operation(summary = "删除Agent")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent.getWorkspaceId(), workspaceId);
        agentService.deleteAgent(id);
        auditEventService.record("DELETE", "AGENT", String.valueOf(id), agent.getName(), null);
        return R.ok();
    }

    @Operation(summary = "流式对话（SSE）")
    @GetMapping("/{id}/chat/stream")
    @RequireWorkspaceRole("viewer")
    public SseEmitter chatStream(
            @PathVariable Long id,
            @RequestParam String message,
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);

        // RFC-058 PR-1: Utf8SseEmitter 显式 charset=UTF-8，防止中文 SSE 乱码
        SseEmitter emitter = new Utf8SseEmitter(5 * 60 * 1000L);
        sseExecutor.execute(() -> {
            try {
                agentService.chatStream(id, message, conversationId)
                        .doOnNext(chunk -> {
                            try {
                                emitter.send(SseEmitter.event().name("message").data(chunk));
                            } catch (IOException e) {
                                log.warn("SSE send error: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(emitter::completeWithError)
                        .subscribe();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @Operation(summary = "同步对话")
    @PostMapping("/{id}/chat")
    @RequireWorkspaceRole("viewer")
    public R<String> chat(
            @PathVariable Long id,
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);
        return R.ok(agentService.chat(id, request.getMessage(), request.getConversationId()));
    }

    @Operation(summary = "执行复杂任务（Plan-Execute）")
    @PostMapping("/{id}/execute")
    @RequireWorkspaceRole("viewer")
    public R<String> execute(
            @PathVariable Long id,
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);
        return R.ok(agentService.execute(id, request.getMessage(), request.getConversationId()));
    }

    @Operation(summary = "获取Agent运行状态")
    @GetMapping("/{id}/state")
    @RequireWorkspaceRole("viewer")
    public R<AgentState> getState(@PathVariable Long id,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AgentEntity agent = agentService.getAgent(id);
        verifyResourceWorkspace(agent != null ? agent.getWorkspaceId() : null, workspaceId);
        return R.ok(agentService.getAgentState(id));
    }

    @lombok.Data
    public static class ChatRequest {
        private String message;
        private String conversationId = "default";
    }

    /**
     * 校验目标资源实际归属的 workspace 与请求 header 一致。
     * 防止 "在 workspace A 鉴权，操作 workspace B 资源" 的跨域攻击。
     */
    private void verifyResourceWorkspace(Long resourceWorkspaceId, Long headerWorkspaceId) {
        long requestedWs = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (resourceWorkspaceId != null && !resourceWorkspaceId.equals(requestedWs)) {
            throw new MateClawException("err.common.wrong_workspace", "资源不属于当前工作区");
        }
    }
}
