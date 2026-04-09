package vip.mate.channel.webchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;

/**
 * WebChat 渠道适配器
 * <p>
 * WebChat 是无状态的 HTTP/SSE 渠道，消息由 WebChatController 直接处理。
 * 此适配器仅用于 ChannelManager 的渠道注册和状态管理，不负责消息收发。
 *
 * @author MateClaw Team
 */
@Slf4j
public class WebChatChannelAdapter extends AbstractChannelAdapter {

    public WebChatChannelAdapter(ChannelEntity channelEntity,
                                  ChannelMessageRouter messageRouter,
                                  ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
    }

    @Override
    public String getChannelType() {
        return "webchat";
    }

    @Override
    protected void doStart() {
        log.info("[webchat] WebChat channel ready: {} (API Key auth via WebChatController)", channelEntity.getName());
    }

    @Override
    protected void doStop() {
        log.info("[webchat] WebChat channel stopped: {}", channelEntity.getName());
    }

    @Override
    public void sendMessage(String targetId, String content) {
        // WebChat 通过 SSE 推送，不通过 adapter sendMessage
        log.debug("[webchat] sendMessage ignored (SSE-driven): target={}", targetId);
    }
}
