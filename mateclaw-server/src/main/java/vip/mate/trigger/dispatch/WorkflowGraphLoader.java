package vip.mate.trigger.dispatch;

import vip.mate.workflow.compiler.ir.WorkflowGraph;

/**
 * SPI for "given a workflow id, load the published WorkflowGraph the trigger
 * should fire". Production binding reads {@code mate_workflow.latest_revision_id}
 * and parses {@code mate_workflow_revision.graph_json}; tests stub this so a
 * fire path can be exercised without standing up the publish pipeline.
 */
public interface WorkflowGraphLoader {

    /**
     * Result of a graph load. {@code graph == null} indicates the workflow
     * has no published revision yet (or was deleted) and the fire should
     * be skipped instead of erroring.
     */
    record Loaded(WorkflowGraph graph, Long revisionId) {
        public static Loaded missing() { return new Loaded(null, null); }
    }

    /**
     * Workspace-scoped lookup. Production callers MUST use this overload
     * so a trigger in workspace A can never resolve to a workflow in
     * workspace B (e.g. via fixture data, manual DB import, or a
     * service-bypass code path). The default binding validates that
     * {@code mate_workflow.workspace_id == workspaceId}; tests that don't
     * care override this to delegate to the workspace-blind overload.
     */
    default Loaded load(long workflowId, long workspaceId) {
        // Default: fall through to the single-arg lookup. The production
        // {@link DefaultWorkflowGraphLoader} overrides this to enforce
        // ownership; test stubs that don't care inherit the lenient
        // default.
        return load(workflowId);
    }

    /**
     * @deprecated workspace-blind lookup; only kept for legacy test stubs
     *             and the deprecated path inside the production binding.
     *             New callers must use {@link #load(long, long)}.
     */
    @Deprecated
    Loaded load(long workflowId);
}
