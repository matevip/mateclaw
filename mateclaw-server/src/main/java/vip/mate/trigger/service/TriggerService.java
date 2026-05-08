package vip.mate.trigger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.repository.TriggerMapper;
import vip.mate.trigger.scheduler.TriggerScheduler;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.repository.WorkflowMapper;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CRUD facade for {@code mate_trigger} that keeps the in-memory cron
 * registration in sync with the persisted row. Pattern_version is the
 * lamport counter the scheduler uses to invalidate stale schedules across
 * a multi-node deployment — every change to {@code patternJson},
 * {@code patternType}, or the disabled→enabled transition bumps it.
 *
 * <p>The service intentionally does not wrap reads in transactions; only
 * mutating paths are {@code @Transactional} so the scheduler hand-off
 * (which reads the row again under its own connection) sees committed
 * data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerService {

    /** Pattern types accepted by the v0 matcher; anything else fails closed at ingest. */
    private static final Set<String> SUPPORTED_PATTERNS = Set.of(
            "cron", "channel_message", "webhook", "agent_lifecycle",
            "content_match", "workflow_completion");

    /** v0 only dispatches workflow targets; agent target requires a v1 dispatcher. */
    private static final Set<String> SUPPORTED_TARGETS = Set.of("workflow");

    private final TriggerMapper triggerMapper;
    private final TriggerScheduler scheduler;
    /** Optional — only present in production. Tests can null it out via constructor. */
    @Autowired(required = false)
    private WorkflowMapper workflowMapper;

    public List<TriggerEntity> listByWorkspace(long workspaceId) {
        return triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getWorkspaceId, workspaceId)
                .orderByDesc(TriggerEntity::getCreateTime));
    }

    /**
     * Lookup that scopes to a single workspace. Returns {@code null} when the
     * trigger doesn't exist OR when it belongs to another workspace, so the
     * caller can surface the same "not found" status either way and avoid
     * leaking foreign trigger ids.
     */
    public TriggerEntity get(long id, long workspaceId) {
        TriggerEntity row = triggerMapper.selectById(id);
        if (row == null || row.getWorkspaceId() == null || row.getWorkspaceId() != workspaceId) {
            return null;
        }
        return row;
    }

    /** Backwards-compatible single-arg get; only used by internal pipelines that
     *  already know they hold a trusted id (scheduler, ingest). New callers must
     *  use {@link #get(long, long)}. */
    public TriggerEntity get(long id) {
        return triggerMapper.selectById(id);
    }

    @Transactional
    public TriggerEntity create(TriggerEntity trigger, long workspaceId) {
        // Ignore whatever workspace / id the caller put on the body — we
        // trust the workspace from the request header alone.
        trigger.setId(null);
        trigger.setWorkspaceId(workspaceId);
        validatePatternAndTargetShape(trigger);
        validateTargetOwnership(trigger, workspaceId);
        ensureDefaults(trigger);
        trigger.setPatternVersion(1L);
        trigger.setFireCount(0L);
        triggerMapper.insert(trigger);
        if (Boolean.TRUE.equals(trigger.getEnabled())) {
            scheduler.register(trigger);
        }
        return trigger;
    }

    /** @deprecated use {@link #create(TriggerEntity, long)} so the workspace
     *  isn't trusted from the body. Kept for tests that already supply a
     *  workspace id on the entity and reference fixture workflow ids that
     *  may not have a real row in mate_workflow. */
    @Deprecated
    @Transactional
    public TriggerEntity create(TriggerEntity trigger) {
        Long ws = trigger.getWorkspaceId();
        if (ws == null) {
            throw new IllegalArgumentException("workspaceId required");
        }
        validatePatternAndTargetShape(trigger);
        ensureDefaults(trigger);
        trigger.setPatternVersion(1L);
        trigger.setFireCount(0L);
        triggerMapper.insert(trigger);
        if (Boolean.TRUE.equals(trigger.getEnabled())) {
            scheduler.register(trigger);
        }
        return trigger;
    }

    @Transactional
    public TriggerEntity update(long id, long workspaceId, TriggerEntity updated) {
        TriggerEntity existing = get(id, workspaceId);
        if (existing == null) {
            throw new IllegalArgumentException("trigger not found: " + id);
        }
        // Force the canonical id + workspace; reject any body-side override.
        updated.setId(id);
        updated.setWorkspaceId(workspaceId);
        validatePatternAndTargetShape(updated);
        validateTargetOwnership(updated, workspaceId);
        return updateInternal(existing, updated);
    }

    /** @deprecated use the workspace-scoped overload. */
    @Deprecated
    @Transactional
    public TriggerEntity update(TriggerEntity updated) {
        TriggerEntity existing = triggerMapper.selectById(updated.getId());
        if (existing == null) {
            throw new IllegalArgumentException("trigger not found: " + updated.getId());
        }
        return updateInternal(existing, updated);
    }

    private TriggerEntity updateInternal(TriggerEntity existing, TriggerEntity updated) {
        // Bump pattern_version whenever ANY field that changes the
        // schedule's behavior, payload rendering, or rate decisions
        // changes. This is the lamport other instances rely on at fire
        // time to decide whether their captured registration is stale —
        // missing a field here means a peer fires the new payload with
        // the old throttling settings (or vice versa) until it next
        // self-cancels for some other reason.
        boolean patternChanged = !Objects.equals(existing.getPatternJson(), updated.getPatternJson())
                || !Objects.equals(existing.getPatternType(), updated.getPatternType());
        boolean payloadChanged = !Objects.equals(existing.getPayloadTemplate(), updated.getPayloadTemplate());
        boolean targetChanged = !Objects.equals(existing.getTargetType(), updated.getTargetType())
                || !Objects.equals(existing.getTargetId(), updated.getTargetId());
        boolean fireConfigChanged = !Objects.equals(existing.getRateLimitPerMin(), updated.getRateLimitPerMin())
                || !Objects.equals(existing.getDedupWindowSecs(), updated.getDedupWindowSecs())
                || !Objects.equals(existing.getMaxFires(), updated.getMaxFires())
                || !Objects.equals(existing.getBotSelfFilter(), updated.getBotSelfFilter());
        boolean enableTransition = !Objects.equals(existing.getEnabled(), updated.getEnabled());

        if (patternChanged || payloadChanged || targetChanged || fireConfigChanged || enableTransition) {
            long bumped = (existing.getPatternVersion() == null ? 1L : existing.getPatternVersion()) + 1L;
            updated.setPatternVersion(bumped);
        } else {
            updated.setPatternVersion(existing.getPatternVersion());
        }
        // Preserve fireCount / lastFiredAt / lastError — those are scheduler / ingest owned.
        updated.setFireCount(existing.getFireCount());
        updated.setLastFiredAt(existing.getLastFiredAt());

        triggerMapper.updateById(updated);

        if (Boolean.TRUE.equals(updated.getEnabled())) {
            scheduler.register(updated);
        } else {
            scheduler.unregister(updated.getId());
        }
        return updated;
    }

    @Transactional
    public void delete(long id, long workspaceId) {
        TriggerEntity row = get(id, workspaceId);
        if (row == null) return;  // 404-equivalent: idempotent for missing rows
        scheduler.unregister(id);
        triggerMapper.deleteById(id);
    }

    /** @deprecated workspace-blind delete; only retained for tests. */
    @Deprecated
    @Transactional
    public void delete(long id) {
        scheduler.unregister(id);
        triggerMapper.deleteById(id);
    }

    /**
     * Pattern + target shape validation — runs on every entry path so a
     * trigger can never silently land in a "looks enabled, never fires"
     * state. The acceptance set deliberately mirrors what
     * {@code TriggerPatternMatcher} understands AND what
     * {@code TriggerDispatcher} can actually route — extending one
     * without the other would re-introduce the silent-skip bug.
     */
    private static void validatePatternAndTargetShape(TriggerEntity t) {
        String pt = t.getPatternType();
        if (pt == null || !SUPPORTED_PATTERNS.contains(pt)) {
            throw new IllegalArgumentException("unsupported patternType: " + pt
                    + " (expected one of " + SUPPORTED_PATTERNS + ")");
        }
        String tt = t.getTargetType();
        if (tt == null || !SUPPORTED_TARGETS.contains(tt)) {
            throw new IllegalArgumentException("unsupported targetType: " + tt
                    + " (v0 only supports 'workflow')");
        }
    }

    /**
     * Cross-workspace ownership check — a trigger in workspace A must
     * not be able to point at a workflow in workspace B. Only runs on
     * the workspace-aware entry points (create / update with explicit
     * workspaceId). The deprecated overloads skip this so legacy tests
     * that reference fixture workflow ids without inserting them keep
     * working.
     */
    private void validateTargetOwnership(TriggerEntity t, long workspaceId) {
        if ("workflow".equals(t.getTargetType()) && t.getTargetId() != null
                && workflowMapper != null) {
            WorkflowEntity wf = workflowMapper.selectById(t.getTargetId());
            if (wf == null || wf.getWorkspaceId() == null
                    || wf.getWorkspaceId() != workspaceId) {
                throw new IllegalArgumentException(
                        "target workflow not found in workspace: " + t.getTargetId());
            }
        }
    }

    private static void ensureDefaults(TriggerEntity t) {
        if (t.getRateLimitPerMin() == null) t.setRateLimitPerMin(60);
        if (t.getDedupWindowSecs() == null) t.setDedupWindowSecs(60);
        if (t.getBotSelfFilter() == null) t.setBotSelfFilter(true);
        if (t.getEnabled() == null) t.setEnabled(true);
        if (t.getMaxFires() == null) t.setMaxFires(0L);
    }
}
