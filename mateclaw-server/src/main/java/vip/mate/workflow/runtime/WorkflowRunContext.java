package vip.mate.workflow.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable run-scoped state shared across step adapters. Holds the per-run
 * identity ({@code runId}, {@code workspaceId}), the resolved input bag, and
 * the rolling outputs map keyed by {@code outputVar}. Adapters mutate this
 * after each successful step so subsequent expressions / templates see the
 * latest value via {@link #templateContext()}.
 *
 * <p>Not thread-safe by itself — the runner ensures a single writer at a time.
 * For the fan_out group, adapters write to a temporary local map and the
 * runner merges results back into the shared context once the group completes.
 */
public class WorkflowRunContext {

    private final long runId;
    private final long workspaceId;
    private final long workflowId;
    private final long revisionId;
    private final Map<String, Object> inputs;
    private final Map<String, Object> outputs = new LinkedHashMap<>();

    public WorkflowRunContext(long runId, long workspaceId, long workflowId, long revisionId,
                              Map<String, Object> inputs) {
        this.runId = runId;
        this.workspaceId = workspaceId;
        this.workflowId = workflowId;
        this.revisionId = revisionId;
        this.inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public long runId() { return runId; }
    public long workspaceId() { return workspaceId; }
    public long workflowId() { return workflowId; }
    public long revisionId() { return revisionId; }

    public Map<String, Object> inputs() { return inputs; }

    /** Mutable outputs map. Use {@link #putOutput} for writes. */
    public synchronized Map<String, Object> outputs() {
        return new LinkedHashMap<>(outputs);
    }

    public synchronized void putOutput(String name, Object value) {
        if (name == null || name.isBlank()) return;
        outputs.put(name, value);
    }

    /**
     * Snapshot map shaped as {@code {"inputs": {...}, "outputs": {...}}} —
     * the contract every workflow expression / template assumes. The map is a
     * defensive copy so concurrent fan_out branches can render templates
     * against a stable view while another branch's success completes.
     */
    public synchronized Map<String, Object> templateContext() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("inputs", inputs);
        ctx.put("outputs", new LinkedHashMap<>(outputs));
        return ctx;
    }

    /**
     * Build a child context for one fan_out branch. The child shares
     * {@code inputs} with the parent (immutable already) and gets a
     * deep-copied snapshot of the parent's outputs at branch-entry time
     * — writes via the child's {@link #putOutput} do NOT propagate back
     * to this context until the runner explicitly merges them after the
     * group completes. That snapshot isolation is what stops branch B's
     * Pebble template from observing branch A's mid-flight write
     * (or vice-versa) when they race on the executor.
     *
     * <p>The merge step is owned by the runner — see
     * {@code WorkflowRunner.executeFanOutGroup}. The branch's own
     * {@code outputVar} write IS still visible inside the branch, which
     * is what the schema validator promises authors: a branch can see
     * its own value but never its sibling branches'.
     */
    public synchronized WorkflowRunContext branchSnapshot() {
        WorkflowRunContext child = new WorkflowRunContext(runId, workspaceId,
                workflowId, revisionId, inputs);
        // Seed the child with a snapshot of the parent's outputs so the
        // branch can read everything that completed before the fan_out
        // group started, but its own writes stay local.
        child.outputs.putAll(this.outputs);
        return child;
    }

    /**
     * Merge a single key/value into the outputs map. Used by the runner
     * after a fan_out group completes to apply each branch's
     * {@code outputVar} to the master context in deterministic
     * step-index order.
     */
    public synchronized void mergeOutput(String name, Object value) {
        if (name == null || name.isBlank()) return;
        outputs.put(name, value);
    }
}
