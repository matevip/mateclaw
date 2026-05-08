package vip.mate.workflow.compiler;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.compiler.ir.WorkflowStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compile-time guard against accessing a sub-field on a step output whose
 * content type is plain text. The rule:
 * <ul>
 *   <li>{@code outputs.X} is always allowed — the value is always defined as
 *       a string for text outputs and as a parsed JSON for json outputs.</li>
 *   <li>{@code outputs.X.field} is only allowed when step X has
 *       {@code outputContentType: json}; on a text output the access raises
 *       a compile-time error.</li>
 * </ul>
 *
 * <p>The check uses a regex over the expression / template source rather
 * than a full Pebble AST walk. This is good enough for v0 — the only
 * sub-field reads that matter are the literal {@code outputs.<name>.<field>}
 * pattern; users who genuinely need richer JSON paths use the {@code | jq}
 * filter (added in Lane 2) instead of dotted access.
 */
@Component
public class OutputContentTypeChecker {

    private static final Pattern OUTPUT_FIELD_REF = Pattern.compile(
            "\\boutputs\\.([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_.]*)");

    public List<CompileError> check(WorkflowGraph graph) {
        if (graph == null || graph.steps().isEmpty()) {
            return List.of();
        }
        Map<String, String> outputVarToContentType = collectOutputVars(graph);

        List<CompileError> errors = new ArrayList<>();
        for (int i = 0; i < graph.steps().size(); i++) {
            WorkflowStep s = graph.steps().get(i);
            // Each step contributes a few sources that may carry expressions:
            // promptTemplate, conditional.expression, dispatch_channel.content,
            // write_memory.content. Walk them all.
            checkSource(i, "promptTemplate", s.promptTemplate(), outputVarToContentType, errors);
            if (s.mode() instanceof StepMode.Conditional c) {
                checkSource(i, "mode.expression", c.expression(), outputVarToContentType, errors);
            } else if (s.mode() instanceof StepMode.DispatchChannel d) {
                checkSource(i, "mode.content", d.content(), outputVarToContentType, errors);
            } else if (s.mode() instanceof StepMode.WriteMemory w) {
                checkSource(i, "mode.content", w.content(), outputVarToContentType, errors);
            }
        }
        return errors;
    }

    private static Map<String, String> collectOutputVars(WorkflowGraph graph) {
        Map<String, String> out = new HashMap<>();
        for (WorkflowStep s : graph.steps()) {
            String var = s.outputVar();
            if (var != null && !var.isBlank()) {
                out.put(var, s.effectiveOutputContentType());
            }
        }
        return out;
    }

    private static void checkSource(int stepIndex, String fieldPath, String source,
                                    Map<String, String> outputContentTypes,
                                    List<CompileError> errors) {
        if (source == null || source.isEmpty()) {
            return;
        }
        Matcher m = OUTPUT_FIELD_REF.matcher(source);
        while (m.find()) {
            String varName = m.group(1);
            String fieldRest = m.group(2);
            String contentType = outputContentTypes.get(varName);
            if (contentType == null) {
                errors.add(CompileError.stepField(stepIndex, fieldPath,
                        "expression.unknown_output_var",
                        "expression references unknown outputVar '" + varName + "'"));
                continue;
            }
            if (!"json".equals(contentType)) {
                errors.add(CompileError.stepField(stepIndex, fieldPath,
                        "expression.field_on_text_output",
                        "cannot access '." + fieldRest + "' on output '" + varName
                                + "' because its outputContentType is text — "
                                + "set outputContentType: json on the producing step"));
            }
        }
    }
}
