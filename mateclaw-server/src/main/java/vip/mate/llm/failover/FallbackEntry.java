package vip.mate.llm.failover;

import org.springframework.ai.chat.model.ChatModel;

/**
 * RFC-009 P3.3: a single rung of the multi-model failover chain. Pairs a
 * {@link ChatModel} with the {@code providerId} that built it so the
 * chain walker can consult {@link ProviderHealthTracker} (which keys cooldown
 * state by provider id, not by ChatModel instance).
 *
 * @param providerId db key of the provider — must match {@code mate_model_provider.provider_id}
 * @param chatModel  the actual model client to invoke
 */
public record FallbackEntry(String providerId, ChatModel chatModel) {}
