package vip.mate.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.task.model.AsyncTaskInfo;
import vip.mate.task.repository.AsyncTaskMapper;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 通用异步任务服务 — 管理长耗时任务的生命周期（提交、轮询、完成回写）
 * <p>
 * 可复用于视频生成、图片生成、音频生成等异步场景。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskService implements ApplicationRunner {

    private final AsyncTaskMapper asyncTaskMapper;
    private final ChatStreamTracker streamTracker;

    /** Polling thread pool. Bumped from 2 to 8 in P0 — image+video+future generative
     *  tasks all share this pool, and per-task work (poll HTTP + DB write + file
     *  download in completion callbacks) is non-trivial; 2 threads saturate
     *  immediately under any concurrent load. */
    private final ScheduledExecutorService pollExecutor =
            Executors.newScheduledThreadPool(8, r -> {
                Thread t = new Thread(r, "async-task-poll");
                t.setDaemon(true);
                return t;
            });

    /** 活跃轮询任务，key = taskId */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activePolls = new ConcurrentHashMap<>();

    /** 每用户最多并行任务数 */
    private static final int MAX_ACTIVE_TASKS_PER_USER = 3;

    /** 默认轮询间隔（秒） */
    private static final int POLL_INTERVAL_SECONDS = 8;

    /** 最大轮询时长（分钟），超时自动标记失败 */
    private static final int MAX_POLL_DURATION_MINUTES = 15;

    /** 连续轮询失败次数上限，超出自动标记任务失败 */
    private static final int MAX_POLL_ERROR_COUNT = 5;

    /** 轮询连续错误计数 */
    private final ConcurrentHashMap<String, Integer> pollErrorCounts = new ConcurrentHashMap<>();

    // ==================== 任务创建 ====================

    /**
     * 创建一个异步任务记录
     *
     * @return 创建的任务实体
     */
    public AsyncTaskEntity createTask(String taskType, String conversationId,
                                       Long messageId, String providerName,
                                       String providerTaskId, String requestJson,
                                       String createdBy) {
        // 并发限制检查
        long activeCount = asyncTaskMapper.selectCount(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .eq(AsyncTaskEntity::getCreatedBy, createdBy)
                        .in(AsyncTaskEntity::getStatus, List.of("pending", "running"))
        );
        if (activeCount >= MAX_ACTIVE_TASKS_PER_USER) {
            throw new IllegalStateException("已达到最大并行任务数（" + MAX_ACTIVE_TASKS_PER_USER + "），请等待现有任务完成");
        }

        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setTaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entity.setTaskType(taskType);
        entity.setStatus("pending");
        entity.setConversationId(conversationId);
        entity.setMessageId(messageId);
        entity.setProviderName(providerName);
        entity.setProviderTaskId(providerTaskId);
        entity.setRequestJson(requestJson);
        entity.setProgress(0);
        entity.setCreatedBy(createdBy);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        asyncTaskMapper.insert(entity);

        log.info("[AsyncTask] Created task {} (type={}, provider={}, providerTaskId={})",
                entity.getTaskId(), taskType, providerName, providerTaskId);
        return entity;
    }

    // ==================== 轮询管理 ====================

    /**
     * 启动对某个任务的定期轮询
     *
     * @param taskId         内部任务 ID
     * @param statusChecker  轮询函数：providerTaskId → 状态
     * @param onComplete     完成回调：(task, status) → void
     */
    public void startPolling(String taskId,
                              Function<String, TaskPollResult> statusChecker,
                              BiConsumer<AsyncTaskEntity, TaskPollResult> onComplete) {
        AsyncTaskEntity task = findByTaskId(taskId);
        if (task == null) {
            log.warn("[AsyncTask] Cannot start polling: task {} not found", taskId);
            return;
        }

        // 更新状态为 running
        updateStatus(taskId, "running", null, null, null);

        LocalDateTime deadline = LocalDateTime.now().plusMinutes(MAX_POLL_DURATION_MINUTES);

        ScheduledFuture<?> future = pollExecutor.scheduleWithFixedDelay(() -> {
            try {
                // 超时检查
                if (LocalDateTime.now().isAfter(deadline)) {
                    log.warn("[AsyncTask] Task {} timed out after {} minutes", taskId, MAX_POLL_DURATION_MINUTES);
                    updateStatus(taskId, "failed", null, null, "任务超时（超过 " + MAX_POLL_DURATION_MINUTES + " 分钟）");
                    cancelPolling(taskId);
                    broadcastTaskEvent(task, "async_task_completed", false, null, "任务超时");
                    return;
                }

                TaskPollResult result = statusChecker.apply(task.getProviderTaskId());
                if (result == null) {
                    return;
                }

                // 轮询成功，重置错误计数
                pollErrorCounts.remove(taskId);

                // 更新进度
                if (result.progress() != null) {
                    updateStatus(taskId, "running", result.progress(), null, null);
                    broadcastProgress(task, result.progress());
                }

                // 终态处理
                if (result.isTerminal()) {
                    cancelPolling(taskId);
                    if (result.succeeded()) {
                        updateStatus(taskId, "succeeded", 100, result.resultJson(), null);
                    } else {
                        updateStatus(taskId, "failed", null, null, result.errorMessage());
                    }
                    // 刷新任务实体
                    AsyncTaskEntity freshTask = findByTaskId(taskId);
                    onComplete.accept(freshTask, result);
                }
            } catch (Exception e) {
                int errorCount = pollErrorCounts.merge(taskId, 1, Integer::sum);
                log.error("[AsyncTask] Polling error for task {} ({}/{}): {}",
                        taskId, errorCount, MAX_POLL_ERROR_COUNT, e.getMessage(), e);
                if (errorCount >= MAX_POLL_ERROR_COUNT) {
                    log.error("[AsyncTask] Task {} exceeded max poll errors, marking as failed", taskId);
                    updateStatus(taskId, "failed", null, null,
                            "轮询连续失败 " + errorCount + " 次: " + e.getMessage());
                    cancelPolling(taskId);
                    pollErrorCounts.remove(taskId);
                    broadcastTaskEvent(task, "async_task_completed", false, null,
                            "轮询异常，任务已标记失败");
                }
            }
        }, 3, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

        activePolls.put(taskId, future);
        log.info("[AsyncTask] Started polling for task {} (interval={}s, timeout={}min)",
                taskId, POLL_INTERVAL_SECONDS, MAX_POLL_DURATION_MINUTES);
    }

    private void cancelPolling(String taskId) {
        ScheduledFuture<?> future = activePolls.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    // ==================== 状态更新 ====================

    public void updateStatus(String taskId, String status, Integer progress,
                              String resultJson, String errorMessage) {
        LambdaUpdateWrapper<AsyncTaskEntity> wrapper = new LambdaUpdateWrapper<AsyncTaskEntity>()
                .eq(AsyncTaskEntity::getTaskId, taskId)
                .set(AsyncTaskEntity::getStatus, status)
                .set(AsyncTaskEntity::getUpdateTime, LocalDateTime.now());
        if (progress != null) {
            wrapper.set(AsyncTaskEntity::getProgress, progress);
        }
        if (resultJson != null) {
            wrapper.set(AsyncTaskEntity::getResultJson, resultJson);
        }
        if (errorMessage != null) {
            wrapper.set(AsyncTaskEntity::getErrorMessage, errorMessage);
        }
        asyncTaskMapper.update(null, wrapper);
    }

    // ==================== 查询 ====================

    public AsyncTaskInfo getTaskInfo(String taskId) {
        AsyncTaskEntity entity = findByTaskId(taskId);
        if (entity == null) {
            return null;
        }
        return toInfo(entity);
    }

    public List<AsyncTaskInfo> listActiveTasks(String conversationId) {
        List<AsyncTaskEntity> entities = asyncTaskMapper.selectList(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .eq(AsyncTaskEntity::getConversationId, conversationId)
                        .in(AsyncTaskEntity::getStatus, List.of("pending", "running"))
                        .orderByDesc(AsyncTaskEntity::getCreateTime)
        );
        return entities.stream().map(this::toInfo).toList();
    }

    private AsyncTaskEntity findByTaskId(String taskId) {
        return asyncTaskMapper.selectOne(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .eq(AsyncTaskEntity::getTaskId, taskId)
        );
    }

    private AsyncTaskInfo toInfo(AsyncTaskEntity entity) {
        return AsyncTaskInfo.builder()
                .taskId(entity.getTaskId())
                .taskType(entity.getTaskType())
                .status(entity.getStatus())
                .progress(entity.getProgress())
                .providerName(entity.getProviderName())
                .errorMessage(entity.getErrorMessage())
                .createTime(entity.getCreateTime())
                .build();
    }

    // ==================== SSE 广播 ====================

    private void broadcastProgress(AsyncTaskEntity task, int progress) {
        Map<String, Object> data = Map.of(
                "taskId", task.getTaskId(),
                "taskType", task.getTaskType(),
                "progress", progress,
                "providerName", Objects.toString(task.getProviderName(), "")
        );
        streamTracker.broadcastObject(task.getConversationId(), "async_task_progress", data);
    }

    public void broadcastTaskEvent(AsyncTaskEntity task, String eventName,
                                     boolean success, String videoUrl, String errorMessage) {
        broadcastTaskEvent(task, eventName, success, videoUrl, null, errorMessage);
    }

    public void broadcastTaskEvent(AsyncTaskEntity task, String eventName,
                                     boolean success, String videoUrl, String imageUrl, String errorMessage) {
        Map<String, Object> extra = new HashMap<>();
        if (videoUrl != null) extra.put("videoUrl", videoUrl);
        if (imageUrl != null) extra.put("imageUrl", imageUrl);
        broadcastTaskEventWithData(task, eventName, success, extra, errorMessage);
    }

    /**
     * Generic task-event broadcaster. Use this for any media kind where the URL
     * field name varies (audioUrl, modelUrl, ...) — pass it via {@code extraData}.
     * Named distinctly from {@link #broadcastTaskEvent} to avoid overload
     * ambiguity when callers pass {@code null} for the 4th argument.
     */
    public void broadcastTaskEventWithData(AsyncTaskEntity task, String eventName,
                                             boolean success, Map<String, Object> extraData,
                                             String errorMessage) {
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("taskType", task.getTaskType());
        data.put("success", success);
        if (extraData != null) data.putAll(extraData);
        if (errorMessage != null) data.put("errorMessage", errorMessage);
        streamTracker.broadcastObject(task.getConversationId(), eventName, data);
    }

    // ==================== 启动恢复 ====================

    @Override
    public void run(ApplicationArguments args) {
        List<AsyncTaskEntity> pendingTasks = asyncTaskMapper.selectList(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .in(AsyncTaskEntity::getStatus, List.of("pending", "running"))
        );
        if (!pendingTasks.isEmpty()) {
            log.info("[AsyncTask] Found {} unfinished tasks on startup, marking as failed", pendingTasks.size());
            for (AsyncTaskEntity task : pendingTasks) {
                updateStatus(task.getTaskId(), "failed", null, null,
                        "服务重启导致任务中断，请重新提交");
            }
        }
    }

    // ==================== 生命周期 ====================

    @PreDestroy
    public void shutdown() {
        int count = activePolls.size();
        activePolls.values().forEach(f -> f.cancel(false));
        activePolls.clear();
        pollErrorCounts.clear();
        pollExecutor.shutdownNow();
        log.info("[AsyncTask] Shutdown complete, cancelled {} active polls", count);
    }

    // ==================== 轮询结果 ====================

    /**
     * Provider 轮询返回的结果
     */
    public record TaskPollResult(
            String state,        // pending / running / succeeded / failed
            Integer progress,    // 0-100, nullable
            String videoUrl,     // 成功时的视频 URL
            String coverImageUrl,// 可选封面图
            String imageUrl,     // 成功时的图片 URL（图片生成场景）
            String resultJson,   // 完成时的完整结果 JSON
            String errorMessage  // 失败时的错误信息
    ) {
        public boolean isTerminal() {
            return "succeeded".equals(state) || "failed".equals(state);
        }

        public boolean succeeded() {
            return "succeeded".equals(state);
        }

        public static TaskPollResult pending(Integer progress) {
            return new TaskPollResult("pending", progress, null, null, null, null, null);
        }

        public static TaskPollResult running(Integer progress) {
            return new TaskPollResult("running", progress, null, null, null, null, null);
        }

        public static TaskPollResult succeeded(String videoUrl, String coverImageUrl, String resultJson) {
            return new TaskPollResult("succeeded", 100, videoUrl, coverImageUrl, null, resultJson, null);
        }

        public static TaskPollResult imageSucceeded(String imageUrl, String resultJson) {
            return new TaskPollResult("succeeded", 100, null, null, imageUrl, resultJson, null);
        }

        public static TaskPollResult failed(String errorMessage) {
            return new TaskPollResult("failed", null, null, null, null, null, errorMessage);
        }
    }
}
