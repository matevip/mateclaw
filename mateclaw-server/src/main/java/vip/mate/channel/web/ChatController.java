package vip.mate.channel.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.common.result.R;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.approval.ApprovalService;
import vip.mate.approval.PendingApproval;
import vip.mate.memory.event.ConversationCompletedEvent;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web 渠道聊天接口
 * 提供 SSE 流式对话和同步对话能力
 *
 * @author MateClaw Team
 */
@Tag(name = "Web聊天")
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ApprovalService approvalService;
    private final ChatStreamTracker streamTracker;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Path uploadRoot = Paths.get("data", "chat-uploads");

    // 使用虚拟线程池处理 SSE（Java 17+ 兼容，Java 21 可用 Executors.newVirtualThreadPerTaskExecutor()）
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * SSE 流式对话（支持断线重连）
     * <p>
     * 正常请求：保存用户消息，启动 Flux 生产者，通过 StreamTracker 广播事件。
     * 重连请求（reconnect=true）：附着到仍在运行的流，回放已缓冲事件后接收实时增量。
     */
    @Operation(summary = "结构化 SSE 流式对话（支持重连）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestBody ChatStreamRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {

        String conversationId = request.getConversationId() != null ? request.getConversationId() : "default";
        // SSE 超时设为 10 分钟，覆盖 servlet 默认的 30s，避免长回答被中断
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);

        // ---- 分支 A：断线重连 ----
        if (Boolean.TRUE.equals(request.getReconnect())) {
            String reconnectUser = auth != null ? auth.getName() : "anonymous";
            log.info("SSE reconnect: conversationId={}, user={}", conversationId, reconnectUser);

            // 校验会话归属
            if (!conversationService.isConversationOwner(conversationId, reconnectUser)) {
                try {
                    sendEvent(emitter, "error", Map.of("message", "无权访问该会话"));
                } catch (IOException e) {
                    log.warn("SSE reconnect auth error send failed: {}", e.getMessage());
                }
                emitter.complete();
                return emitter;
            }

            registerEmitterCallbacks(emitter, conversationId);

            boolean attached = streamTracker.attach(conversationId, emitter);
            if (!attached) {
                // 没有活跃的流（已完成或服务器重启后丢失），通知前端直接结束
                try {
                    sendEvent(emitter, "done", Map.of("status", "completed"));
                } catch (IOException e) {
                    log.warn("SSE reconnect done send error: {}", e.getMessage());
                }
                emitter.complete();
            }
            return emitter;
        }

        // ---- 分支 B：正常请求 ----
        Long agentId = request.getAgentId();
        String message = request.getMessage() != null ? request.getMessage() : "";
        if (auth == null) {
            try {
                sendEvent(emitter, "error", Map.of("message", "未登录，请先登录"));
            } catch (IOException e) {
                log.warn("SSE auth error send failed: {}", e.getMessage());
            }
            emitter.complete();
            return emitter;
        }
        String username = auth.getName();
        log.info("SSE chat: agentId={}, conversationId={}, user={}", agentId, conversationId, username);

        // ---- Workspace 边界校验：确保 agent 属于当前 workspace ----
        if (agentId != null) {
            AgentEntity agent = agentService.getAgent(agentId);
            if (agent != null && agent.getWorkspaceId() != null) {
                long wsId = workspaceId != null ? workspaceId : 1L;
                if (!agent.getWorkspaceId().equals(wsId)) {
                    log.warn("Chat workspace mismatch: agent {} belongs to workspace {}, request workspace {}",
                            agentId, agent.getWorkspaceId(), wsId);
                    try {
                        sendEvent(emitter, "error", Map.of("message", "Agent 不属于当前工作区"));
                        sendEvent(emitter, "done", Map.of("status", "completed"));
                    } catch (IOException e) {
                        log.warn("SSE workspace error send failed: {}", e.getMessage());
                    }
                    emitter.complete();
                    return emitter;
                }
            }
        }

        // ---- 审批命令拦截：/approve、/deny 走 SSE 流式 replay ----
        String normalizedMsg = message.trim().toLowerCase();
        boolean isApprovalCommand = "/approve".equals(normalizedMsg) || "approve".equals(normalizedMsg);
        boolean isDenyCommand = "/deny".equals(normalizedMsg) || "deny".equals(normalizedMsg);

        if (isApprovalCommand || isDenyCommand) {
            PendingApproval pending = approvalService.findPendingByConversation(conversationId);
            if (pending == null) {
                try {
                    sendEvent(emitter, "error", Map.of("message", "当前没有待审批的工具调用"));
                    sendEvent(emitter, "done", Map.of("status", "completed"));
                } catch (IOException e) { /* ignore */ }
                emitter.complete();
                return emitter;
            }

            // deny: 解决并清理 DB 残留
            if (isDenyCommand) {
                approvalService.resolve(pending.getPendingId(), username, "denied");
                conversationService.removeApprovalPlaceholders(conversationId);
                log.info("[Approval-Stream] User {} denied pending {} for conversation {}",
                        username, pending.getPendingId(), conversationId);
            }

            // approve: 原子 resolveAndConsume（消除 resolve/consume race condition）
            PendingApproval consumed = null;
            if (isApprovalCommand) {
                consumed = approvalService.resolveAndConsume(pending.getPendingId(), username);
                if (consumed == null) {
                    try {
                        sendEvent(emitter, "error", Map.of("message", "审批记录已过期或已被处理"));
                        sendEvent(emitter, "done", Map.of("status", "completed"));
                    } catch (IOException e2) { /* ignore */ }
                    emitter.complete();
                    return emitter;
                }
                // 清理 DB 中残留的审批占位消息（对齐 IM 渠道 replayApprovedToolCall）
                conversationService.removeApprovalPlaceholders(conversationId);
                log.info("[Approval-Stream] User {} approved pending {} for conversation {}",
                        username, consumed.getPendingId(), conversationId);
            }

            final PendingApproval finalConsumed = consumed;
            final String decision = isApprovalCommand ? "approved" : "denied";

            streamTracker.register(conversationId);
            registerEmitterCallbacks(emitter, conversationId);
            streamTracker.attach(conversationId, emitter);
            AtomicBoolean approvalEmitterDone = new AtomicBoolean(false);

            sseExecutor.execute(() -> {
                StreamAccumulator accumulator = new StreamAccumulator();
                AtomicBoolean finalized = new AtomicBoolean(false);
                try {
                    // 广播 approval_resolved 事件
                    broadcastEvent(conversationId, "tool_approval_resolved", Map.of(
                            "pendingId", pending.getPendingId(),
                            "decision", decision,
                            "toolName", pending.getToolName(),
                            "timestamp", System.currentTimeMillis()
                    ));

                    if ("denied".equals(decision)) {
                        String denyMsg = "用户拒绝执行工具 " + pending.getToolName();
                        MessageEntity savedAssistant = conversationService.saveMessage(conversationId, "assistant", denyMsg);
                        broadcastEvent(conversationId, "message_start", Map.of("role", "assistant"));
                        broadcastEvent(conversationId, "content_delta", Map.of("delta", denyMsg));
                        broadcastEvent(conversationId, "message_complete", Map.of("status", "completed"));
                        broadcastEvent(conversationId, "done", buildDonePayload(
                                conversationId, "completed", savedAssistant, 0, 0, true,
                                conversationService.getMessageCount(conversationId)));
                        // deny 是正常 turn 终结，用户可能在 awaiting_approval 阶段排了消息
                        ChatStreamTracker.CompletionResult denyCr = streamTracker.completeAndConsumeIfLast(conversationId);
                        if (denyCr.allDone() && denyCr.queuedInput() != null) {
                            startQueuedMessage(conversationId, emitter, approvalEmitterDone, denyCr.queuedInput(), username);
                        } else {
                            completeEmitterQuietly(emitter, approvalEmitterDone);
                        }
                        return;
                    }

                    // approved: 使用已原子消费的记录触发 replay 流
                    if (finalConsumed == null) {
                        broadcastEvent(conversationId, "error", Map.of("message", "审批记录已被消费"));
                        broadcastEvent(conversationId, "done", Map.of("status", "completed"));
                        // 审批记录被另一个请求消费，但用户可能在等待期间排了消息
                        ChatStreamTracker.CompletionResult consumedNullCr = streamTracker.completeAndConsumeIfLast(conversationId);
                        if (consumedNullCr.allDone() && consumedNullCr.queuedInput() != null) {
                            startQueuedMessage(conversationId, emitter, approvalEmitterDone, consumedNullCr.queuedInput(), username);
                        } else {
                            completeEmitterQuietly(emitter, approvalEmitterDone);
                        }
                        return;
                    }

                    Long replayAgentId = finalConsumed.getAgentId() != null
                            ? Long.parseLong(finalConsumed.getAgentId()) : agentId;

                    broadcastEvent(conversationId, "message_start", Map.of("role", "assistant"));

                    // 不含工具名的中性 prompt（对齐 IM 渠道，防止 fallthrough 时误导 LLM）
                    String replayPrompt = "继续执行已批准的工具调用。";

                    streamTracker.incrementFlux(conversationId);
                    Disposable disposable = agentService.chatWithReplayStream(
                            replayAgentId, replayPrompt, conversationId, finalConsumed.getToolCallPayload(), username)
                            .doOnNext(delta -> {
                                if (approvalEmitterDone.get()) return;
                                try {
                                    accumulator.accept(delta, conversationId);
                                } catch (Exception e) {
                                    log.warn("SSE replay broadcast error: {}", e.getMessage());
                                }
                            })
                            .doOnComplete(() -> {
                                if (!finalized.compareAndSet(false, true)) return;
                                try {
                                    MessageEntity savedAssistant = null;
                                    List<MessageContentPart> parts = accumulator.toAssistantParts();
                                    String text = accumulator.getContent();
                                    if (!text.isBlank() || !parts.isEmpty()) {
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant", text, parts,
                                                "completed",
                                                accumulator.getPromptTokens(),
                                                accumulator.getCompletionTokens(),
                                                accumulator.getRuntimeModelName(),
                                                accumulator.getRuntimeProviderId(),
                                                accumulator.toMetadataJson());  // 包含 toolCalls 元数据
                                    }
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "completed",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !text.isBlank()
                                    ));
                                    int msgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, "completed", savedAssistant, 0, 0, true, msgCount));
                                } catch (Exception e) {
                                    log.warn("SSE replay complete error: {}", e.getMessage());
                                } finally {
                                    ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                    if (cr.allDone()) {
                                        if (cr.queuedInput() != null) {
                                            startQueuedMessage(conversationId, emitter, approvalEmitterDone, cr.queuedInput(), username);
                                        } else {
                                            conversationService.updateStreamStatus(conversationId, "idle");
                                            completeEmitterQuietly(emitter, approvalEmitterDone);
                                        }
                                    }
                                }
                            })
                            .doOnError(e -> {
                                if (!finalized.compareAndSet(false, true)) return;

                                boolean isUserStop = e instanceof java.util.concurrent.CancellationException
                                        || (e.getCause() instanceof java.util.concurrent.CancellationException);
                                ChatStreamTracker.InterruptType replayInterruptType = streamTracker.getInterruptType(conversationId);
                                boolean replayIsFollowup = replayInterruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                                String errStatus = !isUserStop ? "failed"
                                        : replayIsFollowup ? "interrupted" : "stopped";

                                if (replayIsFollowup) {
                                    log.info("SSE replay stream interrupted for follow-up: conversationId={}", conversationId);
                                } else if (isUserStop) {
                                    log.info("SSE replay stream stopped by user: conversationId={}", conversationId);
                                } else {
                                    log.error("SSE replay error: {}", e.getMessage());
                                }

                                try {
                                    MessageEntity savedAssistant = null;
                                    List<MessageContentPart> replayParts = accumulator.toAssistantParts();
                                    String replayText = accumulator.getContent();
                                    if (!replayText.isBlank() || !replayParts.isEmpty()) {
                                        String savedText = replayText.isBlank() && isUserStop
                                                ? (replayIsFollowup ? "[已中断]" : "[已停止生成]") : replayText;
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, replayParts,
                                                errStatus,
                                                accumulator.getPromptTokens(),
                                                accumulator.getCompletionTokens(),
                                                accumulator.getRuntimeModelName(),
                                                accumulator.getRuntimeProviderId(),
                                                accumulator.toMetadataJson());
                                    } else if (isUserStop) {
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                                replayIsFollowup ? "[已中断]" : "[已停止生成]", null, errStatus);
                                    }

                                    if (replayIsFollowup) {
                                        broadcastEvent(conversationId, "message_complete", Map.of(
                                                "status", "interrupted",
                                                "hasThinking", !accumulator.getThinking().isBlank(),
                                                "hasContent", !replayText.isBlank()
                                        ));
                                        broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                                "conversationId", conversationId,
                                                "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                        ));
                                    } else if (isUserStop) {
                                        broadcastEvent(conversationId, "message_complete", Map.of(
                                                "status", "stopped",
                                                "hasThinking", !accumulator.getThinking().isBlank(),
                                                "hasContent", !replayText.isBlank()
                                        ));
                                        int stoppedMsgCount = conversationService.getMessageCount(conversationId);
                                        broadcastEvent(conversationId, "done", buildDonePayload(
                                                conversationId, "stopped", savedAssistant, 0, 0, true, stoppedMsgCount));
                                    } else {
                                        broadcastEvent(conversationId, "error", buildErrorPayload(
                                                conversationId,
                                                e.getMessage() != null ? e.getMessage() : "replay error",
                                                savedAssistant));
                                    }
                                } catch (Exception ex) {
                                    log.warn("SSE replay error finalize failed: {}", ex.getMessage());
                                }
                                streamTracker.clearInterruptState(conversationId);
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    if (cr.queuedInput() != null) {
                                        startQueuedMessage(conversationId, emitter, approvalEmitterDone, cr.queuedInput(), username);
                                    } else {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                        completeEmitterQuietly(emitter, approvalEmitterDone);
                                    }
                                }
                            })
                            .subscribe(
                                    chunk -> { },
                                    err -> log.debug("SSE replay subscription terminated: {}", err.getMessage()),
                                    () -> log.debug("SSE replay subscription completed: conversationId={}", conversationId));

                    streamTracker.setDisposable(conversationId, disposable);

                } catch (Exception e) {
                    log.error("SSE approval replay setup error: {}", e.getMessage());
                    streamTracker.complete(conversationId);
                    completeEmitterQuietly(emitter, approvalEmitterDone);
                }
            });
            return emitter;
        }

        // ---- 正常请求：注册流状态并附着首个订阅者 ----
        streamTracker.register(conversationId);
        registerEmitterCallbacks(emitter, conversationId);
        streamTracker.attach(conversationId, emitter);

        // 标记 emitter 是否已结束，防止 Flux 回调再次写入已关闭的 emitter
        AtomicBoolean emitterDone = new AtomicBoolean(false);

        sseExecutor.execute(() -> {
            StreamAccumulator accumulator = new StreamAccumulator();
            AtomicBoolean finalized = new AtomicBoolean(false);
            try {
                conversationService.getOrCreateConversation(conversationId, agentId, username, workspaceId);
                List<MessageContentPart> requestParts = normalizeRequestParts(request);
                String promptText = buildPromptText(message, requestParts);
                conversationService.saveMessage(conversationId, "user", message, requestParts);
                conversationService.updateStreamStatus(conversationId, "running");

                broadcastEvent(conversationId, "session", Map.of(
                        "conversationId", conversationId,
                        "agentId", agentId
                ));
                broadcastEvent(conversationId, "message_start", Map.of(
                        "role", "assistant"
                ));

                streamTracker.incrementFlux(conversationId);
                Disposable disposable = agentService.chatStructuredStream(agentId, promptText, conversationId, username, request.getThinkingLevel())
                        .doOnNext(delta -> {
                            if (emitterDone.get()) return;
                            try {
                                accumulator.accept(delta, conversationId);
                            } catch (Exception e) {
                                log.warn("SSE broadcast error: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            if (!finalized.compareAndSet(false, true)) return;
                            // 区分三种完成语义：
                            // 1. 正常完成（stopRequested=false）→ completed
                            // 2. 用户主动停止 → stopped
                            // 3. 用户中断后续跑（interrupt-with-followup）→ interrupted
                            boolean wasStopped = streamTracker.isStopRequested(conversationId);
                            ChatStreamTracker.InterruptType interruptType = streamTracker.getInterruptType(conversationId);
                            boolean isInterruptFollowup = interruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                            String persistStatus;
                            if (accumulator.isAwaitingApproval()) {
                                persistStatus = "awaiting_approval";
                            } else if (!wasStopped) {
                                persistStatus = "completed";
                            } else {
                                persistStatus = isInterruptFollowup ? "interrupted" : "stopped";
                            }
                            try {
                                MessageEntity savedAssistant = null;
                                List<MessageContentPart> assistantParts = accumulator.toAssistantParts();
                                String assistantText = accumulator.getContent();
                                if (!assistantText.isBlank() || !assistantParts.isEmpty()) {
                                    String savedText = assistantText.isBlank() && wasStopped
                                            ? (isInterruptFollowup ? "[已中断]" : "[已停止生成]") : assistantText;
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, assistantParts,
                                            persistStatus,
                                            accumulator.getPromptTokens(),
                                            accumulator.getCompletionTokens(),
                                            accumulator.getRuntimeModelName(),
                                            accumulator.getRuntimeProviderId(),
                                            accumulator.toMetadataJson());
                                } else if (wasStopped) {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                            isInterruptFollowup ? "[已中断]" : "[已停止生成]", null, persistStatus);
                                }
                                // 发布对话完成事件（仅正常完成时，停止/中断不触发记忆提取）
                                if (!wasStopped) {
                                    try {
                                        int msgCount = conversationService.getMessageCount(conversationId);
                                        eventPublisher.publishEvent(new ConversationCompletedEvent(
                                                agentId, conversationId, message, assistantText, msgCount, "web"));
                                    } catch (Exception ex) {
                                        log.debug("[Memory] Failed to publish ConversationCompletedEvent: {}", ex.getMessage());
                                    }
                                }

                                if (isInterruptFollowup) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "interrupted",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                            "conversationId", conversationId,
                                            "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                    ));
                                } else {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", persistStatus,
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    int msgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, persistStatus, savedAssistant,
                                            accumulator.getPromptTokens(), accumulator.getCompletionTokens(), true, msgCount));
                                }
                            } catch (Exception e) {
                                log.warn("SSE complete error: {}", e.getMessage());
                            } finally {
                                streamTracker.clearInterruptState(conversationId);
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    if (cr.queuedInput() != null && (isInterruptFollowup || !wasStopped)) {
                                        startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), username);
                                    } else {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                        // 延迟关闭 emitter，确保最后的事件都已发送
                                        sseExecutor.execute(() -> {
                                            try {
                                                Thread.sleep(100);
                                            } catch (InterruptedException ignored) {}
                                            completeEmitterQuietly(emitter, emitterDone);
                                        });
                                    }
                                } else {
                                    log.info("Original stream completed but replay still active, " +
                                            "keeping SSE emitter alive: conversationId={}", conversationId);
                                }
                            }
                        })
                        .doOnCancel(() -> {
                            boolean wasFirst = finalized.compareAndSet(false, true);
                            log.info("SSE doOnCancel fired: conversationId={}, wasFirst={}", conversationId, wasFirst);
                            if (!wasFirst) return;
                            // 区分用户主动停止和 interrupt-with-followup
                            ChatStreamTracker.InterruptType interruptType = streamTracker.getInterruptType(conversationId);
                            boolean isInterruptFollowup = interruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                            String status = isInterruptFollowup ? "interrupted" : "stopped";

                            log.info("SSE stream cancelled ({}): conversationId={}", status, conversationId);
                            try {
                                MessageEntity savedAssistant = null;
                                List<MessageContentPart> assistantParts = accumulator.toAssistantParts();
                                String assistantText = accumulator.getContent();
                                if (!assistantText.isBlank() || !assistantParts.isEmpty()) {
                                    String savedText = assistantText.isBlank()
                                            ? (isInterruptFollowup ? "[已中断]" : "[已停止生成]") : assistantText;
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, assistantParts,
                                            status,
                                            accumulator.getPromptTokens(),
                                            accumulator.getCompletionTokens(),
                                            accumulator.getRuntimeModelName(),
                                            accumulator.getRuntimeProviderId(),
                                            accumulator.toMetadataJson());
                                } else {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                            isInterruptFollowup ? "[已中断]" : "[已停止生成]", null, status);
                                }

                                if (isInterruptFollowup) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "interrupted",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                            "conversationId", conversationId,
                                            "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                    ));
                                } else {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "stopped",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    int stoppedMsgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, "stopped", savedAssistant, 0, 0, true, stoppedMsgCount));
                                }
                            } catch (Exception e) {
                                log.warn("SSE stop finalize error: {}", e.getMessage());
                            } finally {
                                streamTracker.clearInterruptState(conversationId);
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    if (cr.queuedInput() != null) {
                                        // 无论中断类型，都消费排队消息（修复 Disposable 不可用时队列被丢弃的 bug）
                                        startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), username);
                                    } else {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                        completeEmitterQuietly(emitter, emitterDone);
                                    }
                                }
                            }
                        })
                        .doOnError(e -> {
                            boolean wasFirst = finalized.compareAndSet(false, true);
                            if (!wasFirst) {
                                log.info("SSE doOnError skipped (finalized by doOnCancel): conversationId={}", conversationId);
                                return;
                            }

                            // CancellationException = 用户主动停止或中断续跑
                            boolean isUserStop = e instanceof java.util.concurrent.CancellationException
                                    || (e.getCause() instanceof java.util.concurrent.CancellationException);
                            ChatStreamTracker.InterruptType interruptType = streamTracker.getInterruptType(conversationId);
                            boolean isInterruptFollowup = interruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                            // 三态：interrupted > stopped > failed
                            String status = !isUserStop ? "failed"
                                    : isInterruptFollowup ? "interrupted" : "stopped";

                            if (isInterruptFollowup) {
                                log.info("SSE stream interrupted for follow-up (CancellationException): conversationId={}", conversationId);
                            } else if (isUserStop) {
                                log.info("SSE stream stopped by user (CancellationException): conversationId={}", conversationId);
                            } else if (isClientDisconnect(e)) {
                                log.warn("SSE client disconnected: conversationId={}, cause={}", conversationId, e.getMessage());
                            } else {
                                log.error("SSE stream error: conversationId={}, cause={}", conversationId, e.getMessage());
                            }

                            try {
                                List<MessageContentPart> assistantParts = accumulator.toAssistantParts();
                                String assistantText = accumulator.getContent();
                                log.info("SSE doOnError saving: conversationId={}, status={}, textLen={}, partsCount={}",
                                        conversationId, status, assistantText.length(), assistantParts.size());
                                String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown error";
                                MessageEntity savedAssistant = null;
                                if (!assistantText.isBlank() || !assistantParts.isEmpty()) {
                                    String savedText = assistantText.isBlank() && isUserStop
                                            ? (isInterruptFollowup ? "[已中断]" : "[已停止生成]") : assistantText;
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, assistantParts,
                                            status,
                                            accumulator.getPromptTokens(),
                                            accumulator.getCompletionTokens(),
                                            accumulator.getRuntimeModelName(),
                                            accumulator.getRuntimeProviderId(),
                                            accumulator.toMetadataJson());
                                } else if (isUserStop) {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                            isInterruptFollowup ? "[已中断]" : "[已停止生成]", null, status);
                                } else {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", "[错误] " + errorMsg, null, "failed");
                                }

                                if (isInterruptFollowup) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "interrupted",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                            "conversationId", conversationId,
                                            "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                    ));
                                } else if (isUserStop) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "stopped",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    int stoppedMsgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, "stopped", savedAssistant, 0, 0, true, stoppedMsgCount));
                                } else {
                                    broadcastEvent(conversationId, "error", buildErrorPayload(conversationId, errorMsg, savedAssistant));
                                }
                            } catch (Exception ioException) {
                                log.error("SSE doOnError save/broadcast failed: conversationId={}, error={}",
                                        conversationId, ioException.getMessage(), ioException);
                            }
                            streamTracker.clearInterruptState(conversationId);
                            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                            log.info("SSE doOnError cleanup: conversationId={}, allDone={}, isInterruptFollowup={}, hasQueued={}",
                                    conversationId, cr.allDone(), isInterruptFollowup, cr.queuedInput() != null);
                            if (cr.allDone()) {
                                // 修复：非用户主动停止时也消费排队消息
                                // isUserStop && !isInterruptFollowup = 用户点了 Stop，不应续跑
                                boolean userExplicitStop = isUserStop && !isInterruptFollowup;
                                if (cr.queuedInput() != null && !userExplicitStop) {
                                    startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), username);
                                } else {
                                    // 即使不续跑，如果有排队消息也要持久化用户消息（防丢失，幂等）
                                    if (cr.queuedInput() != null && !cr.queuedInput().persisted()) {
                                        conversationService.saveMessage(conversationId, "user",
                                                cr.queuedInput().message(), null, "queued");
                                    }
                                    conversationService.updateStreamStatus(conversationId, "idle");
                                    completeEmitterQuietly(emitter, emitterDone);
                                }
                            }
                        })
                        .subscribe(
                                chunk -> { },
                                error -> log.debug("SSE stream subscription terminated with error: {}", error.getMessage()),
                                () -> log.debug("SSE stream subscription completed: conversationId={}", conversationId));

                // 将 Disposable 注册到 StreamTracker，以便 stop 端点可以取消它
                streamTracker.setDisposable(conversationId, disposable);

            } catch (Exception e) {
                log.error("SSE setup error: {}", e.getMessage());
                try {
                    broadcastEvent(conversationId, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "unknown error"));
                } catch (Exception ioException) {
                    log.warn("SSE setup failure event broadcast error: {}", ioException.getMessage());
                }
                streamTracker.complete(conversationId);
                conversationService.updateStreamStatus(conversationId, "idle");
                completeEmitterQuietly(emitter, emitterDone);
            }
        });

        return emitter;
    }

    /**
     * 停止指定会话的流式生成。
     * 取消 Flux 订阅（底层 HTTP 连接也会随之关闭），已生成的部分内容以 stopped 状态入库。
     */
    @Operation(summary = "停止流式生成")
    @PostMapping("/{conversationId}/stop")
    public R<Map<String, Boolean>> stopStream(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        // 权限校验：已认证用户需验证会话归属，匿名用户（permitAll）直接放行
        if (auth != null && !conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权操作该会话");
        }
        boolean stopped = streamTracker.requestStop(conversationId);
        log.info("Stop requested: conversationId={}, user={}, stopped={}", conversationId, username, stopped);
        return R.ok(Map.of("stopped", stopped));
    }

    /**
     * 中断当前流并排队一条后续消息。
     * <p>
     * 与 stop 的区别：interrupt 会在当前 turn 安全结束后自动启动排队消息。
     * 如果当前阶段不可中断（awaiting_approval），消息会被排队但不打断当前执行。
     */
    @Operation(summary = "中断并排队后续消息")
    @PostMapping("/{conversationId}/interrupt")
    public R<Map<String, Object>> interruptStream(
            @PathVariable String conversationId,
            @RequestBody InterruptRequest request,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (auth != null && !conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权操作该会话");
        }

        if (!streamTracker.isRunning(conversationId)) {
            return R.ok(Map.of("interrupted", false, "reason", "no_active_stream"));
        }

        String message = request.getMessage();
        Long agentId = request.getAgentId();
        List<MessageContentPart> contentParts = request.getContentParts();

        // 判断当前阶段是否可中断
        // awaiting_approval 阶段不直接中断，只排队
        boolean isAwaitingApproval = approvalService.findPendingByConversation(conversationId) != null;

        if (isAwaitingApproval) {
            // 不可中断：排队但不打断。先持久化（含 contentParts）再入队（persisted=true）
            conversationService.saveMessage(conversationId, "user", message, contentParts, "queued");
            boolean queued = streamTracker.enqueueMessage(conversationId, message, agentId, true);
            log.info("Interrupt requested during approval, message queued: conversationId={}, user={}, queueSize={}",
                    conversationId, username, streamTracker.getQueueSize(conversationId));
            return R.ok(Map.of(
                    "interrupted", false,
                    "queued", queued,
                    "reason", "awaiting_approval"
            ));
        }

        // 可中断：先持久化（含 contentParts）再打断并入队（persisted=true）
        conversationService.saveMessage(conversationId, "user", message, contentParts, "queued");
        boolean interrupted = streamTracker.requestInterrupt(conversationId, message, agentId, true);
        log.info("Interrupt requested: conversationId={}, user={}, interrupted={}, queueSize={}",
                conversationId, username, interrupted, streamTracker.getQueueSize(conversationId));

        return R.ok(Map.of(
                "interrupted", interrupted,
                "queued", true,
                "queueSize", streamTracker.getQueueSize(conversationId),
                "reason", interrupted ? "interrupted" : "queued"
        ));
    }

    @lombok.Data
    public static class InterruptRequest {
        private String message;
        private Long agentId;
        /** 结构化内容片段（含图片等附件），排队消息带附件时由前端传入 */
        private List<MessageContentPart> contentParts;
    }

    /**
     * 同步对话
     */
    @Operation(summary = "同步对话")
    @PostMapping
    public R<String> chat(
            @RequestParam Long agentId,
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {

        String username = auth != null ? auth.getName() : null;
        if (username == null) {
            return R.fail("未登录，请先登录");
        }
        conversationService.getOrCreateConversation(request.getConversationId(), agentId, username, workspaceId);
        conversationService.saveMessage(request.getConversationId(), "user", request.getMessage(), request.getContentParts());

        String promptText = buildPromptText(request.getMessage(), request.getContentParts());
        String response = agentService.chat(agentId, promptText, request.getConversationId());
        conversationService.saveMessage(request.getConversationId(), "assistant", response);
        // 发布对话完成事件
        try {
            int msgCount = conversationService.getMessageCount(request.getConversationId());
            eventPublisher.publishEvent(new ConversationCompletedEvent(
                    agentId, request.getConversationId(), request.getMessage(), response, msgCount, "web"));
        } catch (Exception ex) {
            log.debug("[Memory] Failed to publish ConversationCompletedEvent: {}", ex.getMessage());
        }
        return R.ok(response);
    }

    @Operation(summary = "上传聊天附件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ChatUploadResponse> upload(
            @RequestParam String conversationId,
            @RequestPart("file") MultipartFile file,
            Authentication auth) throws IOException {

        String username = auth != null ? auth.getName() : "anonymous";
        // 校验会话归属（会话可能尚未创建，此时允许上传——后续 stream/chat 会创建并绑定用户）
        if (conversationService.conversationExists(conversationId)
                && !conversationService.isConversationOwner(conversationId, username)) {
            return R.fail("无权操作该会话");
        }
        if (file.isEmpty()) {
            return R.fail("上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String safeFilename = Path.of(originalFilename).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedName = System.currentTimeMillis() + "_" + safeFilename;
        Path conversationDir = uploadRoot.resolve(conversationId);
        Files.createDirectories(conversationDir);
        Path target = conversationDir.resolve(storedName);
        file.transferTo(target);

        log.info("Chat attachment uploaded: conversationId={}, user={}, file={}", conversationId, username, target);

        ChatUploadResponse response = new ChatUploadResponse();
        response.setConversationId(conversationId);
        response.setFileName(originalFilename);
        response.setStoredName(storedName);
        response.setUrl("/api/v1/chat/files/" + conversationId + "/" + storedName);
        // 使用相对路径，避免暴露服务端绝对路径
        response.setPath(uploadRoot.resolve(conversationId).resolve(storedName).toString());
        response.setSize(file.getSize());
        response.setContentType(file.getContentType());
        return R.ok(response);
    }

    @Operation(summary = "读取聊天附件")
    @GetMapping("/files/{conversationId}/{storedName:.+}")
    public ResponseEntity<Resource> readUploadedFile(
            @PathVariable String conversationId,
            @PathVariable String storedName,
            Authentication auth) throws IOException {

        // 校验当前用户拥有该会话
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return ResponseEntity.status(403).build();
        }

        Path filePath = uploadRoot.resolve(conversationId).resolve(storedName).normalize();
        if (!Files.exists(filePath) || !filePath.startsWith(uploadRoot.resolve(conversationId).normalize())) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(filePath);
        String contentType = Files.probeContentType(filePath);
        // probeContentType 在部分平台不识别视频格式，通过扩展名 fallback
        if (contentType == null) {
            contentType = guessContentTypeByExtension(filePath.getFileName().toString());
        }
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (contentType != null) {
            try {
                mediaType = MediaType.parseMediaType(contentType);
            } catch (Exception ignored) {
            }
        }

        String encodedFilename = URLEncoder.encode(filePath.getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }

    @lombok.Data
    public static class ChatRequest {
        private String message;
        private String conversationId = "default";
        private List<MessageContentPart> contentParts;
    }

    @lombok.Data
    public static class ChatUploadResponse {
        private String conversationId;
        private String fileName;
        private String storedName;
        private String url;
        private String path;
        private Long size;
        private String contentType;
    }

    @lombok.Data
    public static class ChatStreamRequest {
        private Long agentId;
        private String message;
        private String conversationId = "default";
        private List<MessageContentPart> contentParts;
        /** true 表示断线重连，不发送新消息，只附着到已有的流 */
        private Boolean reconnect;
        /** 思考深度：off / low / medium / high / max，null 表示跟随 Agent 默认 */
        private String thinkingLevel;
    }

    /**
     * 自动启动排队消息（interrupt-with-followup 或自然完成后的续跑逻辑）。
     * 接受由 {@link ChatStreamTracker#completeAndConsumeIfLast} 预先消费的 QueuedInput 快照。
     * 快照已脱离 RunState 生命周期，不受后续 complete/register 影响。
     * 支持链式续跑：queued stream 自身完成时也通过 completeAndConsumeIfLast 检查并递归调用。
     */
    private void startQueuedMessage(String conversationId, SseEmitter emitter, AtomicBoolean emitterDone,
                                    ChatStreamTracker.QueuedInput preConsumedInput, String requesterId) {
        if (preConsumedInput == null) {
            conversationService.updateStreamStatus(conversationId, "idle");
            completeEmitterQuietly(emitter, emitterDone);
            return;
        }

        // Rate Limit 防护：如果上一轮以 rate limit 错误结束，不立即续跑排队消息（必然再次 429）。
        // 改为持久化用户消息 + 通知前端"稍后重试"，避免连锁 429 浪费配额。
        String lastMessage = conversationService.getLastMessage(conversationId);
        if (lastMessage != null && (lastMessage.contains("频率过高") || lastMessage.contains("rate_limit")
                || lastMessage.contains("429") || lastMessage.contains("速率限制"))) {
            log.warn("Skipping queued message after rate limit error: conversationId={}, lastMessage={}",
                    conversationId, lastMessage.substring(0, Math.min(50, lastMessage.length())));
            // 持久化用户消息不丢失
            if (preConsumedInput.message() != null && !preConsumedInput.message().isBlank()
                    && !preConsumedInput.persisted()) {
                conversationService.saveMessage(conversationId, "user", preConsumedInput.message());
            }
            broadcastEvent(conversationId, "warning", Map.of(
                    "message", "上一轮请求触发了频率限制，排队消息已保存，请稍后重新发送"));
            broadcastEvent(conversationId, "done", Map.of("status", "rate_limited"));
            conversationService.updateStreamStatus(conversationId, "idle");
            completeEmitterQuietly(emitter, emitterDone);
            return;
        }

        String queuedMessage = preConsumedInput.message();
        Long agentId = preConsumedInput.agentId() != null ? preConsumedInput.agentId() : 1L;
        log.info("Starting queued message: conversationId={}, agentId={}, message={}",
                conversationId, agentId, queuedMessage.substring(0, Math.min(30, queuedMessage.length())));

        // 持久化排队的用户消息（幂等：如果 /interrupt 已提前持久化则跳过）
        if (queuedMessage != null && !queuedMessage.isBlank() && !preConsumedInput.persisted()) {
            conversationService.saveMessage(conversationId, "user", queuedMessage);
        }

        // 广播 queued_input_started 事件
        broadcastEvent(conversationId, "queued_input_started", Map.of(
                "conversationId", conversationId,
                "message", queuedMessage
        ));

        // 重新注册流状态
        streamTracker.register(conversationId);
        streamTracker.attach(conversationId, emitter);

        // 启动新的流（复用现有 sseExecutor.execute 的逻辑模式）
        StreamAccumulator accumulator = new StreamAccumulator();
        AtomicBoolean finalized = new AtomicBoolean(false);

        broadcastEvent(conversationId, "message_start", Map.of("role", "assistant"));

        streamTracker.incrementFlux(conversationId);
        Disposable disposable = agentService.chatStructuredStream(agentId, queuedMessage, conversationId, requesterId)
                .doOnNext(delta -> {
                    if (emitterDone.get()) return;
                    try {
                        accumulator.accept(delta, conversationId);
                    } catch (Exception e) {
                        log.warn("SSE queued broadcast error: {}", e.getMessage());
                    }
                })
                .doOnComplete(() -> {
                    if (!finalized.compareAndSet(false, true)) return;
                    try {
                        MessageEntity savedAssistant = null;
                        List<MessageContentPart> parts = accumulator.toAssistantParts();
                        String text = accumulator.getContent();
                        if (!text.isBlank() || !parts.isEmpty()) {
                            savedAssistant = conversationService.saveMessage(conversationId, "assistant", text, parts,
                                    "completed",
                                    accumulator.getPromptTokens(),
                                    accumulator.getCompletionTokens(),
                                    accumulator.getRuntimeModelName(),
                                    accumulator.getRuntimeProviderId(),
                                    accumulator.toMetadataJson());
                        }
                        broadcastEvent(conversationId, "message_complete", Map.of(
                                "status", "completed",
                                "hasThinking", !accumulator.getThinking().isBlank(),
                                "hasContent", !text.isBlank()
                        ));
                        broadcastEvent(conversationId, "done", buildDonePayload(
                                conversationId, "completed", savedAssistant,
                                accumulator.getPromptTokens(), accumulator.getCompletionTokens(), true,
                                conversationService.getMessageCount(conversationId)));
                    } catch (Exception e) {
                        log.warn("SSE queued complete error: {}", e.getMessage());
                    } finally {
                        ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                        if (cr.allDone()) {
                            if (cr.queuedInput() != null) {
                                // 链式续跑：queued stream 期间又排了新消息
                                startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), requesterId);
                            } else {
                                conversationService.updateStreamStatus(conversationId, "idle");
                                sseExecutor.execute(() -> {
                                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                                    completeEmitterQuietly(emitter, emitterDone);
                                });
                            }
                        }
                    }
                })
                .doOnError(e -> {
                    if (!finalized.compareAndSet(false, true)) return;
                    log.error("SSE queued stream error: conversationId={}, cause={}", conversationId, e.getMessage());
                    // 持久化已累积的 assistant 消息（修复：原逻辑未保存导致回答丢失）
                    try {
                        MessageEntity savedAssistant = null;
                        List<MessageContentPart> parts = accumulator.toAssistantParts();
                        String text = accumulator.getContent();
                        if (!text.isBlank() || !parts.isEmpty()) {
                            savedAssistant = conversationService.saveMessage(conversationId, "assistant", text, parts,
                                    "failed",
                                    accumulator.getPromptTokens(),
                                    accumulator.getCompletionTokens(),
                                    accumulator.getRuntimeModelName(),
                                    accumulator.getRuntimeProviderId(),
                                    accumulator.toMetadataJson());
                        } else {
                            String errorMsg = e.getMessage() != null ? e.getMessage() : "queued stream error";
                            savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                    "[错误] " + errorMsg, null, "failed");
                        }
                        broadcastEvent(conversationId, "error", buildErrorPayload(
                                conversationId,
                                e.getMessage() != null ? e.getMessage() : "queued stream error",
                                savedAssistant));
                    } catch (Exception saveEx) {
                        log.error("SSE queued doOnError save failed: {}", saveEx.getMessage());
                    }
                    ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                    if (cr.allDone()) {
                        if (cr.queuedInput() != null) {
                            startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), requesterId);
                        } else {
                            conversationService.updateStreamStatus(conversationId, "idle");
                            completeEmitterQuietly(emitter, emitterDone);
                        }
                    }
                })
                .subscribe();
        streamTracker.setDisposable(conversationId, disposable);
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) throws IOException {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            payload = "{\"message\":\"serialization_error\"}";
        }
        emitter.send(SseEmitter.event().name(name).data(payload));
    }

    private void broadcastEvent(String conversationId, String name, Object data) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            payload = "{\"message\":\"serialization_error\"}";
        }
        streamTracker.broadcast(conversationId, name, payload);
    }

    private Map<String, Object> buildDonePayload(String conversationId, String status, MessageEntity savedAssistant,
                                                 int promptTokens, int completionTokens,
                                                 boolean persisted, Integer messageCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (conversationId != null && !conversationId.isBlank()) payload.put("conversationId", conversationId);
        payload.put("status", status);
        if (savedAssistant != null && savedAssistant.getId() != null) {
            payload.put("assistantMessageId", savedAssistant.getId());
        }
        if (promptTokens > 0) payload.put("promptTokens", promptTokens);
        if (completionTokens > 0) payload.put("completionTokens", completionTokens);
        payload.put("persisted", persisted);
        if (messageCount != null) payload.put("messageCount", messageCount);
        return payload;
    }

    private Map<String, Object> buildErrorPayload(String conversationId, String message, MessageEntity savedAssistant) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        if (conversationId != null && !conversationId.isBlank()) payload.put("conversationId", conversationId);
        if (savedAssistant != null && savedAssistant.getId() != null) {
            payload.put("assistantMessageId", savedAssistant.getId());
        }
        return payload;
    }

    private List<MessageContentPart> normalizeRequestParts(ChatStreamRequest request) {
        if (request.getContentParts() != null && !request.getContentParts().isEmpty()) {
            return request.getContentParts();
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return List.of();
        }
        MessageContentPart textPart = new MessageContentPart();
        textPart.setType("text");
        textPart.setText(request.getMessage());
        return List.of(textPart);
    }

    private String buildPromptText(String message, List<MessageContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return message != null ? message : "";
        }
        StringBuilder builder = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null || part.getType() == null) {
                continue;
            }
            switch (part.getType()) {
                case "text", "thinking" -> appendPromptLine(builder, part.getText());
                case "file" -> appendPromptLine(builder, "附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
                case "image" -> appendPromptLine(builder, "图片附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
                case "video" -> appendPromptLine(builder, "视频附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
                default -> appendPromptLine(builder, part.getText());
            }
        }
        return builder.toString().trim();
    }

    private void appendPromptLine(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private static final java.util.Map<String, String> MEDIA_CONTENT_TYPES = java.util.Map.of(
            "mp4", "video/mp4", "webm", "video/webm", "mov", "video/quicktime",
            "avi", "video/x-msvideo", "mkv", "video/x-matroska", "mpeg", "video/mpeg",
            "mp3", "audio/mpeg", "wav", "audio/wav", "ogg", "audio/ogg"
    );

    private static String guessContentTypeByExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return MEDIA_CONTENT_TYPES.get(fileName.substring(dot + 1).toLowerCase());
    }

    /**
     * 注册 SseEmitter 的完整生命周期回调
     */
    private void registerEmitterCallbacks(SseEmitter emitter, String conversationId) {
        emitter.onCompletion(() ->
                log.debug("SSE emitter completed: conversationId={}", conversationId));
        emitter.onTimeout(() -> {
            log.debug("SSE emitter timeout: conversationId={}", conversationId);
            streamTracker.detach(conversationId, emitter);
            // 超时后显式 complete，防止 servlet 容器再抛 AsyncRequestTimeoutException
            emitter.complete();
        });
        emitter.onError(e -> {
            if (isClientDisconnect(e)) {
                log.debug("SSE client disconnected: conversationId={}, cause={}", conversationId, e.getMessage());
            } else {
                log.warn("SSE emitter error: conversationId={}, cause={}", conversationId, e.getMessage());
            }
            streamTracker.detach(conversationId, emitter);
        });
    }

    /**
     * 安全地完成 emitter，防止重复调用和已关闭连接引发的异常
     */
    private void completeEmitterQuietly(SseEmitter emitter, AtomicBoolean emitterDone) {
        if (!emitterDone.compareAndSet(false, true)) return;
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("Emitter already completed: {}", e.getMessage());
        }
    }

    /**
     * 判断异常是否为客户端断开连接（broken pipe、connection reset 等）
     */
    private boolean isClientDisconnect(Throwable e) {
        if (e instanceof IOException) return true;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("broken pipe") || lower.contains("connection reset")
                || lower.contains("client abort") || lower.contains("closed");
    }

    /**
     * 流式累积器 — 收集 StreamDelta 事件，持久化到 DB。
     * <p>
     * 维护两份数据：
     * <ul>
     *   <li>{@code toolCalls} — 兼容旧逻辑（执行面板等 UI 使用）</li>
     *   <li>{@code segments} — 按事件到达顺序记录的有序时间线（前端分段渲染用）</li>
     * </ul>
     * 两份数据从同一事件流构建，保证一致。segments 保留了 thinking → tools → content
     * 的真实交错顺序，toolCalls 是 segments 中 tool_call 类型的平铺视图。
     */
    private final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final List<Map<String, Object>> toolCalls = new ArrayList<>();
        /** 有序事件时间线 — 前端分段渲染的权威数据源 */
        private final List<Map<String, Object>> segments = new ArrayList<>();
        private final List<Map<String, Object>> browserActions = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<Map<String, Object>> planStepResults = new ArrayList<>();
        private int segCounter = 0;
        private int promptTokens = 0;
        private int completionTokens = 0;
        private String runtimeModelName = "";
        private String runtimeProviderId = "";
        private boolean awaitingApproval = false;
        private String currentPhase = "";
        private Long planId = null;
        private List<String> planSteps = List.of();
        private Integer currentPlanStep = null;
        private Map<String, Object> pendingApproval = null;

        synchronized void accept(AgentService.StreamDelta delta, String conversationId) {
            if (delta == null) return;

            if (delta.isEvent()) {
                if ("_usage_final".equals(delta.eventType())) {
                    Map<String, Object> data = delta.eventData();
                    promptTokens = ((Number) data.getOrDefault("promptTokens", 0)).intValue();
                    completionTokens = ((Number) data.getOrDefault("completionTokens", 0)).intValue();
                    runtimeModelName = String.valueOf(data.getOrDefault("runtimeModelName", ""));
                    runtimeProviderId = String.valueOf(data.getOrDefault("runtimeProviderId", ""));
                    return;
                }
                if ("phase".equals(delta.eventType())) {
                    String phase = String.valueOf(delta.eventData().getOrDefault("phase", ""));
                    if (!phase.isBlank()) {
                        currentPhase = phase;
                        streamTracker.updatePhase(conversationId, phase);
                        // phase 切换时关闭 running 的 content/thinking segment，保留边界
                        finalizeRunningSegments("content", "thinking");
                    }
                }
                accumulateToolEvent(delta.eventType(), delta.eventData(), conversationId);
                try {
                    broadcastEvent(conversationId, delta.eventType(), delta.eventData());
                } catch (Exception e) {
                    log.warn("Failed to broadcast event {}: {}", delta.eventType(), e.getMessage());
                }
                return;
            }

            // content_delta
            if (delta.content() != null && !delta.content().isBlank()) {
                content.append(delta.content());
                streamTracker.updatePhase(conversationId, "drafting_answer");
                if (!delta.persistenceOnly()) {
                    broadcastEvent(conversationId, "content_delta", Map.of("delta", delta.content()));
                }
                // segments: 追加到当前 running content segment，或创建新的
                var seg = findLastRunning("content");
                if (seg != null) {
                    seg.put("text", seg.getOrDefault("text", "") + delta.content());
                } else {
                    finalizeRunningSegments("thinking");
                    var s = newSegment("content");
                    s.put("text", delta.content());
                    segments.add(s);
                }
            }

            // thinking_delta
            if (delta.thinking() != null && !delta.thinking().isBlank()) {
                thinking.append(delta.thinking());
                if (!delta.persistenceOnly()) {
                    broadcastEvent(conversationId, "thinking_delta", Map.of("delta", delta.thinking()));
                }
                var seg = findLastRunning("thinking");
                if (seg != null) {
                    seg.put("thinkingText", seg.getOrDefault("thinkingText", "") + delta.thinking());
                } else {
                    var s = newSegment("thinking");
                    s.put("thinkingText", delta.thinking());
                    segments.add(s);
                }
            }
        }

        boolean isAwaitingApproval() { return awaitingApproval; }

        private void accumulateToolEvent(String eventType, Map<String, Object> data, String conversationId) {
            if ("tool_approval_requested".equals(eventType)) {
                awaitingApproval = true;
                currentPhase = "awaiting_approval";
                pendingApproval = new LinkedHashMap<>();
                pendingApproval.put("pendingId", data.getOrDefault("pendingId", ""));
                pendingApproval.put("toolName", data.getOrDefault("toolName", ""));
                pendingApproval.put("arguments", data.getOrDefault("arguments", ""));
                pendingApproval.put("reason", data.getOrDefault("reason", ""));
                pendingApproval.put("status", "pending_approval");
                if (data.containsKey("findings")) pendingApproval.put("findings", data.get("findings"));
                if (data.containsKey("maxSeverity")) pendingApproval.put("maxSeverity", data.get("maxSeverity"));
                if (data.containsKey("summary")) pendingApproval.put("summary", data.get("summary"));
                streamTracker.updatePhase(conversationId, "awaiting_approval");
            } else if ("tool_approval_resolved".equals(eventType)) {
                if (pendingApproval != null) {
                    pendingApproval.put("status",
                            "approved".equals(String.valueOf(data.getOrDefault("decision", ""))) ? "approved" : "denied");
                }
            } else if ("plan_created".equals(eventType)) {
                Object rawPlanId = data.get("planId");
                if (rawPlanId instanceof Number n) {
                    planId = n.longValue();
                } else if (rawPlanId != null) {
                    try { planId = Long.valueOf(String.valueOf(rawPlanId)); } catch (Exception ignored) {}
                }
                Object steps = data.get("steps");
                if (steps instanceof List<?> list) {
                    planSteps = list.stream().map(String::valueOf).toList();
                    planStepResults.clear();
                    for (int i = 0; i < planSteps.size(); i++) {
                        planStepResults.add(null);
                    }
                }
                currentPlanStep = 0;
            } else if ("plan_step_started".equals(eventType)) {
                Object idx = data.get("index");
                if (idx instanceof Number n) {
                    currentPlanStep = n.intValue();
                }
            } else if ("plan_step_completed".equals(eventType)) {
                Object idx = data.get("index");
                if (idx instanceof Number n) {
                    int index = n.intValue();
                    currentPlanStep = index;
                    ensurePlanStepCapacity(index + 1);
                    Map<String, Object> stepResult = new LinkedHashMap<>();
                    stepResult.put("result", data.getOrDefault("result", ""));
                    stepResult.put("status", "completed");
                    planStepResults.set(index, stepResult);
                }
            } else if ("browser_action".equals(eventType)) {
                browserActions.add(new LinkedHashMap<>(data));
            } else if ("warning".equals(eventType)) {
                String warning = String.valueOf(data.getOrDefault("message",
                        data.getOrDefault("delta", "")));
                if (!warning.isBlank()) {
                    warnings.add(warning);
                }
            } else if ("tool_call_started".equals(eventType)) {
                // toolCalls（兼容）
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("name", data.getOrDefault("toolName", ""));
                tc.put("arguments", data.getOrDefault("arguments", ""));
                tc.put("status", "running");
                toolCalls.add(tc);
                // segments: 关闭 running thinking/content，插入 tool_call
                finalizeRunningSegments("thinking", "content");
                var seg = newSegment("tool_call");
                seg.put("toolName", data.getOrDefault("toolName", ""));
                seg.put("toolArgs", data.getOrDefault("arguments", ""));
                segments.add(seg);
            } else if ("tool_call_completed".equals(eventType)) {
                String toolName = String.valueOf(data.getOrDefault("toolName", ""));
                // toolCalls（兼容）
                for (int i = toolCalls.size() - 1; i >= 0; i--) {
                    Map<String, Object> tc = toolCalls.get(i);
                    if ("running".equals(tc.get("status")) && toolName.equals(tc.get("name"))) {
                        tc.put("result", data.getOrDefault("result", ""));
                        tc.put("success", data.getOrDefault("success", true));
                        tc.put("status", "completed");
                        break;
                    }
                }
                // segments: 标记对应 tool_call 完成
                for (int i = segments.size() - 1; i >= 0; i--) {
                    var seg = segments.get(i);
                    if ("tool_call".equals(seg.get("type")) && "running".equals(seg.get("status"))
                            && toolName.equals(seg.get("toolName"))) {
                        seg.put("status", "completed");
                        seg.put("toolResult", data.getOrDefault("result", ""));
                        seg.put("toolSuccess", data.getOrDefault("success", true));
                        break;
                    }
                }
            }
        }

        private void ensurePlanStepCapacity(int size) {
            while (planStepResults.size() < size) {
                planStepResults.add(null);
            }
        }

        // ==================== Segment helpers ====================

        private Map<String, Object> newSegment(String type) {
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("id", type.substring(0, 2) + "-" + segCounter++);
            seg.put("type", type);
            seg.put("status", "running");
            return seg;
        }

        private Map<String, Object> findLastRunning(String type) {
            for (int i = segments.size() - 1; i >= 0; i--) {
                var seg = segments.get(i);
                if (type.equals(seg.get("type")) && "running".equals(seg.get("status"))) return seg;
            }
            return null;
        }

        private void finalizeRunningSegments(String... types) {
            var typeSet = java.util.Set.of(types);
            for (var seg : segments) {
                if ("running".equals(seg.get("status")) && typeSet.contains(seg.get("type"))) {
                    seg.put("status", "completed");
                }
            }
        }

        // ==================== 原有访问器 ====================

        String getContent() { return content.toString().trim(); }
        String getThinking() { return thinking.toString().trim(); }
        int getPromptTokens() { return promptTokens; }
        int getCompletionTokens() { return completionTokens; }
        String getRuntimeModelName() { return runtimeModelName; }
        String getRuntimeProviderId() { return runtimeProviderId; }

        synchronized List<MessageContentPart> toAssistantParts() {
            List<MessageContentPart> parts = new ArrayList<>();
            if (!getContent().isBlank()) {
                MessageContentPart textPart = new MessageContentPart();
                textPart.setType("text");
                textPart.setText(getContent());
                parts.add(textPart);
            }
            if (!getThinking().isBlank()) {
                MessageContentPart thinkingPart = new MessageContentPart();
                thinkingPart.setType("thinking");
                thinkingPart.setText(getThinking());
                parts.add(thinkingPart);
            }
            for (Map<String, Object> tc : toolCalls) {
                try {
                    parts.add(MessageContentPart.toolCall(objectMapper.writeValueAsString(tc)));
                } catch (Exception e) {
                    log.warn("Failed to serialize tool call: {}", e.getMessage());
                }
            }
            return parts;
        }

        void finalizeToolCalls() {
            for (Map<String, Object> tc : toolCalls) {
                if ("running".equals(tc.get("status"))) tc.put("status", "completed");
            }
        }

        /**
         * 生成 metadata JSON：包含 toolCalls + segments。
         * toolCalls 保留兼容旧 UI，segments 是按事件顺序的完整时间线。
         */
        synchronized String toMetadataJson() {
            finalizeToolCalls();
            finalizeRunningSegments("thinking", "content", "tool_call");
            try {
                Map<String, Object> metadata = new LinkedHashMap<>();
                if (!toolCalls.isEmpty()) {
                    metadata.put("toolCalls", toolCalls);
                }
                if (!segments.isEmpty()) {
                    metadata.put("segments", segments);
                }
                if (!currentPhase.isBlank()) {
                    metadata.put("currentPhase", currentPhase);
                }
                if (planId != null || !planSteps.isEmpty() || currentPlanStep != null) {
                    Map<String, Object> plan = new LinkedHashMap<>();
                    if (planId != null) plan.put("planId", planId);
                    if (!planSteps.isEmpty()) plan.put("steps", planSteps);
                    if (currentPlanStep != null) plan.put("currentStep", currentPlanStep);
                    if (planStepResults.stream().anyMatch(java.util.Objects::nonNull)) {
                        plan.put("stepResults", planStepResults);
                    }
                    metadata.put("plan", plan);
                }
                if (pendingApproval != null && !pendingApproval.isEmpty()) {
                    metadata.put("pendingApproval", pendingApproval);
                }
                if (!browserActions.isEmpty()) {
                    metadata.put("browserActions", browserActions);
                }
                if (!warnings.isEmpty()) {
                    metadata.put("warnings", warnings);
                }
                return objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("Failed to serialize metadata: {}", e.getMessage());
                return "{}";
            }
        }
    }
}
