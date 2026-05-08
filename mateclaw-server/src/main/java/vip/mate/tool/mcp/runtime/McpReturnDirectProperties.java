package vip.mate.tool.mcp.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MCP tool return-direct opt-in list.
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
 *         - query_employee_salary           # raw upstream name (legacy form, still supported)
 *         - mcp_42_query_employee_salary_aB3xYz  # full prefixed callback name (server-scoped, precise)
 * </pre>
 *
 * <p><b>Two accepted name forms</b>:
 * <ul>
 *   <li><b>Raw upstream name</b> ({@code query_employee_salary}) —
 *       matches the wrapped callback's underlying delegate name. This is
 *       the form that existed before the runtime started prefixing
 *       callback names; existing deployments keep working unchanged.
 *       A raw name matches every server that exposes that tool, so use
 *       this form when a sensitive name should be direct on every
 *       server it appears.</li>
 *   <li><b>Prefixed callback name</b>
 *       ({@code mcp_<serverId>_<slug>_<hash6>}) — server-scoped, precise.
 *       Use this form when only one of several MCP servers exposing the
 *       same raw name should be treated as direct.</li>
 * </ul>
 * Matching happens via {@link #matches(String, String)} from the consumer
 * side; see {@link McpToolCallbackProvider#getToolCallbacks} for the call
 * site.
 *
 * @author MateClaw Team
 */
@Component
@ConfigurationProperties(prefix = "mateclaw.mcp.return-direct")
public class McpReturnDirectProperties {

    /** Tool names (raw or prefixed) that should be treated as returnDirect. */
    private Set<String> tools = Collections.emptySet();

    public Set<String> getTools() {
        return tools;
    }

    public void setTools(Set<String> tools) {
        this.tools = tools != null ? new LinkedHashSet<>(tools) : Collections.emptySet();
    }

    /**
     * Single-string check kept for back-compat with any caller that has
     * only one form of the name. Prefer {@link #matches(String, String)}
     * from the wrapping path so both prefixed and raw forms get a chance
     * to match.
     */
    public boolean isReturnDirect(String toolName) {
        return toolName != null && tools.contains(toolName);
    }

    /**
     * @return {@code true} iff the configured set contains either the
     *         prefixed callback name OR the raw upstream tool name. Either
     *         argument may be {@code null} (e.g. when a callback isn't a
     *         {@link PrefixedNameToolCallback} so no raw form is
     *         available); the other is checked on its own.
     */
    public boolean matches(String prefixedName, String rawName) {
        if (tools.isEmpty()) return false;
        if (prefixedName != null && tools.contains(prefixedName)) return true;
        if (rawName != null && tools.contains(rawName)) return true;
        return false;
    }
}
