package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天流状态追踪器
 * <p>
 * 采用生产者-消费者解耦设计：将 SSE 事件的生产（Flux 订阅）与消费（SseEmitter 连接）解耦。
 * 一个后台 Flux 生产者持续产出事件，广播给所有 SseEmitter 订阅者并缓存到 buffer。
 * 新连接（重连）到来时，先回放 buffer，再接入实时流。
 *
 * <h2>Single-instance assumption</h2>
 * <p><strong>The {@link #runs} map is process-local memory.</strong> A reconnect
 * request can only re-attach to a {@code RunState} that lives on the <em>same</em>
 * JVM that originally created it. In a multi-node deployment behind a load
 * balancer, the LB MUST be configured for sticky session by {@code conversationId}
 * (Nginx {@code hash $arg_conversationId consistent;}, K8s Ingress
 * cookie-based affinity, AWS ALB target-group stickiness, etc.).
 *
 * <p>This is an explicit CE constraint — see
 * {@code rfcs/community/90-appendix/02-tech-debt-inventory.md §4.1} and
 * {@code rfc-054 §0}. Cross-node SSE relay (Redis Stream / NATS / Kafka) is
 * tracked under the EE roadmap.
 *
 * <p>Operator-facing diagnostics: callers should use
 * {@link #streamExistsOnThisNode(String)} when distinguishing "stream finished
 * normally" from "stream is on a different node" — both return {@code false}
 * from {@link #attach(String, SseEmitter)} but mean very different things to
 * the user.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class ChatStreamTracker {

    /** buffer 最大事件数，超出后丢弃最早的 thinking_delta 事件以释放空间 */
    private static final int MAX_BUFFER_SIZE = 16000;

    private final ObjectMapper objectMapper;

    public ChatStreamTracker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    record SseEvent(String name, String json) {}

    /**
     * 中断类型：区分用户主动停止和用户在运行中追加新消息
     */
    public enum InterruptType {
        /** 用户点击 Stop，终止当前 turn，不自动续跑 */
        USER_STOP,
        /** 用户在执行中追加新消息，中断当前 turn 后自动续跑排队消息 */
        USER_INTERRUPT_WITH_FOLLOWUP
    }

    static final class RunState {
        final String conversationId;
        final List<SseEmitter> subscribers = new ArrayList<>();
        final List<SseEvent> buffer = new ArrayList<>();
        final Object lock = new Object();
        volatile boolean done;
        /** Flux 订阅的 Disposable，用于取消 LLM 流 */
        volatile Disposable disposable;
        /** 停止标志：requestStop() 设为 true，各图节点和 LLM 调用检查此标志以提前退出 */
        final AtomicBoolean stopRequested = new AtomicBoolean(false);
        /**
         * 当前活跃的 Flux 数量（原始流 + 审批 Replay 流共享同一个 RunState）。
         * complete() 仅在计数归零时才真正移除 RunState，防止 Replay 仍在运行时被原始流的完成误删。
         */
        volatile int activeFluxCount = 0;

        // ===== Interrupt + Queue 新增字段 =====

        /** 中断类型（null 表示未请求中断） */
        volatile InterruptType interruptType;

        /** 当前执行阶段（用于 heartbeat 和前端状态展示） */
        volatile String currentPhase = "thinking";

        /** 当前正在执行的工具名称 */
        volatile String runningToolName;

        /** 等待原因（审批等待时有值） */
        volatile String waitingReason;

        /** 排队的用户消息队列（支持多条排队消息，按序消费） */
        final java.util.Queue<QueuedInput> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

        /** 心跳定时器 */
        volatile ScheduledFuture<?> heartbeatFuture;

        /** 已广播的 pending approval ID 集合（用于幂等去重） */
        final java.util.Set<String> broadcastedApprovalIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** 创建时间（用于 stale 检测和清理） */
        final long createdAt = System.currentTimeMillis();

        RunState(String conversationId) {
            this.conversationId = conversationId;
        }
    }

    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    /** 事件 relay：子会话事件转发到父会话（用于 Agent 委派进度可见性） */
    private final ConcurrentHashMap<String, List<java.util.function.BiConsumer<String, String>>> eventRelays = new ConcurrentHashMap<>();

    /**
     * 注册事件 relay：将 sourceConversationId 的广播事件同时转发给 listener。
     * 返回一个 Runnable，调用后取消注册。
     */
    public Runnable addEventRelay(String sourceConversationId,
                                   java.util.function.BiConsumer<String, String> listener) {
        eventRelays.computeIfAbsent(sourceConversationId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(listener);
        log.debug("Event relay registered for conversation {}", sourceConversationId);
        return () -> {
            List<java.util.function.BiConsumer<String, String>> listeners = eventRelays.get(sourceConversationId);
            if (listeners != null) {
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    eventRelays.remove(sourceConversationId);
                }
            }
            log.debug("Event relay removed for conversation {}", sourceConversationId);
        };
    }

    /** 心跳调度线程池（守护线程） */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stream-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /**
     * 注册流状态（开始生成时调用）。
     * 幂等：如果已存在活跃的 RunState（Replay 与原始流共享场景），复用它而非覆盖。
     */
    public void register(String conversationId) {
        runs.computeIfAbsent(conversationId, RunState::new);
        // 如果已存在但 done=true（上一轮残留），替换为新的
        RunState state = runs.get(conversationId);
        if (state != null && state.done) {
            stopHeartbeat(conversationId);
            runs.put(conversationId, new RunState(conversationId));
        } else if (state != null) {
            // Reuse path: when complete() early-returns due to activeFluxCount > 0
            // (approval replay / interrupt / any leaked flux increment), the RunState
            // is kept with stopRequested still true from the previous turn. Left alone,
            // the next register() would reuse it and ReasoningNode would instantly
            // abort every new message with "Stop requested before LLM call".
            // Reset the flag here — new registration means new user intent, and any
            // still-live prior flux has already been cancelled via requestStop()'s
            // disposable.dispose(), so the flag is redundant for it.
            if (state.stopRequested.compareAndSet(true, false)) {
                log.info("[ChatStreamTracker] Reset stale stopRequested on register: {}", conversationId);
            }
        }
        startHeartbeat(conversationId);
        log.debug("Stream registered: {}", conversationId);
    }

    /**
     * 设置 Flux 订阅的 Disposable（流开始后立即调用）
     */
    public void setDisposable(String conversationId, Disposable disposable) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.disposable = disposable;
        }
    }

    /**
     * 请求停止指定会话的流。
     * 取消 Flux 订阅（底层 HTTP 连接也会随之关闭），返回 true 表示确实停止了正在运行的流。
     */
    public boolean requestStop(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null || state.done) {
            return false;
        }
        // 设置停止标志，图节点和 LLM 调用会检查此标志以提前退出
        boolean firstRequest = !state.stopRequested.getAndSet(true);
        Disposable d = state.disposable;
        if (d != null && !d.isDisposed()) {
            d.dispose();
            log.info("Stream stopped via requestStop: {}", conversationId);
            return true;
        }
        return firstRequest;
    }

    /**
     * 检查指定会话是否已被请求停止。
     * 图节点在每次迭代入口处调用此方法，若返回 true 则抛出 CancellationException 中断执行。
     */
    public boolean isStopRequested(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null && state.stopRequested.get();
    }

    /**
     * 广播事件到所有订阅者并缓存到 buffer
     * 注意："done" 事件即使在流已完成状态下也会被发送，确保客户端能收到完成信号
     */
    public void broadcast(String conversationId, String eventName, String jsonData) {
        RunState state = runs.get(conversationId);

        // 特殊处理 "done" 事件：即使流已完成，仍然尝试发送给所有订阅者，
        // 并且**也必须入 buffer**——这样如果客户端在生成期间 SSE 断了
        // (broken pipe / 浏览器 tab throttle / 网络抖动)，刷新页面重连
        // 时仍能从 buffer 回放 done 事件，UI 不再永远卡在"生成中"。
        // 之前的设计 done 不入 buffer，配合 complete() 立即 runs.remove()
        // 一起，使得 SSE 中途断开 = done 永远丢，是这次故障的根源。
        if ("done".equals(eventName)) {
            if (state != null) {
                SseEvent doneEvent = new SseEvent(eventName, jsonData);
                synchronized (state.lock) {
                    state.buffer.add(doneEvent);
                    Iterator<SseEmitter> it = state.subscribers.iterator();
                    while (it.hasNext()) {
                        SseEmitter emitter = it.next();
                        try {
                            emitter.send(SseEmitter.event().name(eventName).data(jsonData));
                            log.debug("Sent final 'done' event to subscriber for {}", conversationId);
                        } catch (IOException | IllegalStateException e) {
                            log.debug("Removing dead subscriber for {} while sending done event: {}", conversationId, e.getMessage());
                            it.remove();
                        }
                    }
                }
            }
            return;
        }

        // 普通事件：检查流状态
        if (state == null || state.done) {
            return;
        }

        SseEvent event = new SseEvent(eventName, jsonData);
        synchronized (state.lock) {
            state.buffer.add(event);
            // buffer 容量保护：超出上限时优先丢弃 thinking_delta（占比最大且非关键）
            if (state.buffer.size() > MAX_BUFFER_SIZE) {
                trimBuffer(state.buffer);
            }
            Iterator<SseEmitter> it = state.subscribers.iterator();
            while (it.hasNext()) {
                SseEmitter emitter = it.next();
                try {
                    emitter.send(SseEmitter.event().name(eventName).data(jsonData));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Removing dead subscriber for {}: {}", conversationId, e.getMessage());
                    it.remove();
                }
            }
        }

        // 事件 relay：转发给注册的监听器（用于子会话→父会话进度传递）
        List<java.util.function.BiConsumer<String, String>> relays = eventRelays.get(conversationId);
        if (relays != null) {
            for (var relay : relays) {
                try {
                    relay.accept(eventName, jsonData);
                } catch (Exception e) {
                    log.debug("Event relay error for {}: {}", conversationId, e.getMessage());
                }
            }
        }
    }

    /**
     * 直推事件（Object 自动序列化为 JSON）。
     * <p>
     * 用于在 Node 内部直接向前端推送 SSE 事件，绕过 NodeOutput 管道。
     * 典型场景：审批请求在 awaitDecision() 阻塞前必须先送达前端。
     *
     * @param conversationId 会话 ID
     * @param eventName      SSE 事件名称（如 tool_approval_requested）
     * @param data           事件载荷，将被 Jackson 序列化为 JSON
     */
    public void broadcastObject(String conversationId, String eventName, Object data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to serialize broadcast data for event {}: {}", eventName, e.getMessage());
            json = "{\"error\":\"serialization_failed\"}";
        }
        broadcast(conversationId, eventName, json);
    }

    /**
     * Diagnostic helper for the multi-node deployment edge case (issue #17):
     * tells the caller whether a {@link RunState} for this conversation
     * exists on <em>this</em> JVM at all (regardless of done state).
     *
     * <p>{@link #attach(String, SseEmitter)} returns {@code false} both when
     * the stream finished normally <em>and</em> when no state exists on this
     * node. Callers that need to distinguish those two cases (e.g. to send a
     * different SSE event to the client) should consult this method first.
     *
     * @return {@code true} when a RunState exists locally for this
     *         conversationId; {@code false} when it never existed here OR was
     *         already cleaned up after completion
     */
    public boolean streamExistsOnThisNode(String conversationId) {
        return runs.containsKey(conversationId);
    }

    /**
     * 将 emitter 附着到现有的运行中或刚刚完成的流。
     * 先回放 buffer 中的全部事件，再加入订阅者列表接收后续实时事件（仅当流仍在运行时）。
     * <p>
     * 兼容"流已完成"语义：如果 RunState 还在 map 里但 done=true，仍然回放 buffer
     * （包含 done 事件本身），让重连客户端拿到完成信号后正常退出"生成中"状态。
     * RunState 完成后会保留 DONE_RETENTION_MS（5 分钟），由 cleanupStaleRuns 异步清理；
     * 这段窗口期内任何刷新页面都能拿到 done 回放。
     *
     * @return true 如果成功附着或重放（订阅者已加入或事件已重放完毕），false 如果没有任何状态可恢复
     */
    public boolean attach(String conversationId, SseEmitter emitter) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return false;
        }
        synchronized (state.lock) {
            // 回放全部缓冲事件（包含 done 事件本身——见 broadcast 的 done 分支）
            for (SseEvent event : state.buffer) {
                try {
                    emitter.send(SseEmitter.event().name(event.name()).data(event.json()));
                } catch (IOException | IllegalStateException e) {
                    log.warn("Failed to replay buffer to reconnecting client for {}: {}",
                            conversationId, e.getMessage());
                    return false;
                }
            }
            // 流已完成：buffer 已回放完毕（含 done event），不需要订阅后续事件
            if (state.done) {
                log.info("[SSE] Replayed {} buffered events to reconnecting client for completed stream: {}",
                        state.buffer.size(), conversationId);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // emitter 已被 servlet 容器关掉了，无需处理
                }
                return true;
            }
            state.subscribers.add(emitter);
        }
        log.info("[SSE] Client reconnected for conversation={}, replaying {} buffered events",
                conversationId, state.buffer.size());
        return true;
    }

    /**
     * 递增活跃 Flux 计数（每个 Flux 订阅开始时调用）。
     * 原始流和审批 Replay 流共享同一个 RunState，通过计数协调生命周期。
     */
    public void incrementFlux(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            synchronized (state.lock) {
                state.activeFluxCount++;
                log.debug("Flux count incremented: {} (count={})", conversationId, state.activeFluxCount);
            }
        }
    }

    /**
     * 完成结果：包含是否全部完成、排队消息快照
     */
    public record CompletionResult(boolean allDone, QueuedInput queuedInput) {}

    /**
     * 标记一个 Flux 完成。仅在所有 Flux 都完成时才真正移除 RunState。
     * <p>
     * 这解决了"原始流完成关闭 SSE，但 Replay 流仍在运行"的竞态问题。
     * <p>
     * <b>无副作用</b>：不消费排队消息。适用于不关心 queue 的路径（approval deny、setup error 等）。
     * 需要链式续跑的路径应使用 {@link #completeAndConsumeIfLast(String)}。
     *
     * @return true 如果这是最后一个 Flux（RunState 已被移除），false 如果仍有活跃 Flux
     */
    public boolean complete(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return true;
        }
        synchronized (state.lock) {
            state.activeFluxCount = Math.max(0, state.activeFluxCount - 1);
            if (state.activeFluxCount > 0) {
                log.debug("Stream partially completed (no queue drain): {} (remaining flux={})",
                        conversationId, state.activeFluxCount);
                return false;
            }
        }
        // 所有 Flux 都已完成，停止心跳，标记 done 但**不立即移除 RunState**——
        // 留给 cleanupStaleRuns 在 DONE_RETENTION_MS 后异步清理。这段窗口期内
        // 客户端刷新页面 attach() 能从 buffer 回放 done 事件，UI 不会卡在
        // "生成中"。之前立即 runs.remove() 是 SSE 中途断开导致 done 永远丢的根源。
        stopHeartbeat(conversationId);
        state.done = true;
        log.debug("Stream fully completed (no queue drain): {} (kept in map for {}ms reconnect window)",
                conversationId, DONE_RETENTION_MS);
        return true;
    }

    /**
     * 原子地递减 activeFluxCount，仅在最后一个 Flux 完成时消费排队消息并移除 RunState。
     * <p>
     * 将「递减计数 → 消费 queue → 删除 RunState」三步收口到同一个临界区，
     * 避免非最后一个 flux 提前 consume 导致 queue 丢失，也避免 complete 后查不到 queue。
     *
     * @return CompletionResult(allDone, queuedInput)
     */
    public CompletionResult completeAndConsumeIfLast(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return new CompletionResult(true, null);
        }
        QueuedInput consumed = null;
        synchronized (state.lock) {
            state.activeFluxCount = Math.max(0, state.activeFluxCount - 1);
            if (state.activeFluxCount > 0) {
                log.debug("Stream partially completed: {} (remaining flux={}, queuePreserved={})",
                        conversationId, state.activeFluxCount, !state.messageQueue.isEmpty());
                return new CompletionResult(false, null);
            }
            // 最后一个 Flux：在同一个锁内消费排队消息（取队首）
            consumed = state.messageQueue.poll();
        }
        // 锁外：停止心跳，标记 done。**不立即移除 RunState**——保留 DONE_RETENTION_MS
        // 让客户端可在窗口期内刷新页面通过 attach() 回放 done 事件。
        stopHeartbeat(conversationId);
        state.done = true;
        log.debug("Stream fully completed: {} (hasQueuedSnapshot={}, kept in map for {}ms reconnect window)",
                conversationId, consumed != null, DONE_RETENTION_MS);
        return new CompletionResult(true, consumed);
    }

    /**
     * 检查指定会话是否有正在运行的流
     */
    public boolean isRunning(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null && !state.done;
    }

    /**
     * 从订阅者列表中移除指定 emitter（连接断开/超时时调用）
     */
    public void detach(String conversationId, SseEmitter emitter) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return;
        }
        synchronized (state.lock) {
            state.subscribers.remove(emitter);
        }
        log.debug("Emitter detached from stream: {} (remaining={})",
                conversationId, state.subscribers.size());
    }

    // ===== Heartbeat =====

    /** 心跳间隔（秒） */
    private static final int HEARTBEAT_INTERVAL_SEC = 10;

    /**
     * 启动心跳定时器。在流注册后调用，定期向前端发送 heartbeat 事件。
     * 防止 useStream 的 60 秒无数据 timeout 误杀等待审批/长工具的流。
     */
    public void startHeartbeat(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) return;
        // 避免重复启动
        if (state.heartbeatFuture != null && !state.heartbeatFuture.isDone()) return;

        state.heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                RunState s = runs.get(conversationId);
                if (s == null || s.done) {
                    stopHeartbeat(conversationId);
                    return;
                }
                String json;
                try {
                    json = objectMapper.writeValueAsString(Map.of(
                            "conversationId", conversationId,
                            "currentPhase", safe(s.currentPhase),
                            "waitingReason", safe(s.waitingReason),
                            "runningToolName", safe(s.runningToolName),
                            "queueLength", s.messageQueue.size(),
                            "timestamp", System.currentTimeMillis()
                    ));
                } catch (Exception e) {
                    json = "{\"conversationId\":\"" + conversationId + "\"}";
                }
                broadcast(conversationId, "heartbeat", json);
            } catch (Exception e) {
                log.debug("Heartbeat error for {}: {}", conversationId, e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /**
     * 停止心跳定时器
     */
    public void stopHeartbeat(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state != null && state.heartbeatFuture != null) {
            state.heartbeatFuture.cancel(false);
            state.heartbeatFuture = null;
        }
    }

    // ===== Phase tracking =====

    /**
     * 更新当前执行阶段（用于 heartbeat 和前端状态展示）
     */
    public void updatePhase(String conversationId, String phase) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.currentPhase = phase;
        }
    }

    /**
     * 更新当前正在执行的工具名称
     */
    public void updateRunningTool(String conversationId, String toolName) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.runningToolName = toolName;
        }
    }

    /**
     * 设置等待原因
     */
    public void setWaitingReason(String conversationId, String reason) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.waitingReason = reason;
        }
    }

    // ===== Interrupt with follow-up =====

    /**
     * 请求中断当前流并排队一条用户消息。
     * 与 requestStop 的区别：中断后自动续跑排队消息，而非停在原地。
     *
     * @return true 如果成功请求了中断
     */
    public boolean requestInterrupt(String conversationId, String queuedMessage, Long agentId, boolean persisted) {
        RunState state = runs.get(conversationId);
        if (state == null || state.done) {
            return false;
        }

        // 在锁内完成入队和 Disposable 可用性判断，锁外执行 dispose/broadcast
        Disposable toDispose = null;
        boolean canInterrupt;
        synchronized (state.lock) {
            Disposable d = state.disposable;
            canInterrupt = d != null && !d.isDisposed();
            // 无论是否可中断，都入队（支持多条排队消息）
            state.messageQueue.offer(new QueuedInput(queuedMessage, agentId, persisted));
            if (canInterrupt) {
                state.interruptType = InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                state.stopRequested.set(true);
                toDispose = d;
            }
            // 不可中断时不设 interruptType / stopRequested
        }

        // 锁外执行 dispose 和 broadcast（这些可能阻塞或耗时）
        if (canInterrupt) {
            toDispose.dispose();
            log.info("Stream interrupted for follow-up: {} (queued: {})", conversationId,
                    queuedMessage != null ? queuedMessage.substring(0, Math.min(30, queuedMessage.length())) : "null");
            try {
                String json = objectMapper.writeValueAsString(Map.of(
                        "conversationId", conversationId,
                        "queuedMessage", queuedMessage != null ? queuedMessage : "",
                        "timestamp", System.currentTimeMillis()
                ));
                broadcast(conversationId, "turn_interrupt_requested", json);
            } catch (Exception e) {
                log.warn("Failed to broadcast turn_interrupt_requested: {}", e.getMessage());
            }
            return true;
        }

        log.info("Interrupt requested but Disposable unavailable, message queued only: {} (queued: {})",
                conversationId,
                queuedMessage != null ? queuedMessage.substring(0, Math.min(30, queuedMessage.length())) : "null");
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "conversationId", conversationId,
                    "queuedMessage", queuedMessage != null ? queuedMessage : "",
                    "timestamp", System.currentTimeMillis()
            ));
            broadcast(conversationId, "queued_input_accepted", json);
        } catch (Exception e) {
            log.warn("Failed to broadcast queued_input_accepted: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 将消息加入队列但不中断当前执行（用于不可中断阶段）。
     */
    public boolean enqueueMessage(String conversationId, String message, Long agentId, boolean persisted) {
        RunState state = runs.get(conversationId);
        if (state == null || state.done) {
            return false;
        }
        state.messageQueue.offer(new QueuedInput(message, agentId, persisted));
        // broadcast 在锁外
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "conversationId", conversationId,
                    "queuedMessage", message,
                    "timestamp", System.currentTimeMillis()
            ));
            broadcast(conversationId, "queued_input_accepted", json);
        } catch (Exception e) {
            log.warn("Failed to broadcast queued_input_accepted: {}", e.getMessage());
        }
        return true;
    }

    /**
     * 排队输入的原子快照（message + agentId + persisted 一起返回，避免分离读取导致不一致）
     */
    public record QueuedInput(String message, Long agentId, boolean persisted) {}

    /**
     * 原子消费排队的输入（流完成/中断后调用）。
     * 从队列头部取出一条消息。
     */
    public QueuedInput consumeQueuedInput(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) return null;
        return state.messageQueue.poll();
    }

    /**
     * @deprecated Use {@link #consumeQueuedInput(String)} instead.
     */
    @Deprecated
    public String consumeQueuedMessage(String conversationId) {
        QueuedInput input = consumeQueuedInput(conversationId);
        return input != null ? input.message() : null;
    }

    /**
     * @deprecated 多消息队列模式下，改为在入队时直接传入 persisted 参数。
     */
    @Deprecated
    public boolean markQueuedMessagePersisted(String conversationId) {
        // 向后兼容：无操作（persisted 已在入队时设定）
        return true;
    }

    /**
     * 获取中断类型
     */
    public InterruptType getInterruptType(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null ? state.interruptType : null;
    }

    /**
     * 清除中断状态
     */
    public void clearInterruptState(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.interruptType = null;
        }
    }

    /**
     * 检查是否有排队消息
     */
    public boolean hasQueuedMessage(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null && !state.messageQueue.isEmpty();
    }

    /**
     * 获取当前排队消息数量
     */
    public int getQueueSize(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null ? state.messageQueue.size() : 0;
    }

    // ===== Approval idempotency =====

    /**
     * 尝试标记一个 approval ID 为已广播。如果已经广播过则返回 false（幂等去重）。
     */
    public boolean markApprovalBroadcasted(String conversationId, String pendingId) {
        RunState state = runs.get(conversationId);
        if (state == null) return false;
        return state.broadcastedApprovalIds.add(pendingId);
    }

    // ===== Utility =====

    private static String safe(String s) {
        return s != null ? s : "";
    }

    /**
     * 将 buffer 裁剪到 MAX_BUFFER_SIZE 以内。
     * 策略：将连续的同类型 delta 事件合并为一条（拼接 delta 文本，保留完整内容但减少条目数）。
     * 如果合并后仍超限，丢弃最早的 thinking_delta（thinking 对重连恢复不是关键内容）。
     * 必须在 state.lock 内调用。
     */
    private static void trimBuffer(List<SseEvent> buffer) {
        if (buffer.size() <= MAX_BUFFER_SIZE) return;

        // 第一步：合并连续的同类型 delta 事件，拼接 delta 文本而非丢弃
        List<SseEvent> compacted = new ArrayList<>(buffer.size());
        int i = 0;
        while (i < buffer.size()) {
            SseEvent current = buffer.get(i);
            if ("thinking_delta".equals(current.name()) || "content_delta".equals(current.name())) {
                // 收集连续同类型 delta 的文本
                StringBuilder merged = new StringBuilder();
                merged.append(extractDelta(current.json()));
                int j = i + 1;
                while (j < buffer.size() && current.name().equals(buffer.get(j).name())) {
                    merged.append(extractDelta(buffer.get(j).json()));
                    j++;
                }
                // 合并为一条事件
                compacted.add(new SseEvent(current.name(), buildDeltaJson(merged.toString())));
                i = j;
            } else {
                compacted.add(current);
                i++;
            }
        }

        // 第二步：如果仍超限，丢弃最早的 thinking_delta（对重连恢复不是关键）
        if (compacted.size() > MAX_BUFFER_SIZE) {
            Iterator<SseEvent> it = compacted.iterator();
            int removed = 0;
            int target = compacted.size() - MAX_BUFFER_SIZE;
            while (it.hasNext() && removed < target) {
                SseEvent e = it.next();
                if ("thinking_delta".equals(e.name())) {
                    it.remove();
                    removed++;
                }
            }
        }

        buffer.clear();
        buffer.addAll(compacted);
        log.debug("Buffer trimmed: {} events", buffer.size());
    }

    /**
     * 从 delta JSON（如 {"delta":"text"}）中提取 delta 值
     */
    private static String extractDelta(String json) {
        // 快速解析 {"delta":"..."} — 避免引入完整 JSON 解析器依赖
        int idx = json.indexOf("\"delta\"");
        if (idx < 0) return "";
        int colonIdx = json.indexOf(':', idx);
        if (colonIdx < 0) return "";
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = startQuote + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) {
                char next = json.charAt(k + 1);
                if (next == '"') { sb.append('"'); k++; }
                else if (next == '\\') { sb.append('\\'); k++; }
                else if (next == 'n') { sb.append('\n'); k++; }
                else if (next == 't') { sb.append('\t'); k++; }
                else if (next == 'r') { sb.append('\r'); k++; }
                else if (next == '/') { sb.append('/'); k++; }
                else if (next == 'b') { sb.append('\b'); k++; }
                else if (next == 'f') { sb.append('\f'); k++; }
                else if (next == 'u' && k + 5 < json.length()) {
                    // Unicode escape: backslash-u followed by 4 hex digits
                    String hex = json.substring(k + 2, k + 6);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                        k += 5;
                    } catch (NumberFormatException e) {
                        sb.append(c); // 无法解析，保留原样
                    }
                }
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 构建 delta JSON 字符串
     */
    private static String buildDeltaJson(String delta) {
        StringBuilder sb = new StringBuilder("{\"delta\":\"");
        for (int k = 0; k < delta.length(); k++) {
            char c = delta.charAt(k);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        sb.append("\"}");
        return sb.toString();
    }

    // ==================== Stale RunState 清理 ====================

    /** 已完成的 RunState 保留时间（5 分钟） */
    private static final long DONE_RETENTION_MS = 5 * 60 * 1000;
    /** RunState 最大存活时间（30 分钟，防止挂起的流永远占内存） */
    private static final long MAX_LIFETIME_MS = 30 * 60 * 1000;

    /**
     * 定期清理过期的 RunState，防止内存泄漏。
     * - 已完成超过 5 分钟的 → 移除
     * - 存活超过 30 分钟的（无论是否完成）→ 强制移除
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 600_000)
    public void cleanupStaleRuns() {
        long now = System.currentTimeMillis();
        int evicted = 0;

        var iterator = runs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            RunState state = entry.getValue();
            long age = now - state.createdAt;

            boolean shouldEvict = false;
            String reason = null;

            if (state.done && age > DONE_RETENTION_MS) {
                shouldEvict = true;
                reason = "completed and expired";
            } else if (age > MAX_LIFETIME_MS) {
                shouldEvict = true;
                reason = "exceeded max lifetime (" + (age / 1000) + "s)";
            }

            if (shouldEvict) {
                // 先清理资源再移除
                stopHeartbeat(entry.getKey());
                Disposable d = state.disposable;
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                }
                iterator.remove();
                evicted++;
                log.warn("[SSE] Evicted stale RunState for conversation={}: {}",
                        entry.getKey(), reason);
            }
        }

        if (evicted > 0) {
            log.info("[SSE] Cleanup completed: evicted {} stale RunState entries, {} remaining",
                    evicted, runs.size());
        }
    }
}
