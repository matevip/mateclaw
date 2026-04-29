package vip.mate.channel.qrcode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Generic channel QR-code authorization endpoints.
 *
 * <p>Replaces per-channel pairs ({@code /webhook/dingtalk/register/begin},
 * {@code /webhook/feishu/register/begin}, ...) with a single SPI-routed
 * pair. Adding a new QR-auth-capable channel is now a one-class change
 * (a {@code @Component} implementing {@link ChannelQRCodeAuthProvider})
 * with no controller edits.
 *
 * <p>The legacy per-channel endpoints in
 * {@code ChannelWebhookController} continue to work for backward compat
 * while the frontend migrates over.
 */
@Tag(name = "渠道QR扫码授权（统一）")
@Slf4j
@RestController
@RequestMapping("/api/v1/channels/qrcode")
@RequiredArgsConstructor
public class ChannelQRCodeController {

    private final ChannelQRCodeAuthRegistry registry;

    @Operation(summary = "启动指定渠道的扫码授权流程")
    @PostMapping("/{channelType}/begin")
    public ResponseEntity<Map<String, Object>> begin(
            @PathVariable String channelType,
            @RequestParam(required = false) Map<String, String> params) {
        return registry.lookup(channelType)
                .map(p -> {
                    try {
                        return ResponseEntity.ok(p.begin(params));
                    } catch (Exception e) {
                        log.error("[qrcode-auth/{}] begin failed: {}", channelType, e.getMessage(), e);
                        return ResponseEntity.internalServerError()
                                .body(Map.<String, Object>of("error",
                                        "Failed to start registration: " + e.getMessage()));
                    }
                })
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(Map.of("error", "Channel does not support QR authorization: " + channelType)));
    }

    @Operation(summary = "查询指定渠道的扫码授权状态")
    @GetMapping("/{channelType}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String channelType,
            @RequestParam("session") String sessionId) {
        return registry.lookup(channelType)
                .map(p -> ResponseEntity.ok(p.pollStatus(sessionId)))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(Map.of("error", "Channel does not support QR authorization: " + channelType)));
    }
}
