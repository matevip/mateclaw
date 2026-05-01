package vip.mate.skill.workspace.bundle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copies every asset enumerated by a {@link SkillBundleSource} into a
 * skill workspace directory. Single Spring bean keeps the path-traversal
 * guard, error handling, and logging in one place — sources stay pure
 * data carriers.
 *
 * <p>Idempotent: existing files are overwritten via
 * {@link StandardCopyOption#REPLACE_EXISTING}. SKILL.md ownership is
 * controlled by {@link MaterializeOptions} so the same pipeline serves
 * both the builtin sync (manifest from classpath) and the wizard overlay
 * (manifest rendered from {@code template.json}).
 */
@Slf4j
@Component
public class SkillBundleMaterializer {

    /**
     * Materialize {@code source} into {@code targetDir}.
     *
     * @param source     strategy enumerating asset entries
     * @param targetDir  destination workspace directory; created if missing
     * @param options    knobs (e.g. {@link MaterializeOptions#templateOverlay()})
     * @return per-file outcome counts; never null
     */
    public Result materialize(SkillBundleSource source, Path targetDir,
                               MaterializeOptions options) throws IOException {
        Files.createDirectories(targetDir);
        Path canonicalTarget = targetDir.toAbsolutePath().normalize();

        int copied = 0;
        int skipped = 0;
        for (SkillBundleSource.BundleAsset asset : source.assets()) {
            if (options.skipSkillMd() && asset.isSkillMd()) {
                skipped++;
                continue;
            }
            // Resolve and verify the destination still falls under targetDir.
            // Without this an asset whose relativePath contains "../" could
            // escape the workspace — defense-in-depth even though every
            // current SkillBundleSource produces clean paths.
            Path dest = canonicalTarget.resolve(asset.relativePath()).normalize();
            if (!dest.startsWith(canonicalTarget)) {
                log.warn("Path traversal blocked while materializing {}: {}",
                        source.origin(), asset.relativePath());
                skipped++;
                continue;
            }
            Path parent = dest.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = asset.open().get()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            copied++;
        }

        if (copied > 0) {
            log.info("Materialized {} file(s) from {} → {} (skipped {})",
                    copied, source.origin(), targetDir, skipped);
        }
        return new Result(copied, skipped);
    }

    /** Outcome counts. {@code skipped} covers both opt-out skips (SKILL.md)
     *  and rejected unsafe paths. */
    public record Result(int copied, int skipped) {}
}
