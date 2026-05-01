package vip.mate.tool.model3d.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Tencent Cloud TC3-HMAC-SHA256 v3 request signer.
 * <p>
 * Spec: <a href="https://cloud.tencent.com/document/api/1804/120833">签名方法 v3</a>.
 * Implements only the JSON / POST flavor we need for the
 * {@code ai3d} service. Stateless — each call to {@link #sign} produces a
 * fresh {@code Authorization} header for the supplied request.
 *
 * <p>Why hand-rolled instead of {@code tencentcloud-sdk-java}: the SDK pulls in
 * ~30 MB of dependencies covering 200+ products. We need exactly two endpoints
 * on one product, and an isolated 80-line signer keeps the dependency surface
 * minimal and testable.</p>
 */
public final class TencentCloudV3Signer {

    private TencentCloudV3Signer() {}

    public record SignedHeaders(
            String authorization,
            String timestamp,
            String host
    ) {}

    /**
     * Compute the {@code Authorization} header for a Tencent Cloud v3 POST request.
     *
     * @param secretId   Tencent Cloud SecretId
     * @param secretKey  Tencent Cloud SecretKey
     * @param service    Service name in lower-case, e.g. {@code "ai3d"}
     * @param host       API host, e.g. {@code "ai3d.tencentcloudapi.com"}
     * @param payload    Request body (JSON for POST)
     * @return headers (Authorization, X-TC-Timestamp, Host) ready to attach to the HTTP request.
     */
    public static SignedHeaders sign(String secretId, String secretKey,
                                      String service, String host, String payload) {
        long timestamp = System.currentTimeMillis() / 1000L;
        return signAt(secretId, secretKey, service, host, payload, timestamp);
    }

    /** Internal entry for tests — accepts a fixed timestamp. */
    static SignedHeaders signAt(String secretId, String secretKey, String service,
                                  String host, String payload, long timestamp) {
        String date = utcDate(timestamp);

        // ----- Step 1: canonical request -----
        String hashedPayload = sha256Hex(payload == null ? "" : payload);
        String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n"
                + "x-tc-action:";  // Action header value provided by caller via X-TC-Action; spec requires
                                    // it to be lowercased here. We append it via the placeholder below.
        // Note: TC3 does NOT include x-tc-action in the canonical headers — it's
        // part of the request, not the signature. Recompute strictly:
        canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n";
        String signedHeaders = "content-type;host";

        String canonicalRequest =
                "POST\n"
              + "/\n"
              + "\n"
              + canonicalHeaders
              + "\n"
              + signedHeaders + "\n"
              + hashedPayload;

        // ----- Step 2: string to sign -----
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign =
                "TC3-HMAC-SHA256\n"
              + timestamp + "\n"
              + credentialScope + "\n"
              + sha256Hex(canonicalRequest);

        // ----- Step 3: signature -----
        byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, service);
        byte[] secretSigning = hmacSha256(secretService, "tc3_request");
        String signature = toHex(hmacSha256(secretSigning, stringToSign));

        // ----- Step 4: Authorization header -----
        String authorization = "TC3-HMAC-SHA256 "
                + "Credential=" + secretId + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", "
                + "Signature=" + signature;

        return new SignedHeaders(authorization, String.valueOf(timestamp), host);
    }

    // ===== primitives =====

    private static String utcDate(long epochSeconds) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(epochSeconds * 1000L));
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Build the canonical {@code X-TC-*} header set a Tencent Cloud v3 request
     * needs in addition to {@code Authorization}. Caller must add {@code Host}
     * and {@code Content-Type: application/json; charset=utf-8} themselves
     * (those participate in the signature and are part of the wire request).
     */
    public static Map<String, String> commonHeaders(String action, String version,
                                                     String region, String timestamp) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-TC-Action", action);
        h.put("X-TC-Version", version);
        h.put("X-TC-Timestamp", timestamp);
        if (region != null && !region.isBlank()) {
            h.put("X-TC-Region", region);
        }
        return h;
    }
}
