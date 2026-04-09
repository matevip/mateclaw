package vip.mate.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import vip.mate.channel.web.TalkModeWebSocketHandler;

/**
 * WebSocket 配置
 * <p>
 * 注册 Talk Mode WebSocket 端点。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TalkModeWebSocketHandler talkModeHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(talkModeHandler, "/api/v1/talk/ws")
                .setAllowedOrigins("*");
    }
}
