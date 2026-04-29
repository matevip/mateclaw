package vip.mate.tool.mcp.runtime;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * RFC-052 §3.4: thin decorator that overrides {@link ToolCallback#getToolMetadata()}
 * to report {@code returnDirect=true} for MCP tools.
 *
 * <p>Spring AI 1.1.4's {@code SyncMcpToolCallback} / {@code AsyncMcpToolCallback}
 * never override {@code getToolMetadata()} (they inherit the framework default
 * which yields {@code returnDirect=false}), and the upstream MCP protocol layer
 * has no equivalent field. So MateClaw must wrap MCP callbacks at registration
 * time when their server+tool config opts in via
 * {@code mateclaw.mcp.return-direct.tools}.
 *
 * <p>Everything else (definition, invocation, exceptions) is delegated verbatim
 * — guard, approval, observability, audit all see the original callback.
 *
 * @author MateClaw Team
 */
public final class ReturnDirectMcpToolCallback implements ToolCallback {

    private static final ToolMetadata RETURN_DIRECT_METADATA =
            ToolMetadata.builder().returnDirect(true).build();

    private final ToolCallback delegate;

    public ReturnDirectMcpToolCallback(ToolCallback delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return RETURN_DIRECT_METADATA;
    }

    @Override
    public String call(String arguments) {
        return delegate.call(arguments);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        return delegate.call(arguments, toolContext);
    }

    /** Test/diagnostic accessor — not part of the framework contract. */
    public ToolCallback getDelegate() {
        return delegate;
    }
}
