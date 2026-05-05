package vip.mate.skill.workspace.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Strategy that enumerates the supporting files shipping alongside a
 * SKILL.md (scripts/, references/, templates/, fonts, icons, ...).
 *
 * <p>Decouples the workspace materialization pipeline from the underlying
 * source. Today the only implementation is {@link ClasspathBundleSource},
 * shared by:
 * <ul>
 *   <li>RFC-044 builtin sync — {@code skills/<name>/} on the classpath</li>
 *   <li>RFC-091 template wizard overlay — {@code skill-template-bundles/<id>/}</li>
 * </ul>
 * Future implementations can wrap a git clone, a downloaded ZIP, or an
 * in-memory bundle from the skill hub without touching the consumer side.
 *
 * <p>Implementations are stateless and side-effect-free during
 * construction; {@link #assets()} is the single I/O entry point and may
 * be called more than once.
 */
public interface SkillBundleSource {

    /** Human-readable origin for logs / error messages. */
    String origin();

    /**
     * Enumerate every file under this bundle. Directories MUST be filtered
     * out by the implementation; ordering is not guaranteed.
     */
    List<BundleAsset> assets() throws IOException;

    /**
     * One file in a bundle. {@link #relativePath} is forward-slash
     * separated and never starts with a slash. {@link #open} streams bytes
     * lazily so the materializer never holds the whole bundle in memory.
     */
    record BundleAsset(String relativePath, IOSupplier<InputStream> open) {

        /** Top-level manifest. Template overlays skip this so the rendered
         *  SKILL.md stays authoritative; builtin sync copies it. */
        public boolean isSkillMd() {
            return "SKILL.md".equals(relativePath);
        }

        /** {@link java.util.function.Supplier} variant that allows IOException. */
        @FunctionalInterface
        public interface IOSupplier<T> {
            T get() throws IOException;
        }
    }
}
