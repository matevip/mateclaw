package vip.mate.tool.guard.guardian;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.*;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具名审批守卫。
 *
 * <p>仅匹配明确配置了 {@code tool_name} 的规则，允许配置运行时 prefixed
 * 名称，或配置 MCP server 暴露的 raw tool name。
 */
@Slf4j
@Component
public class McpToolApprovalGuardian implements ToolGuardGuardian {

    private final ToolGuardRuleRegistry ruleRegistry;
    private final McpClientManager mcpClientManager;

    public McpToolApprovalGuardian(ToolGuardRuleRegistry ruleRegistry,
                                   McpClientManager mcpClientManager) {
        this.ruleRegistry = ruleRegistry;
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public boolean supports(ToolInvocationContext context) {
        return McpToolNameResolver.isMcpPrefixedName(context.toolName());
    }

    @Override
    public int priority() {
        return 180;
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        String prefixedName = context.toolName();
        String rawName = resolveRawToolName(prefixedName);
        List<GuardFinding> findings = new ArrayList<>();

        for (ToolGuardRuleEntity rule : ruleRegistry.getAllEnabled()) {
            String configuredToolName = normalize(rule.getToolName());
            if (configuredToolName == null) {
                continue;
            }
            if (!matchesRule(rule, configuredToolName, prefixedName, rawName)) {
                continue;
            }

            findings.add(new GuardFinding(
                    fallback(rule.getRuleId(), "MCP_TOOL_APPROVAL"),
                    approvalSeverity(rule.getSeverity()),
                    category(rule.getCategory()),
                    fallback(rule.getName(), "MCP 工具调用审批"),
                    fallback(rule.getDescription(), "MCP 工具调用匹配审批规则，需要用户确认后执行"),
                    fallback(rule.getRemediation(), "请确认是否允许执行该 MCP 工具"),
                    prefixedName,
                    "tool_name",
                    configuredToolName,
                    rawName != null ? rawName : prefixedName,
                    Map.of(
                            "configuredToolName", configuredToolName,
                            "prefixedToolName", prefixedName,
                            "rawToolName", rawName != null ? rawName : ""
                    )
            ));
        }

        return findings;
    }

    private static boolean matchesRule(ToolGuardRuleEntity rule, String configuredToolName,
                                       String prefixedName, String rawName) {
        if (configuredToolName.equals(prefixedName)) {
            return true;
        }
        if (rawName == null || !configuredToolName.equals(rawName)) {
            return false;
        }
        return !Boolean.TRUE.equals(rule.getBuiltin());
    }

    private String resolveRawToolName(String prefixedName) {
        McpToolNameResolver.ParsedRef parsed = McpToolNameResolver.parse(prefixedName);
        if (parsed == null) {
            return null;
        }
        try {
            for (McpSchema.Tool tool : mcpClientManager.getServerTools(parsed.serverId())) {
                String raw = tool != null ? tool.name() : null;
                if (raw != null && McpToolNameResolver.hash6(raw).equals(parsed.hash6())) {
                    return raw;
                }
            }
        } catch (Exception e) {
            log.debug("[McpToolApprovalGuardian] Failed to resolve raw MCP tool name for {}: {}",
                    prefixedName, e.getMessage());
        }
        return null;
    }

    private static GuardSeverity approvalSeverity(String configuredSeverity) {
        if (configuredSeverity == null || configuredSeverity.isBlank()) {
            return GuardSeverity.MEDIUM;
        }
        try {
            GuardSeverity severity = GuardSeverity.valueOf(configuredSeverity.trim());
            if (severity == GuardSeverity.HIGH) {
                return GuardSeverity.HIGH;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to MEDIUM so a malformed rule still asks for approval.
        }
        return GuardSeverity.MEDIUM;
    }

    private static GuardCategory category(String configuredCategory) {
        if (configuredCategory == null || configuredCategory.isBlank()) {
            return GuardCategory.CODE_EXECUTION;
        }
        try {
            return GuardCategory.valueOf(configuredCategory.trim());
        } catch (IllegalArgumentException e) {
            return GuardCategory.CODE_EXECUTION;
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
