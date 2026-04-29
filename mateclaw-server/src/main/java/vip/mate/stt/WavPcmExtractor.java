package vip.mate.stt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Strip the RIFF/WAVE header off a WAV blob to expose raw PCM samples.
 *
 * <p>DashScope's realtime ASR expects the {@code parameters.format = "pcm"}
 * input as **bare 16-bit signed little-endian PCM**, not WAV. The frontend
 * (see {@code mateclaw-ui/src/utils/wavEncoder.ts}) emits a 16 kHz mono
 * 16-bit WAV with the canonical 44-byte header — this helper unwraps it.
 *
 * <p>Why not just send the WAV: DashScope rejects with "format mismatch"
 * because the first 44 bytes look like garbage when interpreted as PCM
 * samples — they're the RIFF magic + format chunk metadata.
 *
 * <p>Limitations: handles only the canonical 44-byte WAV layout produced by
 * MateClaw's WavRecorder. WAVs with extra chunks (LIST, JUNK, …) before the
 * data chunk would need a chunk-walking parser. We don't currently accept
 * arbitrary uploads, so the tighter scope is fine; if this changes,
 * extend {@link #extract} to scan for the {@code "data"} chunk header
 * instead of assuming offset 36.
 */
public final class WavPcmExtractor {

    /** Bytes before the "data" chunk in a canonical mono 16-bit PCM WAV. */
    public static final int CANONICAL_HEADER_BYTES = 44;

    /** Sample rate field offset in the canonical WAV header. */
    private static final int OFFSET_SAMPLE_RATE = 24;

    private WavPcmExtractor() {}

    /**
     * Extract raw PCM bytes from a WAV blob. Throws when the input is too short
     * or the magic header bytes don't look like RIFF/WAVE — better to fail loud
     * here than ship garbage to DashScope and chase a confusing error code.
     */
    public static byte[] extract(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < CANONICAL_HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "WAV input too short: " + (wavBytes == null ? 0 : wavBytes.length) + " bytes");
        }
        if (wavBytes[0] != 'R' || wavBytes[1] != 'I' || wavBytes[2] != 'F' || wavBytes[3] != 'F'
                || wavBytes[8] != 'W' || wavBytes[9] != 'A' || wavBytes[10] != 'V' || wavBytes[11] != 'E') {
            throw new IllegalArgumentException("Not a WAV (missing RIFF/WAVE magic)");
        }
        byte[] pcm = new byte[wavBytes.length - CANONICAL_HEADER_BYTES];
        System.arraycopy(wavBytes, CANONICAL_HEADER_BYTES, pcm, 0, pcm.length);
        return pcm;
    }

    /**
     * Read the sample rate from a WAV header. Used by callers that need to
     * tell DashScope the actual rate of the audio (the API requires the rate
     * up front in the {@code run-task} message — getting it wrong produces
     * recognisable but distorted transcripts).
     */
    public static int sampleRate(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < CANONICAL_HEADER_BYTES) {
            throw new IllegalArgumentException("WAV input too short for sample-rate read");
        }
        return ByteBuffer.wrap(wavBytes, OFFSET_SAMPLE_RATE, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }

}
