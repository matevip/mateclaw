package vip.mate.tool.mcp.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * RFC-052 §3.4 / PR-4: MCP tool return-direct opt-in list.
 *
 * <p>Tools listed here are wrapped in {@link ReturnDirectMcpToolCallback} so
 * their results bypass the LLM context (see {@code ToolExecutionExecutor} and
 * {@code ObservationDispatcher} for the routing).
 *
 * <p>Configuration ({@code application.yml}):
 * <pre>
 * mateclaw:
 *   mcp:
 *     return-direct:
 *       tools:
 *         - query_employee_salary
 *         - read_medical_record
 * </pre>
 *
 * <p>Match is by tool name only (matching the upstream {@code ToolDefinition.name()}).
 * Per-server scoping is intentionally out of scope for the first iteration; if
 * the same tool name comes from two servers and only one should be direct, give
 * one of them a name prefix at the MCP server config layer.
 *
 * @author MateClaw Team
 */
@Component
@ConfigurationProperties(prefix = "mateclaw.mcp.return-direct")
public class McpReturnDirectProperties {

    /** Tool names that should be treated as returnDirect. */
    private Set<String> tools = Collections.emptySet();

    public Set<String> getTools() {
        return tools;
    }

    public void setTools(Set<String> tools) {
        this.tools = tools != null ? new LinkedHashSet<>(tools) : Collections.emptySet();
    }

    public boolean isReturnDirect(String toolName) {
        return toolName != null && tools.contains(toolName);
    }
}
