package vip.mate.workflow.runtime.mode;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.PebbleSubsetEvaluator;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.runtime.ChannelDispatcher;
import vip.mate.workflow.runtime.PayloadStore;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code dispatch_channel} — render the content template, then deliver the
 * rendered text to every configured channel via {@link ChannelDispatcher}.
 * Targets are looked up in the step's {@code targets} map keyed by channel
 * type. The step fails iff any channel fails to deliver; partial successes
 * are still flagged failed because step state is binary in v0 and silent
 * delivery loss would be worse than an explicit error.
 *
 * <p>The rendered content payload is also written through to
 * {@code mate_workflow_payload} so the run-step row's {@code output_ref}
 * points at exactly what was sent.
 */
@Component
public class DispatchChannelStepAdapter implements StepAdapter {

    private final PebbleSubsetEvaluator pebble;
    private final PayloadStore payloadStore;
    private final ChannelDispatcher dispatcher;

    public DispatchChannelStepAdapter(PebbleSubsetEvaluator pebble,
                                      PayloadStore payloadStore,
                                      ChannelDispatcher dispatcher) {
        this.pebble = pebble;
        this.payloadStore = payloadStore;
        this.dispatcher = dispatcher;
    }

    @Override
    public String typeName() { return "dispatch_channel"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        if (!(step.mode() instanceof StepMode.DispatchChannel cfg)) {
            return StepResult.failed("dispatch_channel adapter received non-dispatch mode: "
                    + step.mode().typeName());
        }

        String rendered;
        try {
            var compiled = pebble.parseTemplate(cfg.content());
            rendered = pebble.evaluateAsString(compiled, context.templateContext());
        } catch (Exception e) {
            return StepResult.failed("dispatch_channel content render failed for step '"
                    + step.name() + "': " + e.getMessage());
        }

        Map<String, String> targets = cfg.targets() == null ? Map.of() : cfg.targets();
        List<String> failures = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        for (String channel : cfg.channels()) {
            String target = targets.get(channel);
            ChannelDispatcher.DispatchResult result =
                    dispatcher.dispatch(context.workspaceId(), channel, target, rendered);
            if (result.success()) {
                delivered.add(channel);
            } else {
                failures.add(channel + ": " + result.message());
            }
        }

        String payloadUri = payloadStore.storeString(context.workspaceId(), rendered, "text/plain");

        if (!failures.isEmpty()) {
            return StepResult.failed("dispatch_channel partial / total failure: "
                    + String.join("; ", failures));
        }
        return StepResult.succeeded(payloadUri, "text", rendered,
                "delivered to " + String.join(", ", delivered));
    }
}
