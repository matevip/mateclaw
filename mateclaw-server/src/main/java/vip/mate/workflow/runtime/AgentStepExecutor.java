package vip.mate.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.PebbleSubsetEvaluator;
import vip.mate.workflow.compiler.ir.WorkflowStep;

import java.util.UUID;

/**
 * Shared "render prompt → invoke agent → parse output" pipeline reused by
 * the sequential / fan_out / conditional adapters. Centralising this here
 * keeps each adapter file focused on its mode-specific dispatch logic
 * (skip-on-condition, merge semantics) instead of repeating prompt rendering
 * and content-type parsing.
 */
@Component
public class AgentStepExecutor {

    private static final String TEXT = "text";
    private static final String JSON = "json";

    private final AgentInvoker agentInvoker;
    private final PebbleSubsetEvaluator pebble;
    private final PayloadStore payloadStore;
    private final ObjectMapper objectMapper;

    public AgentStepExecutor(AgentInvoker agentInvoker,
                             PebbleSubsetEvaluator pebble,
                             PayloadStore payloadStore,
                             ObjectMapper objectMapper) {
        this.agentInvoker = agentInvoker;
        this.pebble = pebble;
        this.payloadStore = payloadStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve the agent, render the prompt with the current run context,
     * invoke the agent, parse the response according to {@code outputContentType},
     * and write the payload through the store. Returns a succeeded result on
     * the happy path and a failed result when any step in the chain throws.
     */
    public StepResult run(WorkflowStep step, WorkflowRunContext context) {
        Long agentId = resolveAgentId(step, context.workspaceId());
        if (agentId == null) {
            return StepResult.failed("agent not resolvable for step '" + step.name()
                    + "': agentName=" + step.agentName() + " agentId=" + step.agentId());
        }

        String prompt;
        try {
            prompt = renderPrompt(step, context);
        } catch (Exception e) {
            return StepResult.failed("prompt render failed for step '" + step.name()
                    + "': " + e.getMessage());
        }

        String response;
        String conversationId = "wf-run-" + context.runId() + "-step-" + step.name()
                + "-" + UUID.randomUUID();
        try {
            response = agentInvoker.invoke(agentId, prompt, conversationId);
            if (response == null) response = "";
        } catch (Exception e) {
            return StepResult.failed("agent invocation failed for step '" + step.name()
                    + "': " + e.getMessage());
        }

        String contentType = step.effectiveOutputContentType();
        try {
            Object parsedValue = parseResponse(response, contentType);
            String payloadUri = (TEXT.equals(contentType))
                    ? payloadStore.storeString(context.workspaceId(), response, "text/plain")
                    : payloadStore.storeString(context.workspaceId(), response, "application/json");
            String summary = summarise(response);
            return StepResult.succeeded(payloadUri, contentType, parsedValue, summary);
        } catch (Exception e) {
            return StepResult.failed("output parse failed for step '" + step.name()
                    + "' (contentType=" + contentType + "): " + e.getMessage());
        }
    }

    private Long resolveAgentId(WorkflowStep step, long workspaceId) {
        if (step.agentId() != null) return step.agentId();
        if (step.agentName() != null && !step.agentName().isBlank()) {
            return agentInvoker.resolveAgentId(workspaceId, step.agentName());
        }
        return null;
    }

    private String renderPrompt(WorkflowStep step, WorkflowRunContext context) {
        if (step.promptTemplate() == null || step.promptTemplate().isBlank()) {
            return "";
        }
        var compiled = pebble.parseTemplate(step.promptTemplate());
        return pebble.evaluateAsString(compiled, context.templateContext());
    }

    private Object parseResponse(String response, String contentType) throws Exception {
        if (JSON.equals(contentType)) {
            // Permissive: agents often wrap JSON in ```json fences.
            String cleaned = stripCodeFence(response);
            return objectMapper.readValue(cleaned, Object.class);
        }
        return response;
    }

    private static String stripCodeFence(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String summarise(String response) {
        if (response == null || response.isBlank()) return "";
        String oneLine = response.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 256 ? oneLine : oneLine.substring(0, 253) + "...";
    }
}
