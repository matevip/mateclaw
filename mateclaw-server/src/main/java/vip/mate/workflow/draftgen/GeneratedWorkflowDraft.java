package vip.mate.workflow.draftgen;

import java.util.List;
import java.util.Map;

/**
 * Result of a natural-language → workflow draft generation. Crosses the
 * REST boundary as JSON; the controller returns this verbatim.
 *
 * <p>{@code draftJson} is the {@code {"steps":[...]}} shape the
 * runtime expects — same string the UI's JSON tab edits, same one
 * {@link vip.mate.workflow.compiler.WorkflowCompiler} consumes. The
 * generator pre-runs the compiler against it and reports compile
 * failures via {@code compileErrors} without auto-publishing — v0
 * always lets the operator review before pushing the row to a
 * revision.
 *
 * <p>{@code triggerDrafts} is a list of suggested triggers the user
 * can choose to create alongside the workflow; they're NOT created
 * automatically and arrive with {@code enabled=false} per the
 * generator system prompt's contract.
 *
 * <p>{@code warnings} / {@code missingFields} surface anywhere the
 * model had to hedge — unfilled {@code TODO_*} placeholders, ambiguous
 * approval policy, missing channel target. The UI displays these
 * inline so the operator can finish the draft.
 */
public record GeneratedWorkflowDraft(
        String name,
        String description,
        String draftJson,
        List<Map<String, Object>> triggerDrafts,
        List<String> warnings,
        List<String> missingFields,
        Double confidence,
        boolean compileOk,
        List<vip.mate.workflow.compiler.CompileError> compileErrors
) {}
