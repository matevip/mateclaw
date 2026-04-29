package vip.mate.cron.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.cron.model.CronJobDTO;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.repository.CronJobMapper;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 定时任务业务服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@Order(210)
@RequiredArgsConstructor
public class CronJobService implements ApplicationRunner {

    private final CronJobMapper cronJobMapper;
    private final AgentMapper agentMapper;
    private final ChannelMapper channelMapper;
    /**
     * RFC-063r §2.7.1: cron-tick execution moved to {@link CronJobRunner}
     * (separate bean) so the three-segment transactional model in
     * {@link CronJobLifecycleService} works via Spring AOP — no more
     * self-invocation footgun.
     *
     * <p>{@code agentService}, {@code conversationService}, and
     * {@code completionPublisher} now live on {@link CronJobLifecycleService}
     * and {@link CronJobRunner} so this service shrinks to CRUD + scheduler
     * registration only.
     */
    private final CronJobRunner cronJobRunner;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ReentrantLock schedulerLock = new ReentrantLock();

    /**
     * RFC-063r post-deploy fix: dedicated executor for the actual cron
     * execution work (LLM call + DB writes). The {@link #scheduler} thread
     * pool is intentionally tiny — its only job is to fire the trigger and
     * hand the runnable off here. If the LLM call ran on the scheduler
     * thread, 4 concurrent crons would saturate the pool and queued ones
     * would silently miss their tick.
     *
     * <p>Virtual threads (JDK 21) are perfect for this workload — LLM HTTP
     * is I/O-bound, virtual threads scale to thousands at trivial cost.
     */
    private final ExecutorService cronExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cron-execute-", 0).factory());

    // ==================== 初始化与销毁 ====================

    /**
     * 实现 ApplicationRunner，确保在 Flyway 迁移和 DatabaseBootstrapRunner 完成后再加载任务
     */
    @Override
    public void run(ApplicationArguments args) {
        // Pool size = trigger firing parallelism only. Actual execution lives
        // on cronExecutor (virtual threads), so 2 is plenty for the trigger
        // pump and even 4 was excessive; we keep 4 for headroom.
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("cron-job-");
        scheduler.initialize();

        // RFC-083: scheduler is process-global by design — load every enabled
        // job across all workspaces. Do NOT filter by workspace_id here,
        // otherwise jobs in workspace B stop firing whenever the active UI
        // workspace is A. Workspace isolation lives in the CRUD paths only.
        List<CronJobEntity> enabledJobs = cronJobMapper.selectList(
                new LambdaQueryWrapper<CronJobEntity>()
                        .eq(CronJobEntity::getEnabled, true));
        for (CronJobEntity job : enabledJobs) {
            try {
                register(job);
            } catch (Exception e) {
                log.warn("[CronJob] Failed to register job {} on startup: {}", job.getId(), e.getMessage());
            }
        }
        log.info("[CronJob] Scheduler initialized, {} jobs registered", enabledJobs.size());
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
        cronExecutor.shutdown();
    }

    // ==================== CRUD ====================

    public List<CronJobDTO> list(Long workspaceId) {
        // RFC-063r §2.14: use the variant that aggregates the most-recent
        // delivery_status from mate_cron_job_run so the list page can
        // render the "最近投递" badge without a per-row N+1 query.
        // RFC-083: scoped to the caller's workspace.
        List<CronJobEntity> entities = cronJobMapper.selectListWithDeliveryStatus(workspaceId);

        // 批量加载 Agent 名称
        List<Long> agentIds = entities.stream()
                .map(CronJobEntity::getAgentId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> agentNameMap = agentIds.isEmpty() ? Map.of() :
                agentMapper.selectBatchIds(agentIds).stream()
                        .collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName));

