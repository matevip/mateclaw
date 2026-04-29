package vip.mate.channel.qrcode.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

/**
 * Single ZXing-based QR encoder used across QR auth providers.
 *
 * <p>Centralized so a future tweak (size, error-correction level, margin)
 * touches one place instead of every register endpoint.
 */
public final class QrCodeImageEncoder {

    private QrCodeImageEncoder() {}

    /** Encode {@code content} as a 300x300 PNG QR code, return base64-only payload. */
    public static String toBase64(String content) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, 2);
        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 300, 300, hints);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /** Encode and wrap as a browser-renderable {@code data:image/png;base64,...} URI. */
    public static String toDataUri(String content) throws Exception {
        return "data:image/png;base64," + toBase64(content);
    }
}
