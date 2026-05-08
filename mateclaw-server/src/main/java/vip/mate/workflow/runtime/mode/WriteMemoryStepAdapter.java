package vip.mate.workflow.runtime.mode;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.PebbleSubsetEvaluator;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.runtime.MemoryWriter;
import vip.mate.workflow.runtime.PayloadStore;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

/**
 * {@code write_memory} — render the content template, then delegate to
 * {@link MemoryWriter} to apply the configured merge strategy against the
 * target memory file. The rendered content is also written through to
 * {@code mate_workflow_payload} so the step row's {@code output_ref} points
 * at the exact text that was merged in (independent of the file's final
 * post-merge state, which downstream tooling may want to diff).
 */
@Component
public class WriteMemoryStepAdapter implements StepAdapter {

    private final PebbleSubsetEvaluator pebble;
    private final PayloadStore payloadStore;
    private final MemoryWriter memoryWriter;

    public WriteMemoryStepAdapter(PebbleSubsetEvaluator pebble,
                                  PayloadStore payloadStore,
                                  MemoryWriter memoryWriter) {
        this.pebble = pebble;
        this.payloadStore = payloadStore;
        this.memoryWriter = memoryWriter;
    }

    @Override
    public String typeName() { return "write_memory"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        if (!(step.mode() instanceof StepMode.WriteMemory cfg)) {
            return StepResult.failed("write_memory adapter received non-write_memory mode: "
                    + step.mode().typeName());
        }

        String rendered;
        try {
            var compiled = pebble.parseTemplate(cfg.content());
            rendered = pebble.evaluateAsString(compiled, context.templateContext());
        } catch (Exception e) {
            return StepResult.failed("write_memory content render failed for step '"
                    + step.name() + "': " + e.getMessage());
        }

        // Resolve template-form employeeId now that the run context exists —
        // the publish-time ACL phase deliberately skipped checking templates.
        String employeeId;
        try {
            var compiled = pebble.parseTemplate(cfg.employeeId());
            employeeId = pebble.evaluateAsString(compiled, context.templateContext());
        } catch (Exception e) {
            return StepResult.failed("write_memory employeeId template failed for step '"
                    + step.name() + "': " + e.getMessage());
        }

        MemoryWriter.Result result = memoryWriter.write(
                context.workspaceId(), employeeId, cfg.file(), cfg.mergeStrategy(), rendered);
        if (!result.success()) {
            return StepResult.failed(result.errorMessage());
        }

        String payloadUri = payloadStore.storeString(context.workspaceId(), rendered, "text/markdown");
        return StepResult.succeeded(payloadUri, "text", rendered, result.summary());
    }
}
