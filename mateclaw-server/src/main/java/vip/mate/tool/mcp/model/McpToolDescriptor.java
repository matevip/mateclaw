package vip.mate.tool.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC-03 Lane A3 — DTO returned by {@code GET /api/v1/mcp/servers/{id}/tools}.
 *
 * <p>Mirrors the discovered subset of {@code io.modelcontextprotocol.spec.McpSchema.Tool}
 * that the UI actually needs (name, description, params schema). Stays in
 * {@code vip.mate.tool.mcp.model} so the public API doesn't leak the
 * mcp-sdk transport types — those have evolved across SDK releases and
 * we don't want every UI bump to track them.
 *
 * <p>{@code inputSchema} is left as a raw {@link Object} so Jackson
 * serializes whatever JSON-Schema shape the server reported; consumers
 * (UI, third-party clients) pass it straight through to JSON Schema
 * renderers / validators.
 *
 * <p>{@link JsonInclude}{@code .NON_NULL} on the type keeps the wire
 * payload tight when servers omit optional fields (description is
 * optional in the MCP spec).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolDescriptor(
        String name,
        String description,
        Object inputSchema
) {
}
