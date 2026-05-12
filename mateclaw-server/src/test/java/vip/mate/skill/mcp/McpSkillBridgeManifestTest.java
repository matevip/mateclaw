package vip.mate.skill.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;
import vip.mate.tool.mcp.service.McpServerService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts the manifest writes prefixed callback names into
 * {@code allowedTools} (so {@code ResolvedSkill.getEffectiveAllowedTools()}
 * returns names that {@link vip.mate.tool.mcp.runtime.McpClientManager} also
 * registers) and that the cache-first / live-fallback ordering holds.
 */
class McpSkillBridgeManifestTest {

    private McpServerService mcpServerService;
    private McpClientManager mcpClientManager;
    private McpSkillBridge bridge;

    @BeforeEach
    void setUp() {
        mcpServerService = mock(McpServerService.class);
        mcpClientManager = mock(McpClientManager.class);
        bridge = new McpSkillBridge(mcpServerService, mcpClientManager, new ObjectMapper());
    }

    @Test
    @DisplayName("manifest emits prefixed tool names matching the resolver output")
    void allowedToolsArePrefixed() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(toolsJson("create_issue", "list_issues"));
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        // The synthesized SkillEntity carries manifest_json — parse it back
        // and check allowedTools contains the prefixed names.
        String manifestJson = entity.getManifestJson();
        assertTrue(manifestJson.contains("\"" + McpToolNameResolver.prefixedName(42L, "create_issue") + "\""),
                "expected prefixed create_issue in manifest, got: " + manifestJson);
        assertTrue(manifestJson.contains("\"" + McpToolNameResolver.prefixedName(42L, "list_issues") + "\""),
                "expected prefixed list_issues in manifest, got: " + manifestJson);
    }

    @Test
    @DisplayName("manifest reads from tools_cache_json when present, never hits the live runtime")
    void readsFromCacheFirst() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(toolsJson("create_issue"));
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));

        bridge.listMcpDerivedSkillEntities();

        verify(mcpClientManager, never()).getServerTools(anyLong());
    }

    @Test
    @DisplayName("manifest falls back to live runtime when cache is absent")
    void fallsBackToLiveWhenCacheMissing() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(null); // first-ever connect just happened, cache not yet written
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));
        when(mcpClientManager.getServerTools(42L)).thenReturn(List.of(
                fakeTool("create_issue"),
                fakeTool("list_issues")));

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        verify(mcpClientManager, times(1)).getServerTools(42L);
        assertTrue(entity.getManifestJson().contains(McpToolNameResolver.prefixedName(42L, "create_issue")));
    }

    @Test
    @DisplayName("disconnected server with empty cache yields an empty allowedTools — no exceptions")
    void disconnectedAndEmptyCacheIsHandled() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson("");
        server.setLastStatus("disconnected");
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));
        when(mcpClientManager.getServerTools(42L)).thenReturn(List.of());

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        // The manifest should still serialize successfully — the picker can
        // still show the skill in stale mode. Jackson may omit the empty
        // allowedTools list entirely, so just assert no prefixed names
        // leaked in (which would indicate a stale-cache regression).
        assertEquals("github", entity.getName());
        assertTrue(!entity.getManifestJson().contains("mcp_42_"),
                "no prefixed tool name expected, got: " + entity.getManifestJson());
    }

    @Test
    @DisplayName("two servers exposing the same raw tool name produce distinct prefixed names")
    void twoServersSameRawNameDistinct() {
        McpServerEntity a = newServer(42L, "github");
        a.setToolsCacheJson(toolsJson("search"));
        McpServerEntity b = newServer(43L, "filesystem");
        b.setToolsCacheJson(toolsJson("search"));
        when(mcpServerService.listEnabled()).thenReturn(List.of(a, b));

        List<SkillEntity> entities = bridge.listMcpDerivedSkillEntities();

        Set<String> prefixed = Set.of(
                McpToolNameResolver.prefixedName(42L, "search"),
                McpToolNameResolver.prefixedName(43L, "search"));
        assertEquals(2, prefixed.size());
        assertTrue(entities.get(0).getManifestJson().contains(McpToolNameResolver.prefixedName(42L, "search")));
        assertTrue(entities.get(1).getManifestJson().contains(McpToolNameResolver.prefixedName(43L, "search")));
    }

    private static McpServerEntity newServer(long id, String name) {
        McpServerEntity s = new McpServerEntity();
        s.setId(id);
        s.setName(name);
        s.setEnabled(true);
        s.setTransport("stdio");
        s.setCommand("/usr/bin/echo");
        s.setLastStatus("connected");
        return s;
    }

    private static String toolsJson(String... names) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(names[i])
                    .append("\",\"description\":\"\",\"inputSchema\":{}}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static McpSchema.Tool fakeTool(String name) {
        return new McpSchema.Tool(
                name,
                /* title */ name,
                "Test tool",
                /* inputSchema */ null,
                /* outputSchema */ null,
                /* annotations */ null,
                /* meta */ null);
    }
}
