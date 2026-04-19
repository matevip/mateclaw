package vip.mate.agent.graph.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 *       per-result-threshold-chars: 4000
 *       per-turn-budget-chars: 16000
 *       preview-head-chars: 800
 *       storage-base-dir:
 * </pre>
 */
@ConfigurationProperties(prefix = "mate.agent.tool-result")
public class ToolResultProperties {

    /** Master switch. When false, the executor falls back to plain truncation. */
    private boolean enabled = true;

    /** A single tool result larger than this is spilled to disk. */
    private int perResultThresholdChars = 4000;

    /** Aggregate cap on combined response size in one tool turn. */
    private int perTurnBudgetChars = 16000;

    /** Number of leading characters kept inline as a preview after spilling. */
    private int previewHeadChars = 800;

    /**
     * Optional absolute path to override the default spill location.
     * When blank, falls back to {@code <workspace>/.mateclaw/tool-results/} or
     * {@code ${java.io.tmpdir}/mateclaw/tool-results/}.
     */
    private String storageBaseDir = "";

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

    public String getStorageBaseDir() { return storageBaseDir; }
    public void setStorageBaseDir(String storageBaseDir) {
        this.storageBaseDir = storageBaseDir == null ? "" : storageBaseDir;
    }
}
