package vip.mate.channel.qrcode;

import java.util.Map;

/**
 * SPI for channels that support QR-code-based bot/app registration.
 *
 * <p>Each implementation handles one channel type's "scan QR → confirm →
 * receive credentials" flow. The framework picks the right provider by
 * {@link #channelType()} so the controller and frontend share a single
 * pair of endpoints regardless of how many channels register this way.
 *
 * <p>Adding a new channel: implement this interface as a Spring
 * {@code @Component} and the framework auto-routes to it. No changes to
 * the controller or the frontend are required for the generic endpoints.
 *
 * <p>This intentionally keeps the wire format channel-agnostic via
 * {@link Map} payloads — implementations are free to surface their own
 * key set (e.g. DingTalk uses {@code client_id/client_secret}, Feishu
 * uses {@code app_id/app_secret}).
 */
public interface ChannelQRCodeAuthProvider {

    /** The {@code mate_channel.channel_type} value this provider handles. */
    String channelType();

    /**
     * Kick off a registration session.
     *
     * @param params Optional channel-specific kickoff parameters
     *               (e.g. Feishu's {@code domain=feishu/lark}).
     * @return A response containing at minimum {@code session_id}.
     */
    Map<String, Object> begin(Map<String, String> params) throws Exception;

    /**
     * Poll a registration session for current state.
     *
     * @param sessionId Token returned from {@link #begin}.
     * @return Status payload — at minimum {@code status} field, plus
     *         credentials when {@code status=confirmed}, or QR image
     *         when the user hasn't scanned yet.
     */
    Map<String, Object> pollStatus(String sessionId);
}
