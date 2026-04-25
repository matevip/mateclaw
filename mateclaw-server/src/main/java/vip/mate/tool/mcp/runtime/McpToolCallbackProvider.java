package vip.mate.tool.mcp.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具回调提供者
 * <p>
 * 将所有 active MCP clients 暴露的 tools 统一为 ToolCallbackProvider，
 * 供 ToolRegistry 收集并注入 AgentToolSet。
 * <p>
 * 每次调用 getToolCallbacks() 都会从 McpClientManager 获取最新的 active tools，
 * 因此新增/删除 MCP server 后无需重启即可生效。
 *
 * <p>RFC-052: tools listed in {@link McpReturnDirectProperties} are wrapped in
 * {@link ReturnDirectMcpToolCallback} so their results bypass the LLM context.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolCallbackProvider implements ToolCallbackProvider {

    private final McpClientManager mcpClientManager;
    private final McpReturnDirectProperties returnDirectProperties;

    @Override
    public ToolCallback[] getToolCallbacks() {
        try {
            var callbacks = mcpClientManager.getAllToolCallbacks();
            if (!callbacks.isEmpty()) {
                log.debug("McpToolCallbackProvider providing {} tools from {} active MCP servers",
                        callbacks.size(), mcpClientManager.getActiveCount());
            }

            // RFC-052: opt-in returnDirect wrapping. The decorator only changes
            // ToolMetadata.returnDirect(); guard/approval/observability still
            // see the original callback through the wrapper.
            List<ToolCallback> wrapped = new ArrayList<>(callbacks.size());
            for (ToolCallback cb : callbacks) {
                String name = cb.getToolDefinition() != null ? cb.getToolDefinition().name() : null;
                if (returnDirectProperties.isReturnDirect(name)) {
                    log.info("[McpToolCallbackProvider] wrapping MCP tool '{}' as returnDirect (RFC-052)", name);
                    wrapped.add(new ReturnDirectMcpToolCallback(cb));
                } else {
                    wrapped.add(cb);
                }
            }
            return wrapped.toArray(new ToolCallback[0]);
        } catch (Exception e) {
            log.warn("Failed to collect MCP tool callbacks: {}", e.getMessage());
            return new ToolCallback[0];
        }
    }
}
