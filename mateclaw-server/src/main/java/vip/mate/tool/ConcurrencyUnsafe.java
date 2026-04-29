package vip.mate.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link org.springframework.ai.tool.annotation.Tool}-annotated method
 * as <b>not safe to run concurrently with itself or with other tools that
 * touch the same state</b>.
 *
 * <p>Read by {@link vip.mate.tool.ToolConcurrencyRegistry} at startup. The
 * registry returns {@code true} from
 * {@link ToolConcurrencyRegistry#isUnsafe(String)} for marked tools, which
 * causes {@code ToolExecutionExecutor} to execute them in their own batch
 * (no parallelism, no overlap with the surrounding safe batch).</p>
 *
 * <p>Use cases:</p>
 * <ul>
 *   <li>File writes / edits ({@code WriteFileTool}, {@code EditFileTool})</li>
 *   <li>Shell command execution ({@code ShellExecuteTool})</li>
 *   <li>Stateful workspace mutations ({@code WorkspaceMemoryTool}, {@code SkillManageTool})</li>
 *   <li>Persistent operations on shared resources ({@code CronJobTool}, {@code DatasourceTool})</li>
 *   <li>Long-running generative tools where API rate limits forbid parallel calls
 *       ({@code ImageGenerateTool}, {@code VideoGenerateTool})</li>
 * </ul>
 *
 * <p>Read-only and idempotent tools should remain unannotated; they will be
 * batched together for parallel execution by the executor.</p>
 *
 * <p>For MCP-provided tools the executor will eventually consult the
 * {@code annotations.readOnlyHint} field from the MCP {@code Tool} schema;
 * that integration is tracked as a Phase 4 follow-up. Until then MCP tools
 * default to safe (their pre-existing behavior).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConcurrencyUnsafe {

    /**
     * Optional human-readable reason. Surfaced in startup logs to help
     * operators audit which tools have been marked unsafe.
     */
    String value() default "";
}
