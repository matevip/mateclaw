package vip.mate.agent.graph.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tool-result spill store implementing layers 2 and 3 of the RFC-008 Phase 3
 * three-layer budget. Layer 1 (per-tool cap) lives inside individual tools.
 *
 * <p><b>Layer 2 — per-result spill</b> ({@link #persistIfOversized}): a single
 * tool result that exceeds the configured threshold is written to disk and
 * the in-memory copy is replaced with a short preview plus a pointer line so
 * the LLM can use {@code read_file} to retrieve the full text on demand.</p>
 *
 * <p><b>Layer 3 — per-turn aggregate budget</b>
 * ({@link #enforceTurnBudget}): after every tool in one turn has executed,
 * if the combined response size still exceeds the turn budget, the largest
 * non-spilled responses are spilled in turn until the aggregate fits.</p>
 *
 * <p>Spill files live under one of, in order:</p>
 * <ol>
 *   <li>{@code ToolResultProperties.storageBaseDir} when explicitly set</li>
 *   <li>{@code <workspaceBasePath>/.mateclaw/tool-results/<conversationId>/} when a workspace is bound</li>
 *   <li>{@code ${java.io.tmpdir}/mateclaw/tool-results/<conversationId>/} as the universal fallback</li>
 * </ol>
 *
 * <p>Failures (disk full, IO error) degrade silently: the original result is
 * returned unchanged so the agent keeps working. Errors are logged at WARN.</p>
 *
 * <p>This class does <b>not</b> manage GC. Spill files accumulate until manually
 * cleaned. A scheduled cleanup job is tracked as a Phase 3 follow-up.</p>
 */
@Slf4j
@Component
@Configuration
@EnableConfigurationProperties(ToolResultProperties.class)
public class ToolResultStorage {

    /** Marker placed in the in-context preview so callers and tools can recognize spill output. */
    public static final String SPILL_MARKER_PREFIX = "[mate-tool-result-spill]";

    private final ToolResultProperties props;

    public ToolResultStorage(ToolResultProperties props) {
        this.props = props;
    }

    /**
     * Layer 2. If {@code result} exceeds the per-result threshold, write the full
     * text to a spill file and return a preview-plus-pointer string. Otherwise
     * return the original result unchanged.
     *
     * @param result          the raw tool output (may be null)
     * @param toolName        used in the preview header so the LLM knows which tool produced it
     * @param toolUseId       unique within a conversation; becomes the spill file's basename
     * @param conversationId  scopes spill files by conversation
     * @param workspaceBasePath agent's workspace base path; may be null/blank
     */
    public String persistIfOversized(String result, String toolName, String toolUseId,
                                     String conversationId, String workspaceBasePath) {
        if (!props.isEnabled() || result == null) {
            return result;
        }
        if (result.length() <= props.getPerResultThresholdChars()) {
            return result;
        }
        Path file = spillFor(conversationId, toolUseId, workspaceBasePath);
        if (file == null) {
            return result;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, result, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            log.warn("[ToolResultStorage] spill write failed for tool={} convId={} ({}); keeping original",
                    toolName, conversationId, ioe.getMessage());
            return result;
        }
        return buildPreview(result, toolName, file);
    }

    /**
     * Layer 3. Walk the responses; if their aggregate length exceeds the turn
     * budget, spill the largest remaining non-spilled result and recompute.
     * Mutates the returned list in place by replacing oversized responses.
     */
    public List<ToolResponseMessage.ToolResponse> enforceTurnBudget(
            List<ToolResponseMessage.ToolResponse> responses,
            String conversationId,
            String workspaceBasePath) {
        if (!props.isEnabled() || responses == null || responses.isEmpty()) {
            return responses;
        }
        int budget = props.getPerTurnBudgetChars();
        int aggregate = aggregateSize(responses);
        if (aggregate <= budget) {
            return responses;
        }

        log.info("[ToolResultStorage] turn budget exceeded: {} chars > {} (responses={})",
                aggregate, budget, responses.size());

        List<ToolResponseMessage.ToolResponse> mutable = new ArrayList<>(responses);

        while (aggregate > budget) {
            // Find the largest response that has not yet been spilled.
            int targetIdx = -1;
            int targetLen = -1;
            for (int i = 0; i < mutable.size(); i++) {
                String body = mutable.get(i).responseData();
                if (body == null || body.startsWith(SPILL_MARKER_PREFIX)) continue;
                if (body.length() > targetLen) {
                    targetLen = body.length();
                    targetIdx = i;
                }
            }
            if (targetIdx < 0) {
                // Nothing left to spill; aggregate is already as small as we can make it.
                log.warn("[ToolResultStorage] aggregate still {} chars after spilling everything eligible",
                        aggregate);
                break;
            }
            ToolResponseMessage.ToolResponse target = mutable.get(targetIdx);
            Path file = spillFor(conversationId, target.id(), workspaceBasePath);
            if (file == null) {
                break;
            }
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, target.responseData(), StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                log.warn("[ToolResultStorage] spill write failed during turn budget enforcement: {}",
                        ioe.getMessage());
                break;
            }
            String preview = buildPreview(target.responseData(), target.name(), file);
            mutable.set(targetIdx, new ToolResponseMessage.ToolResponse(target.id(), target.name(), preview));
            aggregate = aggregateSize(mutable);
        }
        return mutable;
    }

    private static int aggregateSize(List<ToolResponseMessage.ToolResponse> responses) {
        int sum = 0;
        for (ToolResponseMessage.ToolResponse r : responses) {
            if (r.responseData() != null) sum += r.responseData().length();
        }
        return sum;
    }

    private String buildPreview(String fullResult, String toolName, Path spillFile) {
        int previewLen = Math.min(props.getPreviewHeadChars(), fullResult.length());
        String head = fullResult.substring(0, previewLen);
        return SPILL_MARKER_PREFIX
                + " tool=" + toolName
                + " full_chars=" + fullResult.length()
                + " path=" + spillFile.toAbsolutePath()
                + "\n[Preview — first " + previewLen + " of " + fullResult.length()
                + " chars. Use read_file with the path above to retrieve the rest.]\n"
                + head
                + "\n…[truncated]";
    }

    /**
     * Resolve the spill file path for a given (conversationId, toolUseId).
     * Returns {@code null} if no usable directory can be determined.
     */
    private Path spillFor(String conversationId, String toolUseId, String workspaceBasePath) {
        String safeConv = sanitize(conversationId);
        String safeId = sanitize(toolUseId);
        if (safeId.isEmpty()) {
            safeId = "noid-" + System.nanoTime();
        }
        Path base = resolveBaseDir(workspaceBasePath);
        if (base == null) return null;
        return base.resolve(safeConv).resolve(safeId + ".txt");
    }

    private Path resolveBaseDir(String workspaceBasePath) {
        if (!props.getStorageBaseDir().isEmpty()) {
            return Paths.get(props.getStorageBaseDir());
        }
        if (workspaceBasePath != null && !workspaceBasePath.isBlank()) {
            return Paths.get(workspaceBasePath, ".mateclaw", "tool-results");
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null || tmp.isEmpty()) return null;
        return Paths.get(tmp, "mateclaw", "tool-results");
    }

    /** Strip path separators and reserved characters so user-supplied IDs cannot escape the directory. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Test/admin helper: lexicographic ordering by length, descending. Not used at runtime. */
    static Comparator<ToolResponseMessage.ToolResponse> byBodyLengthDesc() {
        return (a, b) -> Integer.compare(
                b.responseData() == null ? 0 : b.responseData().length(),
                a.responseData() == null ? 0 : a.responseData().length());
    }
}
