package vip.mate.channel.webchat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.agent.AgentService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebChat 嵌入式对话接口
 * <p>
 * 独立于 ChatController，使用 API Key 认证（不依赖 JWT）。
 * 供外部网站通过 JS SDK 嵌入 MateClaw 对话能力。
 * <p>
 * 认证方式：请求头 X-MC-Key 携带 API Key
 *
 * @author MateClaw Team
 */
@Tag(name = "WebChat 嵌入式对话")
@Slf4j
@RestController
@RequestMapping("/api/v1/channels/webchat")
@RequiredArgsConstructor
public class WebChatController {

    private final ChannelService channelService;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ChatStreamTracker streamTracker;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * WebChat SSE 流式对话
     */
    @Operation(summary = "WebChat SSE 流式对话")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestBody WebChatRequest request) {

        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);

        // 验证 API Key 并获取关联的 Channel 配置
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            sendErrorAndComplete(emitter, "Invalid API Key");
            return emitter;
        }

        Long agentId = channel.getAgentId();
        if (agentId == null) {
            sendErrorAndComplete(emitter, "No agent configured for this WebChat channel");
            return emitter;
        }

        String visitorId = request.getVisitorId() != null ? request.getVisitorId() : UUID.randomUUID().toString();
        String conversationId = "webchat:" + apiKey.substring(0, Math.min(8, apiKey.length())) + ":" + visitorId;
        String message = request.getMessage() != null ? request.getMessage() : "";

        if (message.isBlank()) {
            sendErrorAndComplete(emitter, "Message is required");
            return emitter;
        }

        log.info("[WebChat] Stream: agentId={}, conversationId={}, visitor={}", agentId, conversationId, visitorId);

        // 注册 emitter 回调
        emitter.onCompletion(() -> log.debug("[WebChat] SSE completed: {}", conversationId));
        emitter.onTimeout(() -> {
            log.debug("[WebChat] SSE timeout: {}", conversationId);
            streamTracker.complete(conversationId);
        });
        emitter.onError(e -> {
            log.debug("[WebChat] SSE error: {} - {}", conversationId, e.getMessage());
            streamTracker.complete(conversationId);
        });

        sseExecutor.execute(() -> {
            try {
                // 创建或获取会话
                var conv = conversationService.getOrCreateConversation(conversationId, agentId, "webchat:" + visitorId);

                // 保存用户消息
                conversationService.saveMessage(conversationId, "user", message, List.of());

                // 初始化 SSE 流跟踪
                streamTracker.register(conversationId);
                streamTracker.attach(conversationId, emitter);

                // 调用 Agent 流式对话
                agentService.chatStructuredStream(agentId, message, conversationId, visitorId)
                        .doOnNext(delta -> {
                            if (delta.content() != null && !delta.content().isEmpty()) {
                                streamTracker.broadcast(conversationId, "content_delta",
                                        "{\"text\":" + escapeJson(delta.content()) + "}");
                            }
                            if (delta.thinking() != null && !delta.thinking().isEmpty()) {
                                streamTracker.broadcast(conversationId, "thinking_delta",
                                        "{\"text\":" + escapeJson(delta.thinking()) + "}");
                            }
                        })
                        .doOnComplete(() -> {
                            streamTracker.broadcast(conversationId, "done", "{\"status\":\"completed\"}");
                            streamTracker.complete(conversationId);
                        })
                        .doOnError(e -> {
                            log.error("[WebChat] Stream error: {}", e.getMessage());
                            streamTracker.broadcast(conversationId, "error",
                                    "{\"message\":" + escapeJson(e.getMessage()) + "}");
                            streamTracker.complete(conversationId);
                        })
                        .subscribe();

            } catch (Exception e) {
                log.error("[WebChat] Error: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", e.getMessage())));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    /**
     * 获取 WebChat 配置（前端 SDK 初始化用）
     */
    @Operation(summary = "获取 WebChat 配置")
    @GetMapping("/config")
    public R<Map<String, Object>> getConfig(@RequestHeader("X-MC-Key") String apiKey) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        return R.ok(Map.of(
                "channelName", channel.getName(),
                "agentId", channel.getAgentId() != null ? channel.getAgentId() : 0
        ));
    }

    // ==================== 内部方法 ====================

    /**
     * 通过 API Key 查找 WebChat 渠道
     */
    private ChannelEntity resolveChannel(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        List<ChannelEntity> channels = channelService.listChannelsByType("webchat");
        for (ChannelEntity channel : channels) {
            if (!Boolean.TRUE.equals(channel.getEnabled())) continue;
            String configJson = channel.getConfigJson();
            if (configJson != null && configJson.contains(apiKey)) {
                return channel;
            }
        }
        return null;
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "null";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    // ==================== 请求体 ====================

    @lombok.Data
    public static class WebChatRequest {
        private String message;
        private String visitorId;
    }
}
