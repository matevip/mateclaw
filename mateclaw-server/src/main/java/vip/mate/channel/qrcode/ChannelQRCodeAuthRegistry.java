package vip.mate.channel.qrcode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routing registry for {@link ChannelQRCodeAuthProvider} implementations.
 *
 * <p>Spring auto-collects every {@code ChannelQRCodeAuthProvider} bean on
 * startup and indexes them by {@link ChannelQRCodeAuthProvider#channelType()}.
 * The generic QR controller hands off to {@code lookup(channelType)} for
 * the requested channel.
 */
@Slf4j
@Component
public class ChannelQRCodeAuthRegistry {

    private final Map<String, ChannelQRCodeAuthProvider> byType = new HashMap<>();

    public ChannelQRCodeAuthRegistry(List<ChannelQRCodeAuthProvider> providers) {
        for (ChannelQRCodeAuthProvider p : providers) {
            ChannelQRCodeAuthProvider prev = byType.put(p.channelType(), p);
            if (prev != null) {
                log.warn("[qrcode-auth] duplicate provider for type={} — {} replaced {}",
                        p.channelType(), p.getClass().getSimpleName(),
                        prev.getClass().getSimpleName());
            }
        }
        log.info("[qrcode-auth] registered {} provider(s): {}", byType.size(), byType.keySet());
    }

    public Optional<ChannelQRCodeAuthProvider> lookup(String channelType) {
        return Optional.ofNullable(byType.get(channelType));
    }
}
