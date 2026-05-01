package vip.mate.skill.knowledge;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

import java.util.function.Function;

/**
 * RFC-090 §14.4 — generic {@link ToolCallback} adapter for skill-scoped
 * wrapper tools.
 *
 * <p>The factory ({@link WikiSkillWrapperToolFactory}) constructs one
 * instance per (skill, op) pair, with the bound {@code kbId} captured
 * inside the {@link Function} body. The LLM sees only the wrapper name
 * (e.g. {@code kb_tcm_classics_search}); the {@code kbId} is never
 * passed via {@link ToolContext} or a ThreadLocal — both of which
 * §14.4 explicitly bans.
 *
 * <p>Why a single class instead of an anonymous lambda: the
 * {@link ToolDefinition} surface is verbose enough that anonymous
 * inner classes would duplicate the same getter wiring everywhere.
 */
public class SkillScopedToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> handler;

    public SkillScopedToolCallback(String name,
                                    String description,
                                    String inputSchema,
                                    Function<String, String> handler) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        this.handler = handler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolCallback.super.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return handler.apply(toolInput);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        // Skill-scoped tools intentionally don't read from ToolContext —
        // the binding is in our captured state. We just forward to the
        // single-arg handler so behaviour stays identical regardless of
        // whether the framework supplies a context.
        return handler.apply(toolInput);
    }
}
