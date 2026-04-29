package vip.mate.channel.qrcode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.feishu.FeishuAppRegistrationService;
import vip.mate.channel.qrcode.util.QrCodeImageEncoder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QR-code auth provider for Feishu / Lark app registration.
 *
 * <p>Domain selection ({@code feishu} vs {@code lark}) flows through the
 * generic {@code params} map.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuQRCodeAuthProvider implements ChannelQRCodeAuthProvider {

    private final FeishuAppRegistrationService service;

    @Override
    public String channelType() {
        return "feishu";
    }

    @Override
    public Map<String, Object> begin(Map<String, String> params) throws Exception {
        String domain = params != null ? params.getOrDefault("domain", "feishu") : "feishu";
        String sessionId = service.begin(domain);
        return Map.of("session_id", sessionId);
    }

    @Override
    public Map<String, Object> pollStatus(String sessionId) {
        FeishuAppRegistrationService.RegistrationSession session = service.getSession(sessionId);
        if (session == null) {
            return Map.of("status", "expired", "error", "session not found or expired");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", session.status.name().toLowerCase());
        if (session.qrcodeUrl != null) {
            body.put("qrcode_url", session.qrcodeUrl);
            body.put("qrcode_expire_seconds", session.qrcodeExpireSeconds);
            if (session.qrcodeImgDataUri == null) {
                try {
                    session.qrcodeImgDataUri = QrCodeImageEncoder.toDataUri(session.qrcodeUrl);
                } catch (Exception e) {
                    log.warn("[feishu-register] QR encode failed: {}", e.getMessage());
                }
            }
            if (session.qrcodeImgDataUri != null) {
                body.put("qrcode_img", session.qrcodeImgDataUri);
            }
        }
        if (session.status == FeishuAppRegistrationService.Status.CONFIRMED) {
            body.put("client_id", session.clientId);
            body.put("client_secret", session.clientSecret);
            if (session.userOpenId != null) body.put("user_open_id", session.userOpenId);
            if (session.userTenantBrand != null) body.put("user_tenant_brand", session.userTenantBrand);
        }
        if (session.errorMessage != null) {
            body.put("error", session.errorMessage);
        }
        return body;
    }
}
