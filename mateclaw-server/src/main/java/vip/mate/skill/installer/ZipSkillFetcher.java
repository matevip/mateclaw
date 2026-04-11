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
 * ZIP 格式 Skill 包解析器
 * <p>
 * 从上传的 ZIP 文件中解析 SKILL.md + references/ + scripts/，
 * 构建统一的 {@link SkillBundle} 供安装流程使用。
 * <p>
 * 安全防护：
 * <ul>
 *   <li>Zip Slip 路径穿越检测</li>
 *   <li>单文件 ≤1MB，总解压 ≤50MB</li>
 *   <li>仅接受 SKILL.md / references/ / scripts/ 下的文件</li>
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
     * 解析 ZIP 文件为 SkillBundle
     *
     * @param zipFile 上传的 ZIP 文件
     * @param parser  frontmatter 解析器
     * @return 解析后的 SkillBundle
     * @throws IOException 解析失败
     */
    public static SkillBundle parse(MultipartFile zipFile, SkillFrontmatterParser parser) throws IOException {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("ZIP file is empty");
        }
        if (zipFile.getSize() > MAX_TOTAL_SIZE) {
            throw new IllegalArgumentException("ZIP file too large (max 50MB)");
        }

        String skillMdContent = null;
        String skillMdPrefix = "";  // 如果 SKILL.md 在子目录中，记录前缀
        Map<String, String> references = new HashMap<>();
        Map<String, String> scripts = new HashMap<>();
        long totalSize = 0;

        try (InputStream is = zipFile.getInputStream();
             ZipInputStream zis = new ZipInputStream(is, StandardCharsets.UTF_8)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String entryName = entry.getName();

                // Zip Slip 防护：normalize 后检查是否逃逸
                Path entryPath = Path.of(entryName).normalize();
                if (entryPath.isAbsolute() || entryName.contains("..")) {
                    log.warn("[ZipSkillFetcher] Skipping suspicious entry: {}", entryName);
                    zis.closeEntry();
                    continue;
                }

                // 文件大小检查
                long size = entry.getSize();
                if (size > MAX_FILE_SIZE) {
                    log.warn("[ZipSkillFetcher] Skipping oversized entry: {} ({}bytes)", entryName, size);
                    zis.closeEntry();
                    continue;
                }

                // 读取内容
                byte[] bytes = zis.readAllBytes();
                totalSize += bytes.length;
                if (totalSize > MAX_TOTAL_SIZE) {
                    throw new IOException("Total extracted size exceeds 50MB limit");
                }

                String content = new String(bytes, StandardCharsets.UTF_8);
                String fileName = entryPath.getFileName().toString();

                // 定位 SKILL.md（根目录或一级子目录）
                if (skillMdContent == null && (SKILL_MD.equals(fileName) || SKILL_MD_LOWER.equals(fileName))) {
                    skillMdContent = content;
                    // 确定子目录前缀（如 "my-skill/SKILL.md" → prefix = "my-skill/"）
                    int slashIdx = entryName.lastIndexOf('/');
                    skillMdPrefix = slashIdx > 0 ? entryName.substring(0, slashIdx + 1) : "";
                    log.info("[ZipSkillFetcher] Found SKILL.md at: {}", entryName);
                }

                zis.closeEntry();

                // 先收集所有文件，后面按前缀过滤
                String normalizedName = entryPath.toString().replace('\\', '/');

                // 收集 references/ 和 scripts/ 文件
                String relativeName = normalizedName;
                if (!skillMdPrefix.isEmpty() && normalizedName.startsWith(skillMdPrefix)) {
                    relativeName = normalizedName.substring(skillMdPrefix.length());
                }

                if (relativeName.startsWith("references/")) {
                    references.put(relativeName, content);
                } else if (relativeName.startsWith("scripts/")) {
                    scripts.put(relativeName, content);
                }
            }
        }

        if (skillMdContent == null) {
            throw new IllegalArgumentException("ZIP does not contain SKILL.md");
        }

        // 解析 frontmatter
        var parsed = parser.parse(skillMdContent);
        String name = parsed.getName();
        if (name == null || name.isBlank()) {
            // 从 ZIP 文件名推断
            String zipName = zipFile.getOriginalFilename();
            if (zipName != null) {
                name = zipName.replaceAll("\\.zip$", "").replaceAll("[^a-zA-Z0-9_-]", "-");
            } else {
                name = "imported-skill";
            }
        }

        log.info("[ZipSkillFetcher] Parsed: name={}, references={}, scripts={}, totalSize={}",
                name, references.size(), scripts.size(), totalSize);

        return new SkillBundle(
                name,
                skillMdContent,
                references,
                scripts,
                "zip",
                zipFile.getOriginalFilename(),
                parsed.getFrontmatter() != null ? String.valueOf(parsed.getFrontmatter().getOrDefault("version", "1.0.0")) : "1.0.0",
                parsed.getDescription(),
                parsed.getFrontmatter() != null ? String.valueOf(parsed.getFrontmatter().getOrDefault("author", "")) : "",
                parsed.getFrontmatter() != null ? String.valueOf(parsed.getFrontmatter().getOrDefault("icon", "📦")) : "📦"
        );
    }
}
