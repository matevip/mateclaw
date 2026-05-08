package vip.mate.workflow.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.ErrorMode;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.compiler.ir.WorkflowInput;
import vip.mate.workflow.compiler.ir.WorkflowStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parse the workflow JSON wire format into the immutable {@link WorkflowGraph}
 * IR. The parser is structural-only: it surfaces malformed JSON and unknown
 * mode types as {@link WorkflowParseException}s but does not run schema /
 * expression / ACL validation — those passes consume the IR and emit
 * {@link CompileError}s.
 *
 * <p>Field naming matches the wire format documented in the workflow design
 * (see {@code mate_workflow_revision.graph_json}).
 */
@Component
public class WorkflowParser {

    private final ObjectMapper objectMapper;

    public WorkflowParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkflowGraph parse(String json) {
        if (json == null || json.isBlank()) {
            throw new WorkflowParseException("workflow definition is empty");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new WorkflowParseException("workflow JSON is not parseable: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new WorkflowParseException("workflow definition root must be a JSON object");
        }

        String schemaVersion = textOrNull(root.get("schemaVersion"));
        List<WorkflowInput> inputs = parseInputs(root.get("inputs"));
        List<WorkflowStep> steps = parseSteps(root.get("steps"));

        return new WorkflowGraph(schemaVersion, inputs, steps);
    }

    private List<WorkflowInput> parseInputs(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new WorkflowParseException("inputs must be a JSON array");
        }
        List<WorkflowInput> out = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            if (!entry.isObject()) {
                throw new WorkflowParseException("inputs[" + i + "] must be a JSON object");
            }
            out.add(new WorkflowInput(
                    textOrNull(entry.get("name")),
                    textOrNull(entry.get("type"))
            ));
        }
        return out;
    }

    private List<WorkflowStep> parseSteps(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new WorkflowParseException("steps must be a JSON array");
        }
        List<WorkflowStep> out = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode raw = node.get(i);
            if (!raw.isObject()) {
                throw new WorkflowParseException("steps[" + i + "] must be a JSON object");
            }
            out.add(parseStep(raw, i));
        }
        return out;
    }

    private WorkflowStep parseStep(JsonNode raw, int index) {
        Long agentId = null;
        JsonNode agentIdNode = raw.get("agentId");
        if (agentIdNode != null && !agentIdNode.isNull()) {
            if (agentIdNode.isNumber()) {
                agentId = agentIdNode.asLong();
            } else if (agentIdNode.isTextual()) {
                try {
                    agentId = Long.parseLong(agentIdNode.asText());
                } catch (NumberFormatException e) {
                    throw new WorkflowParseException("steps[" + index + "].agentId must be numeric");
                }
            } else {
                throw new WorkflowParseException("steps[" + index + "].agentId must be numeric");
            }
        }

        Integer timeoutSecs = null;
        JsonNode toNode = raw.get("timeoutSecs");
        if (toNode != null && !toNode.isNull()) {
            if (!toNode.isInt() && !toNode.isLong()) {
                throw new WorkflowParseException("steps[" + index + "].timeoutSecs must be an integer");
            }
            timeoutSecs = toNode.asInt();
        }

        StepMode mode = parseMode(raw.get("mode"), index);
        ErrorMode errorMode = parseErrorMode(raw.get("errorMode"), index);

        return new WorkflowStep(
                textOrNull(raw.get("name")),
                textOrNull(raw.get("agentName")),
                agentId,
                textOrNull(raw.get("promptTemplate")),
                mode,
                timeoutSecs,
                errorMode,
                textOrNull(raw.get("outputVar")),
                textOrNull(raw.get("outputContentType"))
        );
    }

    private StepMode parseMode(JsonNode raw, int stepIndex) {
        if (raw == null || raw.isNull()) {
            throw new WorkflowParseException("steps[" + stepIndex + "].mode is required");
        }
        if (!raw.isObject()) {
            throw new WorkflowParseException("steps[" + stepIndex + "].mode must be a JSON object");
        }
        String type = textOrNull(raw.get("type"));
        if (type == null || type.isBlank()) {
            throw new WorkflowParseException("steps[" + stepIndex + "].mode.type is required");
        }
        return switch (type) {
            case "sequential" -> new StepMode.Sequential();
            case "fan_out" -> new StepMode.FanOut();
            case "collect" -> new StepMode.Collect();
            case "conditional" -> new StepMode.Conditional(textOrNull(raw.get("expression")));
            case "await_approval" -> new StepMode.AwaitApproval(
                    textOrNull(raw.get("approvalKind")),
                    parseStringList(raw.get("approverChannels")),
                    textOrNull(raw.get("approvalMessage")),
                    raw.has("timeoutSecs") && raw.get("timeoutSecs").isInt() ? raw.get("timeoutSecs").asInt() : null
            );
            case "dispatch_channel" -> new StepMode.DispatchChannel(
                    parseStringList(raw.get("channels")),
                    parseStringMap(raw.get("targets")),
                    textOrNull(raw.get("content"))
            );
            case "write_memory" -> new StepMode.WriteMemory(
                    textOrNull(raw.get("employeeId")),
                    textOrNull(raw.get("file")),
                    textOrNull(raw.get("mergeStrategy")),
                    textOrNull(raw.get("content"))
            );
            default -> throw new WorkflowParseException(
                    "steps[" + stepIndex + "].mode.type '" + type
                            + "' is not supported in v0 (loop / invoke_skill are deferred)");
        };
    }

    private ErrorMode parseErrorMode(JsonNode raw, int stepIndex) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (!raw.isObject()) {
            throw new WorkflowParseException("steps[" + stepIndex + "].errorMode must be a JSON object");
        }
        String type = textOrNull(raw.get("type"));
        if (type == null) {
            throw new WorkflowParseException("steps[" + stepIndex + "].errorMode.type is required");
        }
        return switch (type) {
            case "fail" -> new ErrorMode.Fail();
            case "skip" -> new ErrorMode.Skip();
            case "retry" -> {
                JsonNode mr = raw.get("maxRetries");
                int max = (mr != null && mr.isInt()) ? mr.asInt() : 1;
                yield new ErrorMode.Retry(max);
            }
            default -> throw new WorkflowParseException(
                    "steps[" + stepIndex + "].errorMode.type '" + type + "' is unknown");
        };
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.asText(null);
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new WorkflowParseException("expected JSON array, got " + node.getNodeType());
        }
        List<String> out = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode v = node.get(i);
            if (v == null || v.isNull()) {
                continue;
            }
            out.add(v.asText());
        }
        return out;
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new WorkflowParseException("expected JSON object, got " + node.getNodeType());
        }
        Map<String, String> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            out.put(e.getKey(), v == null || v.isNull() ? null : v.asText());
        }
        return out;
    }
}
