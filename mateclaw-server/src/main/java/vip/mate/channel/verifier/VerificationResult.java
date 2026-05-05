package vip.mate.channel.verifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of a {@link ChannelVerifier#verify} probe. Designed so the
 * onboarding wizard's Step 2 can render success and failure with no extra
 * round-trips:
 * <ul>
 *   <li>{@code headline} — one-line status shown in the verify card</li>
 *   <li>{@code identity} — account display fields piped into Step 3 ("Connected as ...")</li>
 *   <li>{@code invalidField} — when failed, the form key Step 1 should highlight on "Fix it"</li>
 *   <li>{@code hint} — actionable next step, surfaced under the failure headline</li>
 * </ul>
 * The {@code skipped} variant lets channel types with no verifier (web,
 * webchat, webhook) fast-forward through Step 2 without showing an error.
 *
 * @author MateClaw Team
 */
public record VerificationResult(
        boolean ok,
        boolean skipped,
        long durationMs,
        String headline,
        Map<String, Object> identity,
        String invalidField,
        String hint
) {

    public static VerificationResult ok(long durationMs, String headline, Map<String, Object> identity) {
        return new VerificationResult(true, false, durationMs, headline,
                identity != null ? identity : Collections.emptyMap(), null, null);
    }

    public static VerificationResult failed(long durationMs, String headline, String invalidField, String hint) {
        return new VerificationResult(false, false, durationMs, headline,
                Collections.emptyMap(), invalidField, hint);
    }

    public static VerificationResult skipped(String headline) {
        return new VerificationResult(true, true, 0L, headline,
                Collections.emptyMap(), null, null);
    }

    /** Convenience: rich failure with structured identity (for partial-success scenarios). */
    public static VerificationResult failedWithIdentity(long durationMs, String headline,
                                                        Map<String, Object> identity,
                                                        String invalidField, String hint) {
        Map<String, Object> id = identity != null ? identity : new LinkedHashMap<>();
        return new VerificationResult(false, false, durationMs, headline, id, invalidField, hint);
    }
}
