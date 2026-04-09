package vip.mate.dashboard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.repository.CronJobMapper;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CronJob 执行历史服务
 *
 * @author MateClaw Team
 */
@Service
@RequiredArgsConstructor
public class CronJobRunService {

    private final CronJobRunMapper runMapper;
    private final CronJobMapper cronJobMapper;
    private final AgentMapper agentMapper;

    /**
     * 记录一次执行开始
     */
    public CronJobRunEntity recordStart(Long cronJobId, String triggerType, String conversationId) {
        CronJobRunEntity run = new CronJobRunEntity();
        run.setCronJobId(cronJobId);
        run.setConversationId(conversationId);
        run.setStatus("running");
        run.setTriggerType(triggerType);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    /**
     * 记录执行完成
     */
    public void recordComplete(Long runId, Integer tokenUsage) {
        CronJobRunEntity run = runMapper.selectById(runId);
        if (run != null) {
            run.setStatus("completed");
            run.setFinishedAt(LocalDateTime.now());
            run.setTokenUsage(tokenUsage);
            runMapper.updateById(run);
        }
    }

    /**
     * 记录执行失败
     */
    public void recordFailed(Long runId, String errorMessage) {
        CronJobRunEntity run = runMapper.selectById(runId);
        if (run != null) {
            run.setStatus("failed");
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage(errorMessage);
            runMapper.updateById(run);
        }
    }

    /**
     * 查询某个 CronJob 的执行历史
     */
    public List<CronJobRunEntity> listByJobId(Long cronJobId, int limit) {
        return runMapper.selectList(
                new LambdaQueryWrapper<CronJobRunEntity>()
                        .eq(CronJobRunEntity::getCronJobId, cronJobId)
                        .orderByDesc(CronJobRunEntity::getStartedAt)
                        .last("LIMIT " + limit));
    }

    /**
     * 查询最近的执行记录
     */
    public List<CronJobRunEntity> listRecent(int limit) {
        return runMapper.selectList(
                new LambdaQueryWrapper<CronJobRunEntity>()
                        .orderByDesc(CronJobRunEntity::getStartedAt)
                        .last("LIMIT " + limit));
    }

    /**
     * 查询指定 workspace 关联的最近执行记录
     * 路径：workspace → agents → cronJobs → cronJobRuns
     */
    public List<CronJobRunEntity> listRecentByWorkspace(Long workspaceId, int limit) {
        // 1. workspace 下的 agent IDs
        List<AgentEntity> agents = agentMapper.selectList(
                new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getWorkspaceId, workspaceId)
                        .select(AgentEntity::getId));
        if (agents.isEmpty()) return Collections.emptyList();
        Set<Long> agentIds = agents.stream().map(AgentEntity::getId).collect(Collectors.toSet());

        // 2. 这些 agent 关联的 cronJob IDs
        List<CronJobEntity> jobs = cronJobMapper.selectList(
                new LambdaQueryWrapper<CronJobEntity>()
                        .in(CronJobEntity::getAgentId, agentIds)
                        .select(CronJobEntity::getId));
        if (jobs.isEmpty()) return Collections.emptyList();
        Set<Long> jobIds = jobs.stream().map(CronJobEntity::getId).collect(Collectors.toSet());

        // 3. 这些 cronJob 的执行记录
        return runMapper.selectList(
                new LambdaQueryWrapper<CronJobRunEntity>()
                        .in(CronJobRunEntity::getCronJobId, jobIds)
                        .orderByDesc(CronJobRunEntity::getStartedAt)
                        .last("LIMIT " + limit));
    }
}
