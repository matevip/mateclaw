package vip.mate.channel.event;

/**
 * Spring application event fired when a channel adapter accepts an
 * inbound message and hands it off to {@code ChannelMessageRouter}.
 * The trigger module subscribes via {@code @EventListener} and forwards
 * the payload through {@code TriggerEventIngestService} so triggers of
 * pattern type {@code channel_message} or {@code content_match} can fan
 * out to workflows. Going through the event bus instead of injecting
 * the trigger service directly into the channel module keeps the two
 * worlds decoupled and dodges the construction cycle.
 *
 * <p>{@code messageId} doubles as the dedup key — repeated webhook
 * deliveries of the same message can't double-fire downstream triggers
 * because the {@code mate_trigger_event} unique constraint catches the
 * second insert.
 */
public record ChannelMessageReceivedEvent(
        long workspaceId,
        String channelType,
        String messageId,
        String senderId,
        String senderName,
        String chatId,
        String content
) {}
