package vip.mate.channel.qrcode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.dingtalk.DingTalkAppRegistrationService;
import vip.mate.channel.qrcode.util.QrCodeImageEncoder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QR-code auth provider for DingTalk OAuth Device Flow registration.
 *
 * <p>Wraps {@link DingTalkAppRegistrationService} to expose its session
 * model through the unified {@link ChannelQRCodeAuthProvider} contract.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkQRCodeAuthProvider implements ChannelQRCodeAuthProvider {

    private final DingTalkAppRegistrationService service;

    @Override
    public String channelType() {
        return "dingtalk";
    }

    @Override
    public Map<String, Object> begin(Map<String, String> params) throws Exception {
        DingTalkAppRegistrationService.RegistrationSession session = service.begin();
        return Map.of("session_id", session.sessionId);
    }

    @Override
    public Map<String, Object> pollStatus(String sessionId) {
        DingTalkAppRegistrationService.RegistrationSession session = service.getSession(sessionId);
        if (session == null) {
            return Map.of("status", "expired", "error", "session not found or expired");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", session.status.name().toLowerCase());
        if (session.qrcodeUrl != null) {
            body.put("qrcode_url", session.qrcodeUrl);
            if (session.qrcodeImgDataUri == null) {
                try {
                    session.qrcodeImgDataUri = QrCodeImageEncoder.toDataUri(session.qrcodeUrl);
                } catch (Exception e) {
                    log.warn("[dingtalk-register] QR encode failed: {}", e.getMessage());
                }
            }
            if (session.qrcodeImgDataUri != null) {
                body.put("qrcode_img", session.qrcodeImgDataUri);
            }
        }
        if (session.status == DingTalkAppRegistrationService.Status.CONFIRMED) {
            body.put("client_id", session.clientId);
            body.put("client_secret", session.clientSecret);
        }
        if (session.errorMessage != null) {
            body.put("error", session.errorMessage);
        }
        return body;
    }
}
