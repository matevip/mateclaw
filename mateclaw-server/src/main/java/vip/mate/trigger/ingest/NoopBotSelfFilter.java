package vip.mate.trigger.ingest;

import org.springframework.stereotype.Component;

/**
 * Default {@link BotSelfFilter} binding — never identifies a sender as a
 * bot. Acts as the v0 placeholder until the channel-side bot identity
 * registry is wired through; channels that already know their own bot id
 * may also call the filter directly to skip ingest before it begins.
 */
@Component
public class NoopBotSelfFilter implements BotSelfFilter {

    @Override
    public boolean isBotSelf(long workspaceId, String senderId) {
        return false;
    }
}
