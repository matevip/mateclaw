package vip.mate.memory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.memory.event.DreamCompletedEvent;
import vip.mate.memory.event.DreamFailedEvent;
import vip.mate.memory.service.DreamReport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Broadcasts dream events to connected SSE clients.
 * Clients subscribe per agentId via GET /dream/events.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DreamEventBroadcaster {

    private final ObjectMapper objectMapper;
    private final List<EmitterEntry> emitters = new CopyOnWriteArrayList<>();

    record EmitterEntry(Long agentId, SseEmitter emitter) {}

    /**
     * Register a new SSE emitter for an agent.
     */
    public SseEmitter register(Long agentId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        EmitterEntry entry = new EmitterEntry(agentId, emitter);
        emitters.add(entry);
        emitter.onCompletion(() -> emitters.remove(entry));
        emitter.onTimeout(() -> emitters.remove(entry));
        emitter.onError(e -> emitters.remove(entry));
        log.debug("[DreamSSE] Client connected for agent={}, total={}", agentId, emitters.size());
        return emitter;
    }

    @Async
    @EventListener
    public void onDreamCompleted(DreamCompletedEvent event) {
        broadcast(event.report(), "dream.completed");
    }

    @Async
    @EventListener
    public void onDreamFailed(DreamFailedEvent event) {
        broadcast(event.report(), "dream.failed");
    }

    private void broadcast(DreamReport report, String eventType) {
        Long agentId = report.agentId();
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                    "type", eventType,
                    "agentId", agentId,
                    "mode", report.mode().name(),
                    "topic", report.topic() != null ? report.topic() : "",
                    "status", report.status().name(),
                    "promotedCount", report.promotedCount(),
                    "rejectedCount", report.rejectedCount()
            ));
        } catch (Exception e) {
            log.warn("[DreamSSE] Failed to serialize event: {}", e.getMessage());
            return;
        }

        List<EmitterEntry> dead = new java.util.ArrayList<>();
        for (EmitterEntry entry : emitters) {
            if (!entry.agentId().equals(agentId)) continue;
            try {
                entry.emitter().send(SseEmitter.event()
                        .name(eventType)
                        .data(json));
            } catch (Exception e) {
                dead.add(entry);
            }
        }
        emitters.removeAll(dead);
        log.debug("[DreamSSE] Broadcast {} to {} clients for agent={}", eventType, emitters.size(), agentId);
    }
}
