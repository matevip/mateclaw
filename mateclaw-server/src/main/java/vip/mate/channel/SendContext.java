package vip.mate.channel;

import java.util.Map;

/**
 * Per-send context used to thread optional metadata from
 * {@link ChannelMessageRouter} down into channel-specific renderers
 * without having to expand {@code renderAndSend} signatures every time
 * a new channel needs a side-channel value.
 *
 * <p>Currently used by:
 * <ul>
 *   <li>{@code feedbackId} — pre-allocated by Router after the
 *       assistant message is persisted, so the WeCom AI Bot adapter
 *       can attach {@code feedback.id} to its final stream chunk for
 *       like/dislike collection. The id maps back to
 *       {@code (conversationId, savedMessageId, senderId)} in the
 *       channel's feedback registry.</li>
 *   <li>{@code savedMessageId} — the persisted assistant
 *       {@code mate_message.id}; used by feedback registry to bridge
 *       a feedback event back to the originating message.</li>
 *   <li>{@code extra} — open-ended map for future side-channel hints
 *       so we don't need yet another record field migration.</li>
 * </ul>
 *
 * <p>Adapters that don't need any of this can ignore the parameter:
 * the default {@code renderAndSend(targetId, content, ctx)} on
 * {@link ChannelAdapter} delegates to the legacy two-arg version and
 * drops {@code ctx} entirely.
 *
 * @param feedbackId    optional like/dislike correlation id; null if
 *                      the channel does not collect feedback or the
 *                      assistant message could not be persisted
 * @param savedMessageId persisted {@code mate_message.id} for the
 *                      assistant reply; null in error / streaming
 *                      passthrough paths where no row was created
 * @param extra         open-ended map; must never be null — use
 *                      {@link #empty()} if no extras
 */
public record SendContext(
        String feedbackId,
        Long savedMessageId,
        Map<String, Object> extra
) {

    public SendContext {
        // Defensive: a null extra map breaks downstream get-or-default lookups.
        if (extra == null) {
            extra = Map.of();
        }
    }

    private static final SendContext EMPTY = new SendContext(null, null, Map.of());

    /**
     * Reusable empty context. Adapters and callers that have no
     * side-channel hints to thread should use this rather than
     * allocating a fresh empty record per message.
     */
    public static SendContext empty() {
        return EMPTY;
    }
}
