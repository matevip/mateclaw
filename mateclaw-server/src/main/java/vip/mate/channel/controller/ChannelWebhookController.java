package vip.mate.channel.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vip.mate.channel.ChannelAdapter;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.dingtalk.DingTalkChannelAdapter;
import vip.mate.channel.discord.DiscordChannelAdapter;
import vip.mate.channel.feishu.FeishuChannelAdapter;
import vip.mate.channel.telegram.TelegramChannelAdapter;
import vip.mate.channel.weixin.ILinkClient;
import vip.mate.channel.weixin.WeixinChannelAdapter;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 渠道 Webhook 回调接口
 * <p>
 * 接收来自各 IM 平台的消息推送回调。
 * 各平台（钉钉、飞书、Telegram 等）将此 URL 配置为消息回调地址。
 * <p>
 * URL 格式：/api/v1/channels/webhook/{channelType}
 * 此接口不需要 JWT 认证（由各平台的签名/Token 机制保障安全）。
 *
 * @author MateClaw Team
 */
@Tag(name = "渠道Webhook")
@Slf4j
@RestController
@RequestMapping("/api/v1/channels/webhook")
@RequiredArgsConstructor
public class ChannelWebhookController {

    private final ChannelManager channelManager;

    @Operation(summary = "钉钉消息回调")
    @PostMapping("/dingtalk")
    public ResponseEntity<Map<String, Object>> dingtalkWebhook(@RequestBody Map<String, Object> payload) {
        log.debug("[webhook] DingTalk callback received");
        Optional<ChannelAdapter> adapter = channelManager.getAdapterByType("dingtalk");
        if (adapter.isPresent() && adapter.get() instanceof DingTalkChannelAdapter dingtalk) {
            dingtalk.handleWebhook(payload);
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
        log.warn("[webhook] DingTalk channel not active, ignoring callback");
        return ResponseEntity.ok(Map.of("status", "channel_not_active"));
    }

    @Operation(summary = "飞书消息回调")
    @PostMapping("/feishu")
    public ResponseEntity<Map<String, Object>> feishuWebhook(@RequestBody Map<String, Object> payload) {
        log.debug("[webhook] Feishu callback received");
        Optional<ChannelAdapter> adapter = channelManager.getAdapterByType("feishu");
        if (adapter.isPresent() && adapter.get() instanceof FeishuChannelAdapter feishu) {
            Map<String, Object> result = feishu.handleWebhook(payload);
            return ResponseEntity.ok(result);
        }
        // 即使渠道未激活，也需要响应 URL 验证
        String type = (String) payload.get("type");
        if ("url_verification".equals(type)) {
            String challenge = (String) payload.get("challenge");
            return ResponseEntity.ok(Map.of("challenge", challenge != null ? challenge : ""));
        }
        log.warn("[webhook] Feishu channel not active, ignoring callback");
        return ResponseEntity.ok(Map.of("code", 0));
    }

    @Operation(summary = "Telegram 消息回调")
    @PostMapping("/telegram")
    public ResponseEntity<String> telegramWebhook(@RequestBody Map<String, Object> payload) {
        log.debug("[webhook] Telegram callback received");
        Optional<ChannelAdapter> adapter = channelManager.getAdapterByType("telegram");
        if (adapter.isPresent() && adapter.get() instanceof TelegramChannelAdapter telegram) {
            telegram.handleWebhook(payload);
        } else {
            log.warn("[webhook] Telegram channel not active, ignoring callback");
        }
        return ResponseEntity.ok("ok");
    }

    @Operation(summary = "Discord 消息回调（已废弃：Discord 已切换为 Gateway WebSocket 模式）")
    @PostMapping("/discord")
    public ResponseEntity<Map<String, Object>> discordWebhook(@RequestBody Map<String, Object> payload) {
        // Discord Interaction PING 仍需响应（防止 Discord 删除 Interaction URL）
        Integer type = (Integer) payload.get("type");
        if (type != null && type == 1) {
            return ResponseEntity.ok(Map.of("type", 1));
        }

        // Discord 已切换为 Gateway WebSocket，webhook 回调不再用于接收消息
        log.warn("[webhook] Discord webhook called, but messages are now received via Gateway WebSocket");
        Optional<ChannelAdapter> adapter = channelManager.getAdapterByType("discord");
        if (adapter.isPresent() && adapter.get() instanceof DiscordChannelAdapter discord) {
            discord.handleWebhook(payload);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @Operation(summary = "企业微信消息回调（智能机器人模式不使用，保留兼容）")
    @PostMapping("/wecom")
    public ResponseEntity<String> wecomWebhook(@RequestBody Map<String, Object> payload) {
        // 智能机器人模式通过 WebSocket 长连接接收消息，不再使用 HTTP 回调
        log.debug("[webhook] WeCom callback received (not used in bot mode, messages are received via WebSocket)");
        return ResponseEntity.ok("success");
    }

    @Operation(summary = "Slack Events API 回调")
    @PostMapping("/slack")
    public ResponseEntity<Map<String, Object>> slackWebhook(@RequestBody Map<String, Object> payload) {
        log.debug("[webhook] Slack callback received");
        // URL Verification challenge
        if ("url_verification".equals(payload.get("type"))) {
            return ResponseEntity.ok(Map.of("challenge", payload.getOrDefault("challenge", "")));
        }
        Optional<ChannelAdapter> adapter = channelManager.getAdapterByType("slack");
        if (adapter.isPresent() && adapter.get() instanceof vip.mate.channel.slack.SlackChannelAdapter slack) {
            Map<String, Object> result = slack.handleWebhook(payload);
            return ResponseEntity.ok(result);
        }
        log.warn("[webhook] Slack channel not active, ignoring callback");
        return ResponseEntity.ok(Map.of("status", "channel_not_active"));
    }

    // ==================== 微信 iLink Bot ====================

    /** 微信扫码深链接模板 */
    private static final String WEIXIN_SCAN_URL_TEMPLATE =
            "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=%s&bot_type=3";

    /**
     * 创建临时 ILinkClient 用于 QR 码操作（不依赖渠道是否已启动）
     */
    private ILinkClient createWeixinClient() {
        return new ILinkClient("", ILinkClient.DEFAULT_BASE_URL,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * 使用 ZXing 生成 QR 码 PNG 图片并返回 Base64 编码
     */
    private String generateQrCodeBase64(String content) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, 2
        );
        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 300, 300, hints);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos);
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @Operation(summary = "获取微信登录二维码")
    @GetMapping("/weixin/qrcode")
    public ResponseEntity<Map<String, Object>> weixinQrcode() {
        try {
            ILinkClient client = createWeixinClient();
            Map<String, Object> apiResult = client.getBotQrcode();

            String qrcode = String.valueOf(apiResult.getOrDefault("qrcode", ""));
            if (qrcode.isBlank()) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "iLink API returned empty qrcode"));
            }

            // 从 qrcode 构建微信扫码深链接，再生成 QR 码图片
            String scanUrl;
            Object urlObj = apiResult.get("url");
            if (urlObj != null && urlObj.toString().startsWith("http")) {
                scanUrl = urlObj.toString();
            } else {
                String encoded = URLEncoder.encode(qrcode, StandardCharsets.UTF_8);
                scanUrl = String.format(WEIXIN_SCAN_URL_TEMPLATE, encoded);
            }

            String qrCodeImgBase64 = generateQrCodeBase64(scanUrl);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("qrcode", qrcode);
            result.put("qrcode_img", qrCodeImgBase64);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[webhook] WeChat QR code fetch failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get QR code: " + e.getMessage()));
        }
    }

    @Operation(summary = "查询微信二维码扫码状态")
    @GetMapping("/weixin/qrcode/status")
    public ResponseEntity<Map<String, Object>> weixinQrcodeStatus(@RequestParam String qrcode) {
        try {
            ILinkClient client = createWeixinClient();
            Map<String, Object> apiResult = client.getQrcodeStatus(qrcode);

            // 只返回前端需要的字段
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", apiResult.getOrDefault("status", "waiting"));
            result.put("bot_token", apiResult.getOrDefault("bot_token", ""));
            result.put("base_url", apiResult.getOrDefault("baseurl", ""));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[webhook] WeChat QR code status check failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to check QR code status: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取渠道运行状态")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(channelManager.getStatus());
    }
}
