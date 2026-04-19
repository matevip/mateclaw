package vip.mate.llm.chatmodel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

/**
 * Strategy contract for assembling a Spring AI {@link ChatModel} from a
 * persisted {@link ModelConfigEntity} + {@link ModelProviderEntity} pair.
 *
 * <p>One implementation per {@link ModelProtocol}. New provider protocols
 * are added by registering a new {@code @Component} that implements this
 * interface — no edits to {@link ProviderChatModelFactory} or
 * {@code AgentGraphBuilder} required.</p>
 *
 * <p>This interface lives in {@code vip.mate.llm.chatmodel} (under the {@code llm}
 * package) so that {@code llm.failover.ProviderInitProbe} can build a
 * {@link ChatModel} for health-probing without depending on the {@code agent}
 * package — which would create the circular dependency
 * {@code agent → llm → agent}. Prior to this extraction, all model-building
 * logic lived inside {@code AgentGraphBuilder}.</p>
 */
public interface ChatModelBuilder {

    /** The protocol this builder handles; the factory routes by this key. */
    ModelProtocol supportedProtocol();

    /**
     * Build a fresh {@link ChatModel} for the given runtime configuration.
     * Implementations must be stateless — callers may invoke this many times
     * for the same model id and expect equivalent (but not necessarily ==)
     * results.
     *
     * @param model    runtime model row with temperature / max tokens / etc.
     * @param provider provider row supplying API key, base URL, and provider-level
     *                 generate kwargs
     * @param retry    retry template to wire into the underlying Spring AI client
     *                 where supported. Implementations whose protocols don't
     *                 expose a Spring AI {@code RetryTemplate} hook (DashScope
     *                 native, ChatGPT Responses) may ignore this parameter.
     */
    ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry);
}
