package vip.mate.trigger.ingest;

/**
 * Drops events whose sender id matches a registered bot identity for the
 * workspace. The intent: MateClaw's own outbound channel messages would
 * otherwise loop back through the channel webhook, fire a trigger, and
 * dispatch a fresh workflow run — a recipe for a runaway echo loop on any
 * channel where the bot account can read its own posts.
 *
 * <p>v0 keeps the bot identity registry in-memory; production will likely
 * wire this to {@code mate_channel.bot_identity} once that schema lands.
 * The interface lets tests inject a deterministic resolver.
 */
public interface BotSelfFilter {

    /**
     * Whether {@code senderId} matches a known bot identity in
     * {@code workspaceId}. Returning {@code true} causes the ingest pipeline
     * to drop the event silently.
     */
    boolean isBotSelf(long workspaceId, String senderId);
}
