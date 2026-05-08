package vip.mate.workflow.compiler;

/**
 * Pluggable ACL probe used by {@link WorkflowAclValidator}. The validator
 * stays free of Spring-bean dependencies (mapper / service injection) so its
 * unit tests can stub a port directly. The runtime wiring sits in
 * {@code vip.mate.workflow.runtime} where this port is implemented in terms
 * of {@code AgentBindingService}, the workspace channel allowlist, and the
 * mate_skill.enabled view.
 */
public interface WorkflowAclPort {

    /** True if the named agent exists, is enabled, and lives in the workspace. */
    boolean agentExists(long workspaceId, String agentName);

    /** True if the agentId resolves to an enabled agent in the workspace. */
    boolean agentIdExists(long workspaceId, long agentId);

    /** True if the channel is on the workspace allowlist. */
    boolean channelAllowed(long workspaceId, String channelName);

    /** True if employeeId is a member of the workspace. */
    boolean employeeInWorkspace(long workspaceId, String employeeId);
}