        // RFC-063r post-deploy fix: surface channel name on the list so the
        // UI can show which crons are bound to which IM channel — addresses
        // user's "看不到与 channel 有什么关联" complaint.
        List<Long> channelIds = entities.stream()
                .map(CronJobEntity::getChannelId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> channelNameMap = channelIds.isEmpty() ? Map.of() :
                channelMapper.selectBatchIds(channelIds).stream()
                        .collect(Collectors.toMap(ChannelEntity::getId, ChannelEntity::getName));

        return entities.stream()
                .map(e -> {
                    CronJobDTO dto = CronJobDTO.from(e, agentNameMap.getOrDefault(e.getAgentId(), "Unknown"));
                    if (e.getChannelId() != null) {
                        dto.setChannelName(channelNameMap.get(e.getChannelId()));
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public CronJobDTO getById(Long id, Long workspaceId) {
        // RFC-063r §2.14: detail page shows lastDeliveryStatus too — same
        // subquery shape, restricted to one id.
        // RFC-083: scoped to the caller's workspace; cross-workspace ID access
        // surfaces as not_found (same shape as deleted) so workspace existence
        // is not enumerable.
        CronJobEntity entity = cronJobMapper.selectByIdWithDeliveryStatus(id, workspaceId);
        if (entity == null) {
            throw new MateClawException("err.cron.not_found", "定时任务不存在: " + id);
        }
        AgentEntity agent = agentMapper.selectById(entity.getAgentId());
        CronJobDTO dto = CronJobDTO.from(entity, agent != null ? agent.getName() : "Unknown");
        if (entity.getChannelId() != null) {
            ChannelEntity channel = channelMapper.selectById(entity.getChannelId());
            if (channel != null) dto.setChannelName(channel.getName());
        }
        return dto;
    }

    public CronJobDTO create(CronJobDTO dto, Long workspaceId) {
        validateDto(dto);
        // toSpringCron 校验表达式合法性，结果复用于后续 calcNextRunTime 和 register
        String springCron = toSpringCron(dto.getCronExpression());

        CronJobEntity entity = dto.toEntity();
        // RFC-083: workspace stamped server-side from X-Workspace-Id; never
        // trust a client-supplied value (DTO.toEntity intentionally drops it).
        entity.setWorkspaceId(workspaceId);
        if (entity.getTimezone() == null) entity.setTimezone("Asia/Shanghai");
        if (entity.getTaskType() == null) entity.setTaskType("text");
        if (entity.getEnabled() == null) entity.setEnabled(true);

        entity.setNextRunTime(calcNextRunTime(springCron, entity.getTimezone()));
        cronJobMapper.insert(entity);

        if (Boolean.TRUE.equals(entity.getEnabled())) {
            // register() 内部会再次调用 toSpringCron，但表达式已校验过，不会抛异常
            register(entity);
        }

        return getById(entity.getId(), workspaceId);
    }

    public CronJobDTO update(Long id, CronJobDTO dto, Long workspaceId) {
        // RFC-083: scoped lookup — cross-workspace updates 404 the same as
        // deleted rows.
        CronJobEntity existing = cronJobMapper.selectByIdAndWorkspace(id, workspaceId);
        if (existing == null) {
            throw new MateClawException("err.cron.not_found", "定时任务不存在: " + id);
        }
        validateDto(dto);
        String springCron = toSpringCron(dto.getCronExpression());

        existing.setName(dto.getName());
        existing.setCronExpression(dto.getCronExpression());
        existing.setTimezone(dto.getTimezone() != null ? dto.getTimezone() : "Asia/Shanghai");
        existing.setAgentId(dto.getAgentId());
        existing.setTaskType(dto.getTaskType());
        existing.setTriggerMessage(dto.getTriggerMessage());
        existing.setRequestBody(dto.getRequestBody());
        if (dto.getEnabled() != null) {
            existing.setEnabled(dto.getEnabled());
        }
        existing.setNextRunTime(calcNextRunTime(springCron, existing.getTimezone()));

        cronJobMapper.updateById(existing);

        // 加锁保证 cancel + register 的原子性（ReentrantLock 支持同线程重入）
        schedulerLock.lock();
        try {
            cancel(id);
            if (Boolean.TRUE.equals(existing.getEnabled())) {
                register(existing);
            }
        } finally {
            schedulerLock.unlock();
        }

        return getById(id, workspaceId);
    }

    public void delete(Long id, Long workspaceId) {
        CronJobEntity entity = cronJobMapper.selectByIdAndWorkspace(id, workspaceId);
        if (entity == null) {
            throw new MateClawException("err.cron.not_found", "定时任务不存在: " + id);
        }
        schedulerLock.lock();
        try {
            cancel(id);
        } finally {
            schedulerLock.unlock();
        }
        cronJobMapper.deleteById(id);
    }

    public void toggle(Long id, Boolean enabled, Long workspaceId) {
        CronJobEntity entity = cronJobMapper.selectByIdAndWorkspace(id, workspaceId);
        if (entity == null) {
            throw new MateClawException("err.cron.not_found", "定时任务不存在: " + id);
        }
        entity.setEnabled(enabled);

        // 先更新 DB，再同步调度器；避免调度器已注册但 DB 未持久化的不一致状态
        if (Boolean.TRUE.equals(enabled)) {
            String springCron = toSpringCron(entity.getCronExpression());
            entity.setNextRunTime(calcNextRunTime(springCron, entity.getTimezone()));
        } else {
            entity.setNextRunTime(null);
        }
        cronJobMapper.updateById(entity);

        // 加锁保证 cancel + register 的原子性
        schedulerLock.lock();
        try {
            cancel(id);
            if (Boolean.TRUE.equals(enabled)) {
                register(entity);
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    public void runNow(Long id, Long workspaceId) {
        CronJobEntity entity = cronJobMapper.selectByIdAndWorkspace(id, workspaceId);
        if (entity == null) {
            throw new MateClawException("err.cron.not_found", "定时任务不存在: " + id);
        }
        // RFC-063r §2.7.1: delegate to CronJobRunner via Spring proxy so
        // the three-segment REQUIRES_NEW transactions on
        // CronJobLifecycleService work as advertised. "manual" trigger type
        // distinguishes this from scheduler-driven runs in mate_cron_job_run.
        // Run on the virtual-thread cronExecutor — never block the scheduler.
        cronExecutor.submit(() -> {
            try {
                cronJobRunner.executeJob(entity, "manual");
            } finally {
                updateRunTimes(entity.getId(), entity.getCronExpression(), entity.getTimezone());
            }
        });
    }

    // ==================== 调度器管理 ====================

    private void register(CronJobEntity job) {
        schedulerLock.lock();
        try {
            cancel(job.getId());
            String springCron = toSpringCron(job.getCronExpression());
            ZoneId zoneId = ZoneId.of(job.getTimezone());
            CronTrigger trigger = new CronTrigger(springCron, zoneId);
            // RFC-063r §2.7.1 + post-deploy fix: scheduler thread fires the
            // trigger and immediately offloads to the virtual-thread
            // cronExecutor — the LLM call must NOT run on a scheduler
            // worker (4 concurrent long crons would otherwise saturate the
            // pool and the 5th would miss its tick).
            ScheduledFuture<?> future = scheduler.schedule(() ->
                    cronExecutor.submit(() -> {
                        try {
                            cronJobRunner.executeJob(job, "scheduled");
                        } finally {
                            updateRunTimes(job.getId(), job.getCronExpression(), job.getTimezone());
                        }
                    }), trigger);
            scheduledTasks.put(job.getId(), future);
            log.info("[CronJob] Registered job {} ({}) ws={}, cron={}, tz={}",
                    job.getId(), job.getName(), job.getWorkspaceId(),
                    job.getCronExpression(), job.getTimezone());
        } finally {
            schedulerLock.unlock();
        }
    }

    private void cancel(Long jobId) {
        ScheduledFuture<?> f = scheduledTasks.remove(jobId);
        if (f != null) {
            f.cancel(false);
        }
    }

    // ==================== 任务执行 ====================
    //
    // RFC-063r §2.7.1: the executeJob body moved to CronJobRunner so the
    // three-segment transactional model in CronJobLifecycleService runs
    // through a Spring AOP proxy. CronJobService now only owns CRUD +
    // scheduler registration, and the lastRunTime / nextRunTime bookkeeping
    // hook below — which deliberately runs *after* the runner returns so a
    // failed run still advances the next-run pointer (otherwise a single
    // bad run wedges all future ticks).
    //
    // Both register() and runNow() now delegate to cronJobRunner.executeJob;
    // see those methods above. The wrap below ensures next-run rolls forward
    // regardless of run outcome.

    /**
     * 合并更新 lastRunTime 和 nextRunTime，单次 DB 写入替代原来的 4 次 selectById + updateById
     */
    private void updateRunTimes(Long jobId, String cronExpression, String timezone) {
        try {
            String springCron = toSpringCron(cronExpression);
            LocalDateTime nextRun = calcNextRunTime(springCron, timezone);
            cronJobMapper.update(null, new LambdaUpdateWrapper<CronJobEntity>()
                    .eq(CronJobEntity::getId, jobId)
                    .set(CronJobEntity::getLastRunTime, LocalDateTime.now())
                    .set(CronJobEntity::getNextRunTime, nextRun));
        } catch (Exception e) {
            log.warn("[CronJob] Failed to update run times for job {}: {}", jobId, e.getMessage());
        }
    }

    // ==================== Cron 工具方法 ====================

    /**
     * 5 字段用户 cron → 6 字段 Spring cron
     */
    private String toSpringCron(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) {
            throw new MateClawException("Cron 表达式必须是 5 字段（分 时 日 月 周）");
        }
        // 标准化 day-of-week
        parts[4] = normalizeDayOfWeek(parts[4]);
        String springCron = "0 " + String.join(" ", parts);
        try {
            CronExpression.parse(springCron);
        } catch (IllegalArgumentException e) {
            throw new MateClawException("Cron 表达式非法: " + e.getMessage());
        }
        return springCron;
    }

    /**
     * 标准化 day-of-week 字段：将独立的 7（Sunday）归一化为 0
     * 支持单值、列表、范围、步长格式
     */
    private String normalizeDayOfWeek(String dow) {
        // 处理逗号分隔的列表
        String[] tokens = dow.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(normalizeToken(tokens[i]));
        }
        return sb.toString();
    }

    private String normalizeToken(String token) {
        // 处理步长：如 1-7/2 或 */2
        int slashIdx = token.indexOf('/');
        if (slashIdx >= 0) {
            String base = token.substring(0, slashIdx);
            String step = token.substring(slashIdx + 1);
            return normalizeRangeOrValue(base) + "/" + step;
        }
        // 处理范围：如 1-5
        int dashIdx = token.indexOf('-');
        if (dashIdx >= 0) {
            return normalizeRangeOrValue(token);
        }
        // 单值
        return normalizeSingleValue(token);
    }

    private String normalizeRangeOrValue(String expr) {
        int dashIdx = expr.indexOf('-');
        if (dashIdx >= 0) {
            String start = normalizeSingleValue(expr.substring(0, dashIdx));
            String end = normalizeSingleValue(expr.substring(dashIdx + 1));
            return start + "-" + end;
        }
        return normalizeSingleValue(expr);
    }

    private String normalizeSingleValue(String val) {
        if ("7".equals(val.trim())) {
            return "0";
        }
        return val;
    }

    /**
     * 计算下次执行时间
     */
    private LocalDateTime calcNextRunTime(String springCron, String timezone) {
        try {
            CronExpression cronExpression = CronExpression.parse(springCron);
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime next = cronExpression.next(ZonedDateTime.now(zoneId));
            if (next != null) {
                return next.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            }
        } catch (Exception e) {
            log.warn("[CronJob] Failed to calculate next run time: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 校验 ====================

    private void validateDto(CronJobDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new MateClawException("err.cron.name_required", "任务名称不能为空");
        }
        if (dto.getAgentId() == null) {
            throw new MateClawException("err.cron.agent_required", "请选择关联 Agent");
        }
        if (dto.getCronExpression() == null || dto.getCronExpression().isBlank()) {
            throw new MateClawException("err.cron.expression_required", "Cron 表达式不能为空");
        }
        String taskType = dto.getTaskType() != null ? dto.getTaskType() : "text";
        if ("text".equals(taskType) && (dto.getTriggerMessage() == null || dto.getTriggerMessage().isBlank())) {
            throw new MateClawException("err.cron.trigger_required", "触发消息不能为空");
        }
        if ("agent".equals(taskType) && (dto.getRequestBody() == null || dto.getRequestBody().isBlank())) {
            throw new MateClawException("err.cron.target_required", "执行目标不能为空");
        }
    }
}
