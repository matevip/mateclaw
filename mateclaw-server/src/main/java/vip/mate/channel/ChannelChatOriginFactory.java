package vip.mate.channel;

import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChannelTarget;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.channel.model.ChannelEntity;

/**
 * RFC-063r §2.2: factory that translates an inbound channel message into a
 * {@link ChatOrigin}. Lives in {@code vip.mate.channel} (not in
 * {@code vip.mate.agent.context}) so that the dependency direction stays
 * {@code channel → agent} and never the reverse.
 */
@Component
public class ChannelChatOriginFactory {

    /**
     * Build a {@link ChatOrigin} for a channel-originated message.
     *
     * @param channel             channel entity (non-null) — provides id + workspaceId
     * @param message             inbound message (non-null) — provides senderId + reply target
     * @param conversationId      resolved conversation id (channel-scoped)
     * @param workspaceBasePath   workspace activity directory; null = unrestricted
     */
    public ChatOrigin from(ChannelEntity channel,
                           ChannelMessage message,
                           String conversationId,
                           String workspaceBasePath) {
        ChannelTarget target = new ChannelTarget(
                resolveTargetId(message),
                /* threadId  */ null,    // adapters fill via ChannelMessage extension fields when available
                /* accountId */ null);
        return new ChatOrigin(
                /* agentId           */ null,
                /* conversationId    */ conversationId,
                /* requesterId       */ message.getSenderId(),
                /* workspaceId       */ channel.getWorkspaceId(),
                /* workspaceBasePath */ workspaceBasePath,
                /* channelId         */ channel.getId(),
                /* channelTarget     */ target);
    }

    /**
     * Resolve the IM target id used for proactive sends — prefer chatId
     * (group/room) over senderId so that cron deliveries land in the same
     * conversation the user originally messaged from.
     */
    private String resolveTargetId(ChannelMessage message) {
        if (message.getReplyToken() != null && !message.getReplyToken().isBlank()) {
            return message.getReplyToken();
        }
        return message.getChatId() != null ? message.getChatId() : message.getSenderId();
    }
}
