package vip.mate.tool.guard.guardian;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.*;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolApprovalGuardianTest {

    private ToolGuardRuleRegistry ruleRegistry;
    private McpClientManager mcpClientManager;
    private McpToolApprovalGuardian guardian;

    @BeforeEach
    void setUp() {
        ruleRegistry = mock(ToolGuardRuleRegistry.class);
        mcpClientManager = mock(McpClientManager.class);
        guardian = new McpToolApprovalGuardian(ruleRegistry, mcpClientManager);
    }

    @Test
    @DisplayName("prefixed MCP tool name rule creates approval finding")
    void prefixedRuleMatches() {
        String toolName = McpToolNameResolver.prefixedName(42L, "search_entity");
        when(ruleRegistry.getAllEnabled()).thenReturn(List.of(rule("MCP_SEARCH", toolName, "HIGH")));

        List<GuardFinding> findings = guardian.evaluate(context(toolName));

        assertEquals(1, findings.size());
        assertEquals("MCP_SEARCH", findings.get(0).ruleId());
        assertEquals(GuardSeverity.HIGH, findings.get(0).severity());
        assertEquals(toolName, findings.get(0).toolName());
        assertEquals(toolName, findings.get(0).matchedPattern());
    }

    @Test
    @DisplayName("raw MCP tool name rule matches through server tool cache")
    void rawRuleMatches() {
        String rawName = "search_entity";
        String toolName = McpToolNameResolver.prefixedName(42L, rawName);
        when(ruleRegistry.getAllEnabled()).thenReturn(List.of(rule("MCP_SEARCH_RAW", rawName, "MEDIUM")));
        when(mcpClientManager.getServerTools(42L)).thenReturn(List.of(tool(rawName)));

        List<GuardFinding> findings = guardian.evaluate(context(toolName));

        assertEquals(1, findings.size());
        assertEquals("MCP_SEARCH_RAW", findings.get(0).ruleId());
        assertEquals(rawName, findings.get(0).matchedPattern());
        assertEquals(rawName, findings.get(0).snippet());
    }

    @Test
    @DisplayName("blank tool_name rules are ignored for MCP approval")
    void blankToolRuleIgnored() {
        String toolName = McpToolNameResolver.prefixedName(42L, "search_entity");
        when(ruleRegistry.getAllEnabled()).thenReturn(List.of(rule("GLOBAL", "", "HIGH")));

        List<GuardFinding> findings = guardian.evaluate(context(toolName));

        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("raw name matching does not reuse builtin non-MCP rules")
    void rawRuleDoesNotMatchBuiltinRule() {
        String rawName = "execute_shell_command";
        String toolName = McpToolNameResolver.prefixedName(42L, rawName);
        ToolGuardRuleEntity builtinShellRule = rule("SHELL_RM", rawName, "HIGH");
        builtinShellRule.setBuiltin(true);
        when(ruleRegistry.getAllEnabled()).thenReturn(List.of(builtinShellRule));
        when(mcpClientManager.getServerTools(42L)).thenReturn(List.of(tool(rawName)));

        List<GuardFinding> findings = guardian.evaluate(context(toolName));

        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("critical severity is coerced to approval severity")
    void criticalSeverityDoesNotBlock() {
        String toolName = McpToolNameResolver.prefixedName(42L, "search_entity");
        when(ruleRegistry.getAllEnabled()).thenReturn(List.of(rule("MCP_SEARCH", toolName, "CRITICAL")));

        List<GuardFinding> findings = guardian.evaluate(context(toolName));
        GuardDecision decision = new vip.mate.tool.guard.engine.ToolPolicyResolver()
                .resolve(findings, context(toolName));

        assertEquals(1, findings.size());
        assertEquals(GuardSeverity.MEDIUM, findings.get(0).severity());
        assertEquals(GuardDecision.NEEDS_APPROVAL, decision);
    }

    @Test
    @DisplayName("non-MCP tools are not supported")
    void nonMcpUnsupported() {
        assertFalse(guardian.supports(context("execute_shell_command")));
    }

    private static ToolInvocationContext context(String toolName) {
        return ToolInvocationContext.of(toolName, "{}", "conv-1", "agent-1");
    }

    private static ToolGuardRuleEntity rule(String ruleId, String toolName, String severity) {
        ToolGuardRuleEntity rule = new ToolGuardRuleEntity();
        rule.setRuleId(ruleId);
        rule.setName(ruleId);
        rule.setDescription(ruleId);
        rule.setToolName(toolName);
        rule.setSeverity(severity);
        rule.setCategory(GuardCategory.CODE_EXECUTION.name());
        rule.setDecision(GuardDecision.NEEDS_APPROVAL.name());
        rule.setPattern(".*");
        rule.setEnabled(true);
        return rule;
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, null, "Test tool", null, null, null, null);
    }
}
