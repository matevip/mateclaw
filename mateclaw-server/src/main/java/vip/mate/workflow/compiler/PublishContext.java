package vip.mate.workflow.compiler;

/**
 * Immutable scope passed to publish-time validators: the workspace the
 * workflow lives in plus the user attempting to publish. ACL checks compare
 * these against the resolvable agent / channel / employee scope.
 */
public record PublishContext(long workspaceId, Long publisherId) {
}
