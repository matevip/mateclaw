package vip.mate.i18n;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/**
 * I18n decorator around a {@link ToolCallback} — overrides
 * {@link #getToolDefinition()} so the LLM sees the localized description.
 *
 * <p>RFC-063r §2.3 (P0): every {@code call(String, ToolContext)} invocation
 * must be forwarded to the underlying delegate so {@link ToolContext} (carrying
 * {@code ChatOrigin}) reaches downstream {@code @Tool} methods. The previous
 * implementation only overrode {@code call(String)}, which silently dropped the
 * ToolContext via the framework default.
 *
 * <p>{@link #getToolMetadata()} is forwarded so {@code returnDirect=true}
 * tools keep their direct-return semantics under this decorator.
 */
public class LocaleAwareToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String localizedDescription;

    public LocaleAwareToolCallback(ToolCallback delegate, String localizedDescription) {
        this.delegate = delegate;
        this.localizedDescription = localizedDescription;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition original = delegate.getToolDefinition();
        return ToolDefinition.builder()
                .name(original.name())
                .description(localizedDescription)
                .inputSchema(original.inputSchema())
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
