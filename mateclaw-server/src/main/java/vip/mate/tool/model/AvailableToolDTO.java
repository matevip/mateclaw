package vip.mate.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Picker DTO for the unified agent tool selector.
 *
 * <p>One row per atomic tool the agent can be bound to — built-in tools
 * appear under {@code source="builtin"}, MCP tools appear under
 * {@code source="mcp"} and are grouped by their server. The {@link #name}
 * field is the value the UI saves into {@code mate_agent_tool.tool_name};
 * for MCP tools it is the prefixed callback name returned by the resolver
 * so picker and runtime use the same key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableToolDTO {

    /**
     * Stable per-row identifier for the picker. The frontend uses this as
     * the {@code v-for :key} so two rows with the same prefixed
     * {@link #name} (e.g. a hash-collision pair) don't reuse each other's
     * DOM state. Server-assigned, opaque to the client.
     */
    private String rowId;

    /** {@code "builtin"} or {@code "mcp"}. */
    private String source;

    /** MCP server id when {@code source == "mcp"}; null otherwise. */
    private Long providerId;

    /** Human-readable provider label — server display name for MCP, empty for builtin. */
    private String providerName;

    /** What the UI saves into {@code mate_agent_tool.tool_name}. */
    private String name;

    /** Original raw tool name as advertised upstream. UI shows this. */
    private String rawName;

    /** Tool description shown as the picker subtitle. */
    private String description;

    /** Group label for the picker UI section header (e.g. {@code "MCP · github"}). */
    private String group;

    /** Stable group key for collapse/expand state across renames. */
    private String groupId;

    /**
     * {@code true} when the entry comes from the cache while the upstream
     * MCP server is currently disconnected. The picker should grey it out;
     * runtime callbacks for stale tools are absent so the LLM cannot call
     * them either way.
     */
    private boolean stale;

    /**
     * {@code false} → the picker must disable selection. Currently set when
     * a hash collision was detected for the same (serverId, prefixed-name)
     * pair. {@code true} for everything that can be safely bound.
     */
    private boolean available;

    /**
     * Machine-readable cause when {@link #available} is {@code false}.
     * Examples: {@code "HASH_COLLISION"} (with the conflicting raw name in
     * a follow-up message), {@code "DUPLICATE_RAW_NAME"}.
     */
    private String unavailableReason;

    public static AvailableToolDTO fromBuiltin(ToolEntity t) {
        return AvailableToolDTO.builder()
                // Built-in tool names are unique by ToolRegistry contract,
                // so name suffices as a stable rowId.
                .rowId("builtin#" + t.getName())
                .source("builtin")
                .providerId(null)
                .providerName(null)
                .name(t.getName())
                .rawName(t.getName())
                .description(t.getDescription() != null ? t.getDescription() : "")
                .group("builtin")
                .groupId("builtin")
                .stale(false)
                .available(true)
                .unavailableReason(null)
                .build();
    }
}
