package vip.mate.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Settles a paused workflow run. Callers (approval callbacks, timeout sweeper,
 * REST endpoints) hand in a {@code pauseToken} and an outcome; the resumer
 * marks the pause and the await_approval step row, hydrates a fresh
 * {@link WorkflowRunContext} from the persisted step rows, and delegates back
 * to {@link WorkflowRunner#continueFromIndex} for the post-pause tail.
 *
 * <p>Idempotent: a pause that has already been resumed yields
 * {@link Outcome#alreadyResolved(long)} without touching DB or memory. The
 * graph is loaded by the caller (typically via a revision-id lookup) since the
 * resumer has no opinion on storage.
 */
@Slf4j
@Service
public class WorkflowResumer {

    private static final String STATE_SUCCEEDED = "succeeded";
    private static final String STATE_FAILED = "failed";

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowRunner runner;
    private final PayloadStore payloadStore;
    private final ObjectMapper objectMapper;

    public WorkflowResumer(WorkflowRunMapper runMapper,
                           WorkflowRunStepMapper stepMapper,
                           WorkflowRunPauseMapper pauseMapper,
                           WorkflowRunner runner,
                           PayloadStore payloadStore,
                           ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.pauseMapper = pauseMapper;
        this.runner = runner;
        this.payloadStore = payloadStore;
        this.objectMapper = objectMapper;
    }

    public Outcome resume(WorkflowGraph graph, String pauseToken,
                          ResumeOutcome outcome, byte[] resumePayloadBody) {
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                .eq(WorkflowRunPauseEntity::getPauseToken, pauseToken));
        if (pause == null) {
            return Outcome.notFound(pauseToken);
        }
        if (pause.getResumedAt() != null) {
            return Outcome.alreadyResolved(pause.getRunId());
        }

        WorkflowRunEntity runRow = runMapper.selectById(pause.getRunId());
        if (runRow == null) {
            return Outcome.notFound(pauseToken);
        }

        WorkflowRunStepEntity stepRow = stepMapper.selectById(pause.getStepId());
        if (stepRow == null) {
            return Outcome.notFound(pauseToken);
        }

        // Persist the pause row before doing any further work so a crash mid-resume
        // leaves a clear audit trail (the pause is settled even if the post-resume
        // execution never started).
        String resumePayloadRef = null;
        if (resumePayloadBody != null && resumePayloadBody.length > 0) {
            resumePayloadRef = payloadStore.storeBytes(runRow.getWorkspaceId(),
                    resumePayloadBody, "application/octet-stream");
        }
        pause.setResumedAt(LocalDateTime.now());
        pause.setResumeOutcome(outcome.token());
        pause.setResumePayloadRef(resumePayloadRef);
        pauseMapper.updateById(pause);

        // Settle the await_approval step row first.
        stepRow.setState(outcome == ResumeOutcome.APPROVED ? STATE_SUCCEEDED : STATE_FAILED);
        stepRow.setOutputSummary("resumed: " + outcome.token());
        stepRow.setCompletedAt(LocalDateTime.now());
        if (outcome != ResumeOutcome.APPROVED) {
            stepRow.setErrorMessage("approval " + outcome.token());
        }
        stepMapper.updateById(stepRow);

        if (outcome != ResumeOutcome.APPROVED) {
            // Failed approval ends the run — no further steps.
            runRow.setState(STATE_FAILED);
            runRow.setErrorMessage("paused step '" + stepRow.getStepName() + "' " + outcome.token());
            runRow.setCompletedAt(LocalDateTime.now());
            runMapper.updateById(runRow);
            // Publish the workflow_completion event downstream — same as the
            // runner's finishFailed path. Without this, runs that end on a
            // rejected / timed-out approval would never fire their
            // completion trigger because the resumer skips
            // runner.continueFromIndex on the failure branch.
            runner.publishCompletionEvent(runRow, STATE_FAILED, null, runRow.getErrorMessage());
            return Outcome.failed(runRow.getId(), runRow.getErrorMessage());
        }

        // Hydrate the run context from prior step rows so post-resume steps can
        // reference {{ outputs.xxx }} from steps that completed before the pause.
        WorkflowRunContext ctx = hydrateContext(runRow, graph, stepRow.getStepIndex());
        String priorOutputRef = lastSucceededOutputRef(runRow.getId(), stepRow.getStepIndex());

        WorkflowRunResult result = runner.continueFromIndex(
                graph, ctx, runRow, stepRow.getStepIndex() + 1, priorOutputRef);
        return Outcome.continued(result);
    }

    private WorkflowRunContext hydrateContext(WorkflowRunEntity runRow, WorkflowGraph graph,
                                              int pausedStepIndex) {
        Map<String, Object> inputs = (runRow.getInitialInputRef() == null)
                ? Map.of()
                : payloadStore.readJson(runRow.getInitialInputRef(), Map.class);
        WorkflowRunContext ctx = new WorkflowRunContext(
                runRow.getId(),
                runRow.getWorkspaceId(),
                runRow.getWorkflowId(),
                runRow.getRevisionId(),
                inputs);

        // Replay the rolling outputs map: walk completed succeeded step rows
        // up to the pause and put their parsed payloads back into the context
        // under their declared outputVar.
        List<WorkflowRunStepEntity> rows = stepMapper.selectList(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, runRow.getId())
                .lt(WorkflowRunStepEntity::getStepIndex, pausedStepIndex)
                .orderByAsc(WorkflowRunStepEntity::getStepIndex)
                .orderByAsc(WorkflowRunStepEntity::getIterationIndex));
        for (WorkflowRunStepEntity row : rows) {
            if (!STATE_SUCCEEDED.equals(row.getState()) || row.getOutputRef() == null) continue;
            int idx = row.getStepIndex();
            if (idx < 0 || idx >= graph.steps().size()) continue;
            var step = graph.steps().get(idx);
            if (step.outputVar() == null || step.outputVar().isBlank()) continue;
            Object value = decodeOutput(row);
            if (value != null) ctx.putOutput(step.outputVar(), value);
        }
        return ctx;
    }

    private Object decodeOutput(WorkflowRunStepEntity row) {
        try {
            byte[] body = payloadStore.readBytes(row.getOutputRef());
            if ("json".equals(row.getOutputContentType())) {
                return objectMapper.readValue(body, Object.class);
            }
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Workflow resume: failed to decode prior step output ref={}: {}",
                    row.getOutputRef(), e.getMessage());
            return null;
        }
    }

    private String lastSucceededOutputRef(long runId, int beforeStepIndex) {
        WorkflowRunStepEntity row = stepMapper.selectOne(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, runId)
                .eq(WorkflowRunStepEntity::getState, STATE_SUCCEEDED)
                .lt(WorkflowRunStepEntity::getStepIndex, beforeStepIndex)
                .isNotNull(WorkflowRunStepEntity::getOutputRef)
                .orderByDesc(WorkflowRunStepEntity::getStepIndex)
                .orderByDesc(WorkflowRunStepEntity::getIterationIndex)
                .last("LIMIT 1"));
        return row == null ? null : row.getOutputRef();
    }

    /** Outcome label written to {@code mate_workflow_run_pause.resume_outcome}. */
    public enum ResumeOutcome {
        APPROVED("approved"),
        REJECTED("rejected"),
        TIMEOUT("timeout"),
        CANCELLED("cancelled");

        private final String token;

        ResumeOutcome(String token) { this.token = token; }

        public String token() { return token; }
    }

    /** Result of attempting a resume — exposes the final run state when completed inline. */
    public record Outcome(Kind kind, Long runId, WorkflowRunResult finalResult, String errorMessage) {
        public enum Kind { CONTINUED, FAILED, ALREADY_RESOLVED, NOT_FOUND }

        public static Outcome continued(WorkflowRunResult r) {
            return new Outcome(Kind.CONTINUED, r.runId(), r, null);
        }
        public static Outcome failed(long runId, String err) {
            return new Outcome(Kind.FAILED, runId, null, err);
        }
        public static Outcome alreadyResolved(long runId) {
            return new Outcome(Kind.ALREADY_RESOLVED, runId, null, null);
        }
        public static Outcome notFound(String token) {
            return new Outcome(Kind.NOT_FOUND, null, null, "pause token not found: " + token);
        }
    }
}
