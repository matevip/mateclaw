package vip.mate.workflow.compiler;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps Pebble with a v0 expression-language subset suitable for workflow
 * conditionals and string templates. The wrapper:
 * <ul>
 *   <li>Pre-screens the source for blocked tags ({@code {% include %}},
 *       {@code {% extends %}}, {@code {% import %}}, {@code {% from %}},
 *       {@code {% set %}}, {@code {% macro %}}, {@code {% block %}}). These
 *       reach beyond the expression sandbox and are never required for a
 *       workflow expression.</li>
 *   <li>Disables auto-escaping (workflow content is not HTML), turns the
 *       template cache off (each compile is one-shot), and runs in
 *       non-strict variable mode so {@code default('x')} and missing-field
 *       access remain ergonomic.</li>
 *   <li>Treats expressions and full string templates as the same engine
 *       artifact — {@link #parseExpression(String)} accepts either the bare
 *       expression ({@code outputs.x.tier == 'enterprise'}) or the wrapped
 *       form ({@code "{{ outputs.x.tier == 'enterprise' }}"}).</li>
 * </ul>
 *
 * <p>JSONPath-style filtering (the {@code | jq('.foo')} syntax in the design
 * doc) is intentionally not yet wired here — Day 2-3 ships only the engine
 * wrapper plus parse / evaluate; the {@code jq} filter will be added in
 * Lane 2 alongside its runtime tests so we can exercise it against real
 * step outputs.
 */
@Component
public class PebbleSubsetEvaluator {

    private static final Pattern BLOCKED_TAG_PATTERN = Pattern.compile(
            "\\{%\\s*(include|extends|import|from|set|macro|block)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Wrapping form recognized for bare conditional expressions. */
    private static final Pattern WRAPPED_EXPRESSION = Pattern.compile(
            "^\\s*\\{\\{(.*)\\}\\}\\s*$", Pattern.DOTALL);

    private final PebbleEngine engine;

    public PebbleSubsetEvaluator() {
        this.engine = new PebbleEngine.Builder()
                .strictVariables(false)
                .cacheActive(false)
                .autoEscaping(false)
                .build();
    }

    /**
     * Parse a conditional expression into a compiled artifact ready for
     * repeated evaluation. Accepts either {@code expr} or {@code "{{ expr }}"}.
     */
    public Compiled parseExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ExpressionException("expression is empty");
        }
        rejectBlockedTags(expression);

        String inner = stripWrapping(expression);
        String source = "{{ " + inner + " }}";
        return compile(source, expression);
    }

    /**
     * Parse a multi-segment string template (prompt template, dispatch_channel
     * content, write_memory content). The whole string is treated as a Pebble
     * template body.
     */
    public Compiled parseTemplate(String template) {
        if (template == null) {
            throw new ExpressionException("template is null");
        }
        rejectBlockedTags(template);
        return compile(template, template);
    }

    public String evaluateAsString(Compiled compiled, Map<String, Object> context) {
        StringWriter writer = new StringWriter();
        evaluate(compiled, context, writer);
        return writer.toString();
    }

    public boolean evaluateAsBoolean(Compiled compiled, Map<String, Object> context) {
        String rendered = evaluateAsString(compiled, context).trim();
        return "true".equalsIgnoreCase(rendered);
    }

    private void evaluate(Compiled compiled, Map<String, Object> context, Writer writer) {
        try {
            compiled.template.evaluate(writer, context == null ? Map.of() : context);
        } catch (Exception e) {
            throw new ExpressionException(
                    "expression evaluation failed: " + e.getMessage()
                            + " (source: " + compiled.originalSource + ")",
                    e);
        }
    }

    private Compiled compile(String pebbleSource, String originalSource) {
        try {
            // getLiteralTemplate uses the source string itself as the template
            // body, bypassing the Loader (which is the right call here — we
            // never want to read templates from the filesystem or classpath).
            PebbleTemplate template = engine.getLiteralTemplate(pebbleSource);
            return new Compiled(template, originalSource);
        } catch (Exception e) {
            throw new ExpressionException(
                    "expression parse failed: " + e.getMessage()
                            + " (source: " + originalSource + ")",
                    e);
        }
    }

    private static void rejectBlockedTags(String source) {
        Matcher m = BLOCKED_TAG_PATTERN.matcher(source);
        if (m.find()) {
            throw new ExpressionException(
                    "expression uses blocked tag '" + m.group(1)
                            + "' — workflow expressions only allow {{ ... }} substitutions");
        }
    }

    private static String stripWrapping(String expression) {
        Matcher m = WRAPPED_EXPRESSION.matcher(expression);
        return m.matches() ? m.group(1).trim() : expression.trim();
    }

    /** Compiled, reusable expression. */
    public record Compiled(PebbleTemplate template, String originalSource) {
    }
}
