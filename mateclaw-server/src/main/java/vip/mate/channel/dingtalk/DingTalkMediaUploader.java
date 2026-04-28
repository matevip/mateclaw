package vip.mate.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 钉钉媒体文件上传 helper
 * <p>
 * 钉钉 SampleFile / SampleImageMsg 消息要求 mediaId，必须先把字节传到钉钉的 media
 * 存储拿到 mediaId（3 天有效）。
 *
 * <pre>
 *   POST https://oapi.dingtalk.com/media/upload?access_token=XXX&type=file|image|voice|video
 *   Content-Type: multipart/form-data
 *
 *   form field: media (file content)
 *
 *   ← { "errcode": 0, "media_id": "@xxx", "type": "file", "created_at": ... }
 * </pre>
 * <p>
 * 单文件上限 20 MB（钉钉服务端限制；客户端上传时 timeout 30s 已经够大文件用）。
 *
 * @author MateClaw Team
 */
@Slf4j
@RequiredArgsConstructor
public class DingTalkMediaUploader {

    private static final String UPLOAD_URL = "https://oapi.dingtalk.com/media/upload";

    /** 钉钉单文件大小上限 20 MB —— 不是我们的政策，是钉钉服务端拒绝上传更大的，提前拦下省一次失败往返 */
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public String uploadFile(byte[] bytes, String fileName, String accessToken) {
        return upload(bytes, fileName, "file", accessToken);
    }

    public String uploadImage(byte[] bytes, String fileName, String accessToken) {
        return upload(bytes, fileName, "image", accessToken);
    }

    /**
     * @param type "file" / "image" / "voice" / "video"
     * @return mediaId（带 `@` 前缀）；任何失败返回 null，调用方自己决定怎么回退
     */
    private String upload(byte[] bytes, String fileName, String type, String accessToken) {
        if (bytes == null || bytes.length == 0) {
            log.warn("[dingtalk-upload] empty bytes for {} ({})", type, fileName);
            return null;
        }
        if (bytes.length > MAX_FILE_BYTES) {
            log.warn("[dingtalk-upload] file too large: {} bytes (limit {} bytes)",
                    bytes.length, MAX_FILE_BYTES);
            return null;
        }
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("[dingtalk-upload] missing access_token");
            return null;
        }

        try {
            String boundary = "----DingTalkBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = buildMultipartBody(boundary, fileName, bytes);

            String url = UPLOAD_URL + "?access_token=" + accessToken + "&type=" + type;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[dingtalk-upload] HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }

            Map<?, ?> result = objectMapper.readValue(response.body(), Map.class);
            Integer errcode = result.get("errcode") instanceof Number n ? n.intValue() : null;
            if (errcode != null && errcode != 0) {
                log.warn("[dingtalk-upload] errcode={}, errmsg={}", errcode, result.get("errmsg"));
                return null;
            }
            String mediaId = (String) result.get("media_id");
            if (mediaId == null || mediaId.isBlank()) {
                log.warn("[dingtalk-upload] empty media_id in response: {}", response.body());
                return null;
            }
            log.info("[dingtalk-upload] uploaded {} ({} bytes) → mediaId suffix=...{}",
                    type, bytes.length,
                    mediaId.length() > 8 ? mediaId.substring(mediaId.length() - 8) : mediaId);
            return mediaId;
        } catch (Exception e) {
            log.warn("[dingtalk-upload] failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 手写最小 multipart 体，避免引入第三方 multipart 库。
     * 字段名 "media"，content-type application/octet-stream（钉钉只看字节，不嫌弃 mime）。
     */
    private byte[] buildMultipartBody(String boundary, String fileName, byte[] fileBytes) {
        String safeFileName = fileName != null && !fileName.isBlank() ? fileName : "upload.bin";
        // Use a simple ASCII-only fallback if the filename has non-ASCII chars to avoid header
        // encoding issues; the actual bytes are unaffected.
        String headerSafe = safeFileName.replaceAll("[\\r\\n\"]", "_");

        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media\"; filename=\"" + headerSafe + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";

        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        List<byte[]> chunks = new ArrayList<>(3);
        chunks.add(prefixBytes);
        chunks.add(fileBytes);
        chunks.add(suffixBytes);

        int total = prefixBytes.length + fileBytes.length + suffixBytes.length;
        byte[] body = new byte[total];
        int pos = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, body, pos, c.length);
            pos += c.length;
        }
        return body;
    }
}
