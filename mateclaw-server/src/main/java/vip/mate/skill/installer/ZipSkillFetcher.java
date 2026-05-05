package vip.mate.skill.installer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import vip.mate.skill.installer.model.SkillBundle;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses a ZIP-packaged Skill into a {@link SkillBundle}.
 * <p>
 * Used by both the upload endpoint (MultipartFile) and the ClawHub install
 * path (downloaded ZIP bytes). Hardened against:
 * <ul>
 *   <li>Zip Slip path traversal</li>
 *   <li>Per-file ≤1MB, total ≤50MB</li>
 *   <li>Only SKILL.md / references/ / scripts/ entries are kept</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
public class ZipSkillFetcher {

    private static final long MAX_FILE_SIZE = 1_000_000;      // 1MB per file
    private static final long MAX_TOTAL_SIZE = 50_000_000;     // 50MB total
    private static final String SKILL_MD = "SKILL.md";
    private static final String SKILL_MD_LOWER = "skill.md";

    /**
     * Holds the in-memory result of decompressing a ZIP. Used by callers
     * that want to enrich the SkillBundle with metadata (e.g. ClawHub author
     * / icon) that isn't carried inside SKILL.md.
     */
    public record ExtractedSkill(String skillMdContent,
                                 Map<String, String> references,
                                 Map<String, String> scripts) {}

    /**
     * Parse an uploaded ZIP file into a SkillBundle. Source type is "zip"
     * and source URL is the original filename.
     */
    public static SkillBundle parse(MultipartFile zipFile, SkillFrontmatterParser parser) throws IOException {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("ZIP file is empty");
        }
        if (zipFile.getSize() > MAX_TOTAL_SIZE) {
            throw new IllegalArgumentException("ZIP file too large (max 50MB)");
        }

        ExtractedSkill extracted;
        try (InputStream is = zipFile.getInputStream()) {
            extracted = extract(is);
        }

        var parsed = parser.parse(extracted.skillMdContent());
        String name = parsed.getName();
        if (name == null || name.isBlank()) {
            String zipName = zipFile.getOriginalFilename();
            if (zipName != null) {
                name = zipName.replaceAll("\\.zip$", "").replaceAll("[^a-zA-Z0-9_-]", "-");
            } else {
                name = "imported-skill";
            }
        }

        log.info("[ZipSkillFetcher] Parsed: name={}, references={}, scripts={}",
                name, extracted.references().size(), extracted.scripts().size());

        Map<String, Object> fm = parsed.getFrontmatter();
        return new SkillBundle(
                name,
                extracted.skillMdContent(),
                extracted.references(),
                extracted.scripts(),
                "zip",
                zipFile.getOriginalFilename(),
                fm != null ? String.valueOf(fm.getOrDefault("version", "1.0.0")) : "1.0.0",
                parsed.getDescription(),
                fm != null ? String.valueOf(fm.getOrDefault("author", "")) : "",
                fm != null ? String.valueOf(fm.getOrDefault("icon", "📦")) : "📦"
        );
    }

    /**
     * Decompress a ZIP stream into in-memory SKILL.md + references + scripts.
     * Throws {@link IllegalArgumentException} if no SKILL.md is present.
     */
    public static ExtractedSkill extract(InputStream zipStream) throws IOException {
        String skillMdContent = null;
        String skillMdPrefix = "";
        Map<String, String> references = new HashMap<>();
        Map<String, String> scripts = new HashMap<>();
        long totalSize = 0;

        try (ZipInputStream zis = new ZipInputStream(zipStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String entryName = entry.getName();

                // Zip Slip guard: normalize and reject absolute / traversal entries.
                Path entryPath = Path.of(entryName).normalize();
                if (entryPath.isAbsolute() || entryName.contains("..")) {
                    log.warn("[ZipSkillFetcher] Skipping suspicious entry: {}", entryName);
                    zis.closeEntry();
                    continue;
                }

                long declaredSize = entry.getSize();
                if (declaredSize > MAX_FILE_SIZE) {
                    log.warn("[ZipSkillFetcher] Skipping oversized entry: {} ({}bytes)", entryName, declaredSize);
                    zis.closeEntry();
                    continue;
                }

                byte[] bytes = zis.readAllBytes();
                if (bytes.length > MAX_FILE_SIZE) {
                    log.warn("[ZipSkillFetcher] Skipping oversized entry post-read: {} ({}bytes)", entryName, bytes.length);
                    zis.closeEntry();
                    continue;
                }
                totalSize += bytes.length;
                if (totalSize > MAX_TOTAL_SIZE) {
                    throw new IOException("Total extracted size exceeds 50MB limit");
                }

                String content = new String(bytes, StandardCharsets.UTF_8);
                String fileName = entryPath.getFileName().toString();

                if (skillMdContent == null && (SKILL_MD.equals(fileName) || SKILL_MD_LOWER.equals(fileName))) {
                    skillMdContent = content;
                    int slashIdx = entryName.lastIndexOf('/');
                    skillMdPrefix = slashIdx > 0 ? entryName.substring(0, slashIdx + 1) : "";
                    log.info("[ZipSkillFetcher] Found SKILL.md at: {}", entryName);
                }

                zis.closeEntry();

                String normalizedName = entryPath.toString().replace('\\', '/');
                String relativeName = normalizedName;
                if (!skillMdPrefix.isEmpty() && normalizedName.startsWith(skillMdPrefix)) {
                    relativeName = normalizedName.substring(skillMdPrefix.length());
                }

                if (relativeName.startsWith("references/")) {
                    references.put(relativeName.substring("references/".length()), content);
                } else if (relativeName.startsWith("scripts/")) {
                    scripts.put(relativeName.substring("scripts/".length()), content);
                }
            }
        }

        if (skillMdContent == null) {
            throw new IllegalArgumentException("ZIP does not contain SKILL.md");
        }

        return new ExtractedSkill(skillMdContent, references, scripts);
    }
}
