package vip.mate.agent.graph.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Configuration for the tool-result three-layer budget (RFC-008 Phase 3).
 *
 * <p>Layer 1 — per-tool cap — is implemented inside each tool itself.
 * Layer 2 — per-result spill — when a single tool result exceeds {@link #perResultThresholdChars}
 * the full output is written to disk and only a {@link #previewHeadChars} preview
 * (plus a pointer line) is sent back to the LLM.
 * Layer 3 — per-turn aggregate budget — after all tools in a turn complete, if
 * the cumulative response size exceeds {@link #perTurnBudgetChars}, the largest
 * non-spilled responses are spilled in turn until the aggregate fits.</p>
 *
 * <p>Spill files live under {@link #storageBaseDir} when set, otherwise under
 * {@code <workspaceBasePath>/.mateclaw/tool-results/<conversationId>/} when a
 * workspace is bound to the agent, otherwise under
 * {@code ${java.io.tmpdir}/mateclaw/tool-results/<conversationId>/}.</p>
 *
 * <pre>
 * mate:
 *   agent:
 *     tool-result:
 *       enabled: true
 *       per-result-threshold-chars: 16000
 *       per-turn-budget-chars: 32000
 *       preview-head-chars: 800
 *       excluded-tool-inline-chars: 4000
 *       storage-base-dir:
 * </pre>
 */
@ConfigurationProperties(prefix = "mate.agent.tool-result")
public class ToolResultProperties {

    /** Master switch. When false, the executor falls back to plain truncation. */
    private boolean enabled = true;

    /**
     * Layer 2 — a single tool result larger than this is spilled to disk.
     * The executor evaluates this against the raw result before applying the
     * final inline cap, so oversized content is preserved before it is shortened
     * for the model request.
     */
    private int perResultThresholdChars = 16000;  // was 4000 — prevents WebSearch spill-to-disk

    /**
     * Layer 3 — aggregate cap on combined response size in one tool turn.
     * After all tools complete, the largest non-spilled responses are spilled
     * in turn until the cumulative size fits this budget.
     */
    private int perTurnBudgetChars = 32000;  // was 16000 — headroom for multi-tool turns

    /** Number of leading characters kept inline as a preview after spilling. */
    private int previewHeadChars = 800;

    /**
     * Retrieval-style tools are not spilled, but their inline content still must
     * fit the model context. When aggregate turn budget is exceeded and only
     * excluded tools remain, their results are compacted to this size.
     */
    private int excludedToolInlineChars = 2500;

    /**
     * Optional absolute path to override the default spill location.
     * When blank, falls back to {@code <workspace>/.mateclaw/tool-results/} or
     * {@code ${java.io.tmpdir}/mateclaw/tool-results/}.
     */
    private String storageBaseDir = "";

    /**
     * Tools whose results must NEVER be spilled. These are the tools the agent
     * uses to <i>retrieve</i> spilled content — spilling their output would
     * cause infinite recursion (read spill path → produces another spill →
     * agent reads new spill → …) and starve {@code MAX_TOOL_CALLS_PER_STEP}.
     *
     * <p>Defaults to file-read tools that already cap their own output internally.
     * Configurable so deployments can add more retrieval-style tools (e.g.,
     * MCP-provided readers) without code changes.</p>
     */
    private List<String> excludedTools = List.of("read_file", "read_workspace_memory_file");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPerResultThresholdChars() { return perResultThresholdChars; }
    public void setPerResultThresholdChars(int perResultThresholdChars) {
        this.perResultThresholdChars = perResultThresholdChars;
    }

    public int getPerTurnBudgetChars() { return perTurnBudgetChars; }
    public void setPerTurnBudgetChars(int perTurnBudgetChars) {
        this.perTurnBudgetChars = perTurnBudgetChars;
    }

    public int getPreviewHeadChars() { return previewHeadChars; }
    public void setPreviewHeadChars(int previewHeadChars) {
        this.previewHeadChars = previewHeadChars;
    }

    public int getExcludedToolInlineChars() { return excludedToolInlineChars; }
    public void setExcludedToolInlineChars(int excludedToolInlineChars) {
        this.excludedToolInlineChars = excludedToolInlineChars;
    }

    public String getStorageBaseDir() { return storageBaseDir; }
    public void setStorageBaseDir(String storageBaseDir) {
        this.storageBaseDir = storageBaseDir == null ? "" : storageBaseDir;
    }

    public List<String> getExcludedTools() { return excludedTools; }
    public void setExcludedTools(List<String> excludedTools) {
        this.excludedTools = excludedTools == null ? List.of() : excludedTools;
    }

    /** O(1) membership test for the exclusion list, used on every tool result. */
    public Set<String> excludedToolsSet() {
        return Set.copyOf(excludedTools);
    }
}
