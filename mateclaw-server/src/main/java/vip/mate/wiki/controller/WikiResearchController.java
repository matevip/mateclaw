package vip.mate.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiResearchService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RFC-011 Phase 3: Wiki Deep Research REST + SSE 接口
 *
 * @author MateClaw Team
 */
@Slf4j
@Tag(name = "Wiki Deep Research")
@RestController
@RequestMapping("/api/v1/wiki/research")
@RequiredArgsConstructor
public class WikiResearchController {

    private final WikiResearchService researchService;
    private final WikiKnowledgeBaseService kbService;
    private final ChatStreamTracker streamTracker;

    private static final ExecutorService RESEARCH_EXEC = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 启动 research。返回 sessionId，前端用它订阅 SSE 流。
     */
    @RequireWorkspaceRole("member")
    @Operation(summary = "启动 Deep Research，返回 SSE sessionId")
    @PostMapping("/start")
    public R<Map<String, Object>> startResearch(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {

        Long kbId = body.get("kbId") != null ? Long.valueOf(body.get("kbId").toString()) : null;
        String topic = (String) body.get("topic");
        Integer topK = body.get("topKPerQuestion") != null
                ? Integer.valueOf(body.get("topKPerQuestion").toString()) : null;

        if (kbId == null || topic == null || topic.isBlank()) {
            return R.fail("kbId and topic are required");
        }
        if (kbService.getById(kbId) == null) {
            return R.fail("Knowledge base not found");
        }

        // 生成 SSE 会话 ID
        String sessionId = "research-" + UUID.randomUUID();
        streamTracker.register(sessionId);

        // 异步跑 research，事件通过 streamTracker 推送
        // 【Review Bug 4】register 后需要 incrementFlux 配平，否则 complete 永远不会清理 RunState
        streamTracker.incrementFlux(sessionId);
        RESEARCH_EXEC.submit(() -> {
            try {
                researchService.research(kbId, topic, sessionId, topK);
            } catch (Exception e) {
                log.error("[ResearchController] Execution failed for sessionId={}: {}", sessionId, e.getMessage(), e);
            } finally {
                // 先发结束标记，让前端关闭 EventSource
                try {
                    streamTracker.broadcast(sessionId, "done", "{}");
                } catch (Exception ignored) {}
                // 然后清理 RunState（递减 flux count，所有 flux 完成时自动 remove）
                try {
                    streamTracker.complete(sessionId);
                } catch (Exception ignored) {}
            }
        });

        return R.ok(Map.of(
                "sessionId", sessionId,
                "kbId", kbId,
                "topic", topic,
                "streamUrl", "/api/v1/wiki/research/stream/" + sessionId
        ));
    }

    /**
     * SSE 端点：订阅指定 sessionId 的 research 事件流
     */
    @RequireWorkspaceRole("viewer")
    @Operation(summary = "订阅 Deep Research SSE 事件流")
    @GetMapping(value = "/stream/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId) {
        // 10 分钟超时（research 典型 < 1 分钟，10 分钟给重连留余地）
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);

        boolean attached = streamTracker.attach(sessionId, emitter);
        if (!attached) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"session not found or already ended\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
        }

        emitter.onCompletion(() -> streamTracker.detach(sessionId, emitter));
        emitter.onTimeout(() -> streamTracker.detach(sessionId, emitter));
        emitter.onError(err -> streamTracker.detach(sessionId, emitter));

        return emitter;
    }
}
