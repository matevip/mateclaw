package vip.mate.workflow.compiler;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.WorkflowGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-level compile entry point. Runs each pass in order and collects the
 * resulting diagnostics into a single {@link Result}. Phases short-circuit
 * on the kind of failure that would invalidate later passes:
 * <ul>
 *   <li>Parse failure raises a {@link WorkflowParseException} immediately —
 *       structural validation needs an IR.</li>
 *   <li>Schema, expression, and ACL checks are independent and all run, so
 *       a single compile call surfaces every problem instead of
 *       error-by-error round-trips.</li>
 * </ul>
 */
@Component
public class WorkflowCompiler {

    private final WorkflowParser parser;
    private final WorkflowSchemaValidator schemaValidator;
    private final OutputContentTypeChecker outputContentTypeChecker;
    private final WorkflowAclValidator aclValidator;
    private final PebbleSubsetEvaluator pebbleEvaluator;

    public WorkflowCompiler(WorkflowParser parser,
                            WorkflowSchemaValidator schemaValidator,
                            OutputContentTypeChecker outputContentTypeChecker,
                            WorkflowAclValidator aclValidator,
                            PebbleSubsetEvaluator pebbleEvaluator) {
        this.parser = parser;
        this.schemaValidator = schemaValidator;
        this.outputContentTypeChecker = outputContentTypeChecker;
        this.aclValidator = aclValidator;
        this.pebbleEvaluator = pebbleEvaluator;
    }

    public Result compile(String json, PublishContext ctx, WorkflowAclPort aclPort) {
        WorkflowGraph graph = parser.parse(json);
        List<CompileError> errors = new ArrayList<>();
        errors.addAll(schemaValidator.validate(graph));
        errors.addAll(checkExpressionSyntax(graph));
        errors.addAll(outputContentTypeChecker.check(graph));
        if (aclPort != null) {
            errors.addAll(aclValidator.validate(graph, ctx, aclPort));
        }
        return new Result(graph, Collections.unmodifiableList(errors));
    }

    private List<CompileError> checkExpressionSyntax(WorkflowGraph graph) {
        List<CompileError> errors = new ArrayList<>();
        for (int i = 0; i < graph.steps().size(); i++) {
            var step = graph.steps().get(i);
            if (step.mode() instanceof vip.mate.workflow.compiler.ir.StepMode.Conditional c
                    && c.expression() != null && !c.expression().isBlank()) {
                try {
                    pebbleEvaluator.parseExpression(c.expression());
                } catch (ExpressionException e) {
                    errors.add(CompileError.stepField(i, "mode.expression",
                            "expression.parse_failed", e.getMessage()));
                }
            }
            if (step.promptTemplate() != null && !step.promptTemplate().isBlank()) {
                try {
                    pebbleEvaluator.parseTemplate(step.promptTemplate());
                } catch (ExpressionException e) {
                    errors.add(CompileError.stepField(i, "promptTemplate",
                            "expression.parse_failed", e.getMessage()));
                }
            }
            if (step.mode() instanceof vip.mate.workflow.compiler.ir.StepMode.DispatchChannel d
                    && d.content() != null && !d.content().isBlank()) {
                try {
                    pebbleEvaluator.parseTemplate(d.content());
                } catch (ExpressionException e) {
                    errors.add(CompileError.stepField(i, "mode.content",
                            "expression.parse_failed", e.getMessage()));
                }
            }
            if (step.mode() instanceof vip.mate.workflow.compiler.ir.StepMode.WriteMemory w
                    && w.content() != null && !w.content().isBlank()) {
                try {
                    pebbleEvaluator.parseTemplate(w.content());
                } catch (ExpressionException e) {
                    errors.add(CompileError.stepField(i, "mode.content",
                            "expression.parse_failed", e.getMessage()));
                }
            }
        }
        return errors;
    }

    /**
     * Compile result. Callers that want strictness can do
     * {@code result.requireOk()}; the publish flow uses that to refuse
     * persisting a new revision row when there are errors.
     */
    public record Result(WorkflowGraph graph, List<CompileError> errors) {
        public boolean ok() { return errors.isEmpty(); }

        public void requireOk() {
            if (!ok()) {
                throw new WorkflowCompileFailedException(errors);
            }
        }
    }
}
