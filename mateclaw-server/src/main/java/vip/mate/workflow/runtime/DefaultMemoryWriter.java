package vip.mate.workflow.runtime;

import org.springframework.stereotype.Component;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

/**
 * Production binding for {@link MemoryWriter}. Resolves {@code employeeId}
 * (a string in the wire format) to the agent id keying
 * {@code mate_workspace_file}, applies the chosen merge strategy via
 * {@link MergeStrategies}, then persists the result through
 * {@link WorkspaceFileService#saveFile}.
 *
 * <p>v0 treats {@code employeeId} as the numeric agent id rendered as a
 * string. Looking the agent up by name was considered but pushes name
 * uniqueness into the runtime — the schema validator already accepts only
 * a string so the wire format does not change. When we add a "human
 * employee" surface this binding will grow a separate code path.
 */
@Component
public class DefaultMemoryWriter implements MemoryWriter {

    private final WorkspaceFileService fileService;

    public DefaultMemoryWriter(WorkspaceFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public Result write(long workspaceId, String employeeId, String file,
                        String mergeStrategy, String content) {
        Long agentId;
        try {
            agentId = Long.parseLong(employeeId);
        } catch (NumberFormatException e) {
            return Result.fail("employeeId '" + employeeId
                    + "' is not a valid agent id (numeric string expected)");
        }
        WorkspaceFileEntity existing = fileService.getFile(agentId, file);
        String existingBody = existing == null ? "" : (existing.getContent() == null ? "" : existing.getContent());

        String merged;
        try {
            merged = MergeStrategies.apply(existingBody, content, mergeStrategy);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }

        try {
            fileService.saveFile(agentId, file, merged);
        } catch (Exception e) {
            return Result.fail("failed to persist memory file '" + file + "': " + e.getMessage());
        }
        return Result.ok(mergeStrategy + " merged " + content.length() + " chars into "
                + file + " (agent " + agentId + ")");
    }
}
