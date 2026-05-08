package vip.mate.trigger.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowRevisionEntity;
import vip.mate.workflow.repository.WorkflowMapper;
import vip.mate.workflow.repository.WorkflowRevisionMapper;

/**
 * Production binding for {@link WorkflowGraphLoader}. Looks up
 * {@code mate_workflow.latest_revision_id} and parses the corresponding
 * {@code mate_workflow_revision.graph_json}. Returns
 * {@link Loaded#missing()} when either lookup fails or the workflow is
 * disabled — triggers should not fire workflows that the user already
 * paused or removed.
 */
@Slf4j
@Component
public class DefaultWorkflowGraphLoader implements WorkflowGraphLoader {

    private final WorkflowMapper workflowMapper;
    private final WorkflowRevisionMapper revisionMapper;
    private final WorkflowParser parser;

    public DefaultWorkflowGraphLoader(WorkflowMapper workflowMapper,
                                      WorkflowRevisionMapper revisionMapper,
                                      WorkflowParser parser) {
        this.workflowMapper = workflowMapper;
        this.revisionMapper = revisionMapper;
        this.parser = parser;
    }

    @Override
    public Loaded load(long workflowId, long workspaceId) {
        WorkflowEntity workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || Boolean.FALSE.equals(workflow.getEnabled())
                || workflow.getLatestRevisionId() == null) {
            return Loaded.missing();
        }
        // Workspace ownership check — the trigger must live in the same
        // workspace as the workflow. Without this gate, fixture data /
        // manual imports / a service-bypass code path could let a
        // workspace A trigger fire a workspace B workflow.
        if (workflow.getWorkspaceId() == null || workflow.getWorkspaceId() != workspaceId) {
            log.warn("Trigger graph load: workflow {} is in workspace {}, caller asked for {}",
                    workflowId, workflow.getWorkspaceId(), workspaceId);
            return Loaded.missing();
        }
        WorkflowRevisionEntity revision = revisionMapper.selectById(workflow.getLatestRevisionId());
        if (revision == null) return Loaded.missing();
        try {
            return new Loaded(parser.parse(revision.getGraphJson()), revision.getId());
        } catch (Exception e) {
            log.warn("Trigger graph load: revision {} failed to parse: {}",
                    revision.getId(), e.getMessage());
            return Loaded.missing();
        }
    }

    /**
     * @deprecated production callers MUST use the workspace-scoped overload
     *             {@link #load(long, long)}. Kept available for legacy test
     *             stubs that bind a fake workspace context. Returns
     *             {@code missing()} unconditionally so a production code
     *             path that accidentally hits this overload doesn't silently
     *             cross workspaces.
     */
    @Override
    @Deprecated
    public Loaded load(long workflowId) {
        log.warn("Workspace-blind WorkflowGraphLoader.load({}) called — refusing. " +
                "Use load(workflowId, workspaceId) instead.", workflowId);
        return Loaded.missing();
    }
}
