package vip.mate.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * RFC-03 Lane K3 — centralized validation for media files passing through
 * channel adapters (incoming attachments + outgoing replies).
 *
 * <p>Each {@code *ChannelAdapter} (DingTalk / Feishu / Discord / Telegram /
 * QQ / WeCom / Weixin / Slack) historically inlined its own ad-hoc set of
 * checks — extension allowlist here, size cap there, no path-traversal
 * guard at all. The result is 8 places to keep in sync when a new file
 * type is supported (or when a new exploit needs a defensive patch).
 *
 * <p>This guard centralizes four checks that every adapter needs:
 * <ol>
 *   <li><b>Path containment</b> — the file resolves under the configured
 *       workspace root (after symlink resolution). Blocks {@code ../}
 *       traversal even when the attacker controls just a filename string.</li>
 *   <li><b>Existence + regular file</b> — not a directory, not a symlink
 *       to a special device.</li>
 *   <li><b>Extension allowlist</b> — case-insensitive, dot-prefix tolerant.</li>
 *   <li><b>Size cap</b> — checked once via {@link Files#size(Path)} so a
 *       gigabyte-plus file gets rejected before any adapter sends it.</li>
 * </ol>
 *
 * <p>Adapter migration is staged: this utility lands and ships with tests,
 * then individual adapters convert their inline checks one PR at a time
 * (Phase 1 starts with DingTalk per the RFC). Keeping this class
 * single-purpose so it doesn't depend on any adapter-specific service
 * means the migration can proceed without ripple-edits.
 */
public final class MediaPathGuard {

    private MediaPathGuard() {}

    /**
     * Lookup-friendly representation of a violation. The {@link #reason}
     * codes are stable so audit logs / metrics can group failures without
     * parsing the message text.
     */
    public enum Reason {
        PATH_OUTSIDE_WORKSPACE,
        FILE_MISSING,
        NOT_A_REGULAR_FILE,
        EXTENSION_NOT_ALLOWED,
        FILE_TOO_LARGE,
        IO_ERROR
    }

    /** Validation policy — immutable, share across adapter calls. */
    public record Policy(
            Path workspaceRoot,
            Set<String> allowedExtensions,
            long maxBytes
    ) {
        public Policy {
            Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
            Objects.requireNonNull(allowedExtensions, "allowedExtensions must not be null");
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
            }
            // Normalize allowed extensions: lowercase, strip leading dots.
            allowedExtensions = Set.copyOf(allowedExtensions.stream()
                    .filter(Objects::nonNull)
                    .map(e -> e.toLowerCase(Locale.ROOT))
                    .map(e -> e.startsWith(".") ? e.substring(1) : e)
                    .toList());
        }
    }

    /** Thrown when validation fails — caller decides whether to log + drop
     *  silently or surface to the user. */
    public static final class MediaValidationException extends RuntimeException {
        private final Reason reason;

        public MediaValidationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public MediaValidationException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /**
     * Validate {@code file} against {@code policy}. Returns the canonical
     * (real, symlink-resolved) path on success — callers that need to act
     * on the file (read bytes, upload to platform) should use the returned
     * value to avoid TOCTOU between validation and use.
     *
     * @throws MediaValidationException with a {@link Reason} that maps 1:1
     *         to the four documented checks; the message is human-readable
     *         and includes the offending path / extension / size.
     */
    public static Path validate(Path file, Policy policy) {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        Path canonicalRoot;
        Path canonicalFile;
        try {
            canonicalRoot = policy.workspaceRoot().toRealPath();
        } catch (IOException e) {
            throw new MediaValidationException(Reason.IO_ERROR,
                    "workspace root cannot be resolved: " + policy.workspaceRoot(), e);
        }
        try {
            canonicalFile = file.toRealPath();
        } catch (IOException e) {
            // toRealPath fails when file does not exist OR when traversal
            // hits a non-readable segment. Distinguish the common case
            // (missing file) for clearer audit output.
            if (!Files.exists(file)) {
                throw new MediaValidationException(Reason.FILE_MISSING,
                        "file does not exist: " + file, e);
            }
            throw new MediaValidationException(Reason.IO_ERROR,
                    "file path cannot be resolved: " + file, e);
        }

        // Containment: canonicalFile must be inside canonicalRoot. startsWith on
        // Path does element-wise comparison so "/ws-foo" doesn't mistakenly look
        // like a prefix of "/ws-foobar".
        if (!canonicalFile.startsWith(canonicalRoot)) {
            throw new MediaValidationException(Reason.PATH_OUTSIDE_WORKSPACE,
                    "file is outside the workspace: " + canonicalFile + " not under " + canonicalRoot);
        }

        if (!Files.isRegularFile(canonicalFile)) {
            throw new MediaValidationException(Reason.NOT_A_REGULAR_FILE,
                    "not a regular file (directory / device / fifo): " + canonicalFile);
        }

        String ext = extensionOf(canonicalFile);
        if (!policy.allowedExtensions().contains(ext)) {
            throw new MediaValidationException(Reason.EXTENSION_NOT_ALLOWED,
                    "extension '" + ext + "' not in allowlist; got " + canonicalFile);
        }

        long size;
        try {
            size = Files.size(canonicalFile);
        } catch (IOException e) {
            throw new MediaValidationException(Reason.IO_ERROR,
                    "cannot read file size: " + canonicalFile, e);
        }
        if (size > policy.maxBytes()) {
            throw new MediaValidationException(Reason.FILE_TOO_LARGE,
                    "file size " + size + " exceeds policy cap " + policy.maxBytes() + ": " + canonicalFile);
        }

        return canonicalFile;
    }

    /** Lowercase extension without leading dot; empty string when there is none. */
    static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
