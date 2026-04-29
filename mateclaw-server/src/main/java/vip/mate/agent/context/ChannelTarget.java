package vip.mate.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.lang.Nullable;

/**
 * Channel-bound target identity used when an agent's response must be delivered
 * back to a specific external channel (cron, IM relay, etc.).
 *
 * <p>Kept as a sub-VO of {@link ChatOrigin} so future channel-related fields do
 * not pollute the top-level origin record.
 *
 * <p>Field evolution rule: only-add, do-not-rename, deprecate-for-90-days before
 * physical removal — see {@link ChatOrigin}'s class doc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChannelTarget(
        @Nullable String targetId,
        @Nullable String threadId,
        @Nullable String accountId
) {
}
