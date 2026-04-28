package vip.mate.channel;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * RFC-063r §2.10: Parameter Object that bundles optional delivery hints
 * (Slack {@code thread_ts}, Telegram {@code message_thread_id}, multi-bot
 * {@code accountId}, etc.) so {@link ChannelManager#sendToChannel} doesn't
 * grow a 5-arg overload.
 *
 * <p>{@link #DEFAULTS} is the canonical "no hints" instance — adapters that
 * don't override the 4-arg {@code proactiveSend} keep their pre-RFC behavior.
 */
public record DeliveryOptions(
        @Nullable String threadId,
        @Nullable String accountId,
        Map<String, Object> ext
) {

    public static final DeliveryOptions DEFAULTS = new DeliveryOptions(null, null, Map.of());

    public DeliveryOptions {
        // Defensive: never expose a null map — the receiver should be able to
        // call .get(...) without a null check.
        if (ext == null) ext = Map.of();
    }
}
