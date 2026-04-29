package vip.mate.llm.anthropic.oauth;

/**
 * RFC-062: a Claude Code OAuth credential bundle, parsed from either the macOS
 * Keychain or {@code ~/.claude/.credentials.json}.
 *
 * <p>The shape mirrors the {@code claudeAiOauth} object that Claude Code
 * persists. Fields are nullable when the source doesn't carry them
 * (e.g. managed keys with no expiry, or older Claude Code versions that
 * skipped the refresh token).
 *
 * @param accessToken    the Bearer token to send to Anthropic API
 * @param refreshToken   token used to obtain a fresh access token; nullable
 * @param expiresAtMs    epoch millis when the access token expires; 0 means "no expiry"
 * @param source         where this credential was read from — used for diagnostics
 *                       and for routing writes back to the same storage
 */
public record ClaudeCodeCredentials(
        String accessToken,
        String refreshToken,
        long expiresAtMs,
        Source source
) {

    /**
     * Storage location the credential was read from. Determines write-back
     * destination and influences refresh behaviour.
     */
    public enum Source {
        /** macOS Keychain entry "Claude Code-credentials" (Claude Code &gt;= 2.1.114). */
        MACOS_KEYCHAIN,
        /** {@code ~/.claude/.credentials.json} JSON file (all platforms, legacy). */
        CREDENTIALS_FILE,
        /** Result of a successful refresh — to be written back to whatever the
         *  original source was. */
        REFRESH_RESPONSE
    }

    /**
     * Returns true if the access token is non-blank and not within
     * {@code bufferMs} of expiring.
     *
     * @param bufferMs safety margin (e.g. 60_000 to refresh 1 minute before expiry).
     *                 0 means "still valid even if it expires this instant".
     */
    public boolean isValid(long bufferMs) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        if (expiresAtMs == 0L) {
            // No expiry recorded (managed keys / older Claude Code formats) —
            // assume valid as long as token is present.
            return true;
        }
        return System.currentTimeMillis() < (expiresAtMs - bufferMs);
    }

    /** True if a refresh is possible (refresh token is present). */
    public boolean canRefresh() {
        return refreshToken != null && !refreshToken.isBlank();
    }
}
