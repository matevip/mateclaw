package vip.mate.skill.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skill 工作区管理器
 * <p>
 * 遵循 Maven Local Repository 模式：{root}/{skillName}/ 约定子目录。
 * 负责工作区的路径解析、初始化、归档、导出和状态查询。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillWorkspaceManager {

    private final SkillWorkspaceProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private static final DateTimeFormatter ARCHIVE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // ==================== 路径解析 ====================

    /**
     * 获取工作区根目录（确保存在）
     */
    public Path getWorkspaceRoot() {
        Path root = Paths.get(properties.getRoot());
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Failed to create workspace root {}: {}", root, e.getMessage());
        }
        return root;
    }

    /**
     * 按约定解析 skill 工作区路径：{root}/{skillName}/
     */
    public Path resolveConventionPath(String skillName) {
        return getWorkspaceRoot().resolve(sanitizeName(skillName));
    }

    /**
     * 智能解析 skill 工作区路径（三级优先级）：
     * <ol>
     *   <li>configuredDir（显式配置的 skillDir）</li>
     *   <li>{root}/{skillName}/（约定路径，目录存在时）</li>
     *   <li>null（无目录，回退数据库）</li>
     * </ol>
     */
    public Path resolveEffectivePath(String skillName, String configuredDir) {
        // 1. 显式配置
        if (configuredDir != null && !configuredDir.isBlank()) {
            Path explicit = Paths.get(configuredDir);
            if (Files.exists(explicit) && Files.isDirectory(explicit)) {
                return explicit;
            }
        }
        // 2. 约定路径
        Path convention = resolveConventionPath(skillName);
        if (Files.exists(convention) && Files.isDirectory(convention)) {
            return convention;
        }
        // 3. 无目录
        return null;
    }

    /**
     * 检查约定路径的 workspace 是否存在
     */
    public boolean conventionWorkspaceExists(String skillName) {
        Path convention = resolveConventionPath(skillName);
        return Files.exists(convention) && Files.isDirectory(convention);
    }

    // ==================== 生命周期操作 ====================

    /**
     * 初始化 skill 工作区目录（兼容旧调用：overwrite=false）
     *
     * @param skillName      skill 名称
     * @param initialContent SKILL.md 初始内容（可为 null）
     * @return 创建的工作区路径
     */
    public Path initWorkspace(String skillName, String initialContent) {
        return initWorkspace(skillName, initialContent, false);
    }

    /**
     * 初始化 skill 工作区目录
     *
     * @param skillName      skill 名称
     * @param initialContent SKILL.md 初始内容（可为 null）
     * @param overwrite      true 时无条件覆写 SKILL.md（用于重装 / 导出场景），
     *                       false 时仅在 SKILL.md 不存在时写入（用于首次创建）
     * @return 创建的工作区路径
     */
    public Path initWorkspace(String skillName, String initialContent, boolean overwrite) {
        Path workspaceDir = resolveConventionPath(skillName);
        try {
            Files.createDirectories(workspaceDir);
            Files.createDirectories(workspaceDir.resolve("references"));
            Files.createDirectories(workspaceDir.resolve("scripts"));

            Path skillMd = workspaceDir.resolve("SKILL.md");
            if (overwrite || !Files.exists(skillMd)) {
                String content = (initialContent != null && !initialContent.isBlank())
                        ? initialContent
                        : buildDefaultSkillMd(skillName);
                Files.writeString(skillMd, content);
            }

            log.info("Initialized skill workspace: {} (overwrite={})", workspaceDir, overwrite);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.CREATED, workspaceDir));
            return workspaceDir;
        } catch (IOException e) {
            log.warn("Failed to initialize workspace for skill '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    /**
     * RFC-090 §14.5 — physically remove the workspace directory. Used
     * by hard-delete only; uninstall still calls
     * {@link #archiveWorkspace} so users can recover by re-installing.
     */
    public void purgeWorkspace(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        if (!Files.exists(workspaceDir)) return;
        try {
            // Walk and delete bottom-up so non-empty dirs go away too.
            try (var stream = Files.walk(workspaceDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        /* leave partial cleanup; best effort */
                    }
                });
            }
            log.info("Purged skill workspace: {}", workspaceDir);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName,
                    SkillWorkspaceEvent.Type.ARCHIVED, workspaceDir));
        } catch (IOException e) {
            log.warn("Failed to purge workspace for skill '{}': {}", skillName, e.getMessage());
        }
    }

    /**
     * 归档 workspace 到 {root}/.archived/{name}-{timestamp}/
     */
    public void archiveWorkspace(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        if (!Files.exists(workspaceDir)) {
            return;
        }

        try {
            Path archiveRoot = getWorkspaceRoot().resolve(".archived");
            Files.createDirectories(archiveRoot);

            String archiveName = sanitizeName(skillName) + "-" + LocalDateTime.now().format(ARCHIVE_TS);
            Path archiveDir = archiveRoot.resolve(archiveName);

            Files.move(workspaceDir, archiveDir, StandardCopyOption.ATOMIC_MOVE);
            log.info("Archived skill workspace: {} → {}", workspaceDir, archiveDir);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.ARCHIVED, archiveDir));
        } catch (IOException e) {
            log.warn("Failed to archive workspace for skill '{}': {}", skillName, e.getMessage());
        }
    }

    /**
     * 将数据库 skill 内容导出到工作区目录
     */
    public Path exportToWorkspace(String skillName, String skillContent) {
        // 始终覆写 SKILL.md（initWorkspace 内部已写入），无需再做一次冗余 IO
        Path workspaceDir = initWorkspace(skillName, skillContent, true);
        if (workspaceDir != null) {
            log.info("Exported skill '{}' to workspace: {}", skillName, workspaceDir);
            eventPublisher.publishEvent(new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.EXPORTED, workspaceDir));
        }
        return workspaceDir;
    }

    /**
     * 将文件写入 skill 工作区（用于安装时写入 references/scripts）
     * <p>
     * 安全边界：
     * <ul>
     *   <li>relativePath 必须以 references/ 或 scripts/ 开头</li>
     *   <li>禁止 .. 路径遍历</li>
     *   <li>normalize 后必须仍在 workspace 目录内</li>
     * </ul>
     *
     * @param skillName    skill 名称
     * @param relativePath 相对路径（如 references/config.md）
     * @param content      文件内容
     * @throws IllegalArgumentException 如果路径不安全
     */
    public void writeWorkspaceFile(String skillName, String relativePath, String content) {
        Path workspaceDir = resolveConventionPath(skillName);

        // 路径安全校验
        Path safePath = validateWritePath(workspaceDir, relativePath);
        if (safePath == null) {
            log.error("Rejected unsafe write path for skill '{}': {}", skillName, relativePath);
            throw new IllegalArgumentException("Unsafe file path rejected: " + relativePath);
        }

        try {
            Files.createDirectories(safePath.getParent());
            Files.writeString(safePath, content);
        } catch (IOException e) {
            log.warn("Failed to write workspace file {}/{}: {}", skillName, relativePath, e.getMessage());
        }
    }

    /**
     * 清空 skill 工作区中的 references/ 和 scripts/ 目录内容（保留目录本身）
     * 用于 overwrite 安装前清除旧版本残留文件
     */
    public void cleanWorkspaceDataDirs(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        cleanDirectoryContents(workspaceDir.resolve("references"));
        cleanDirectoryContents(workspaceDir.resolve("scripts"));
    }

    /**
     * 验证写入路径安全性，防止路径逃逸
     *
     * @return 安全的绝对路径，不安全返回 null
     */
    private Path validateWritePath(Path workspaceDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }

        // 归一化分隔符
        String normalized = relativePath.replace("\\", "/");

        // 必须以 references/ 或 scripts/ 开头
        if (!normalized.startsWith("references/") && !normalized.startsWith("scripts/")) {
            return null;
        }

        // 禁止路径遍历元素
        if (normalized.contains("..") || normalized.startsWith("/")) {
            return null;
        }

        // resolve + normalize，然后检查是否仍在 workspace 内
        Path resolved = workspaceDir.resolve(normalized).normalize();
        if (!resolved.startsWith(workspaceDir.normalize())) {
            return null;
        }

        return resolved;
    }

    /**
     * 递归清空目录内容（保留目录本身）
     */
    private void cleanDirectoryContents(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (!d.equals(dir)) {
                        Files.delete(d);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean directory {}: {}", dir, e.getMessage());
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 获取 skill 工作区信息
     */
    public Map<String, Object> getWorkspaceInfo(String skillName) {
        Path workspaceDir = resolveConventionPath(skillName);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("skillName", skillName);
        info.put("conventionPath", workspaceDir.toString());
        info.put("exists", Files.exists(workspaceDir));

        if (Files.exists(workspaceDir)) {
            info.put("hasSkillMd", Files.exists(workspaceDir.resolve("SKILL.md")));
            info.put("hasReferences", Files.exists(workspaceDir.resolve("references")));
            info.put("hasScripts", Files.exists(workspaceDir.resolve("scripts")));

            // 计算目录大小
            try {
                AtomicLong size = new AtomicLong(0);
                List<String> files = new ArrayList<>();
                Files.walkFileTree(workspaceDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        size.addAndGet(attrs.size());
                        files.add(workspaceDir.relativize(file).toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
                info.put("totalSizeBytes", size.get());
                info.put("files", files);
            } catch (IOException e) {
                info.put("error", "Failed to scan directory: " + e.getMessage());
            }
        }

        return info;
    }

    // ==================== 预置技能同步 ====================

    /**
     * 将 classpath 下 bundledSkillsPath 目录中的预置技能同步到 workspace root。
     * <p>
     * 规则：
     * <ul>
     *   <li>仅当目标目录不存在时同步（不覆盖用户本地修改）</li>
     *   <li>支持文本和二进制文件（.so、.dll 等按字节流复制）</li>
     *   <li>JAR 和开发模式均可用（基于 Spring ResourcePatternResolver）</li>
     * </ul>
     *
     * @return 同步的技能名称列表
     */
    public List<String> syncBundledSkills() {
        String bundledPath = properties.getBundledSkillsPath();
        if (bundledPath == null || bundledPath.isBlank()) {
            return List.of();
        }

        List<String> synced = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            // 扫描 classpath:skills/**/SKILL.md 来发现预置技能
            String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                    + bundledPath + "/*/SKILL.md";
            Resource[] skillMdResources = resolver.getResources(pattern);

            for (Resource skillMdResource : skillMdResources) {
                String skillName = extractSkillName(skillMdResource, bundledPath);
                if (skillName == null || skillName.isBlank()) {
                    continue;
                }

                Path targetDir = resolveConventionPath(skillName);
                if (Files.exists(targetDir)) {
                    // 版本比对：classpath version > workspace version 时覆盖升级
                    String bundledVersion = parseFrontmatterVersion(skillMdResource);
                    Path workspaceMd = targetDir.resolve("SKILL.md");
                    String workspaceVersion = Files.exists(workspaceMd)
                            ? parseFrontmatterVersionFromPath(workspaceMd) : null;

                    if (bundledVersion != null && isNewerVersion(bundledVersion, workspaceVersion)) {
                        log.info("Bundled skill '{}' version {} > workspace version {}, upgrading",
                                skillName, bundledVersion, workspaceVersion);
                        archiveWorkspace(skillName);
                        syncSingleBundledSkill(resolver, bundledPath, skillName, targetDir);
                        synced.add(skillName);
                        eventPublisher.publishEvent(
                                new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.CREATED, targetDir));
                    } else {
                        log.debug("Bundled skill '{}' workspace version is current (bundled={}, workspace={}), skipping",
                                skillName, bundledVersion, workspaceVersion);
                    }
                    continue;
                }

                // 目录不存在：首次同步
                syncSingleBundledSkill(resolver, bundledPath, skillName, targetDir);
                synced.add(skillName);
                log.info("Synced bundled skill '{}' → {}", skillName, targetDir);
                eventPublisher.publishEvent(
                        new SkillWorkspaceEvent(skillName, SkillWorkspaceEvent.Type.CREATED, targetDir));
            }
        } catch (IOException e) {
            log.warn("Failed to scan bundled skills from classpath:{}/: {}", bundledPath, e.getMessage());
        }

        return synced;
    }

    /**
     * 从 SKILL.md resource 的 URI 中提取技能名称
     * URI 格式如：classpath:skills/etf-analyzer/SKILL.md
     */
    private String extractSkillName(Resource resource, String bundledPath) {
        try {
            String uri = resource.getURI().toString();
            // 找 bundledPath 后的部分：skills/etf-analyzer/SKILL.md → etf-analyzer
            String marker = bundledPath + "/";
            int start = uri.indexOf(marker);
            if (start < 0) return null;
            String remainder = uri.substring(start + marker.length()); // etf-analyzer/SKILL.md
            int slash = remainder.indexOf('/');
            return slash > 0 ? remainder.substring(0, slash) : null;
        } catch (IOException e) {
            log.debug("Failed to extract skill name from resource: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 同步单个预置技能的所有文件到目标目录
     */
    private void syncSingleBundledSkill(ResourcePatternResolver resolver, String bundledPath,
                                        String skillName, Path targetDir) {
        try {
            Files.createDirectories(targetDir);

            // 扫描该技能目录下的所有文件
            String allFilesPattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                    + bundledPath + "/" + skillName + "/**";
            Resource[] allResources = resolver.getResources(allFilesPattern);

            String prefix = bundledPath + "/" + skillName + "/";
            for (Resource res : allResources) {
                if (!res.isReadable()) continue;

                String relativePath = extractRelativePath(res, prefix);
                if (relativePath == null || relativePath.isBlank()) continue;

                // 跳过目录型 resource
                if (relativePath.endsWith("/")) continue;

                Path targetFile = targetDir.resolve(relativePath);
                Files.createDirectories(targetFile.getParent());

                try (InputStream is = res.getInputStream()) {
                    Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to sync bundled skill '{}': {}", skillName, e.getMessage());
        }
    }

    /**
     * 从 resource URI 中提取相对于技能目录的路径
     */
    private String extractRelativePath(Resource resource, String prefix) {
        try {
            String uri = resource.getURI().toString();
            int start = uri.indexOf(prefix);
            if (start < 0) return null;
            return uri.substring(start + prefix.length());
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== 版本比对 ====================

    private static final java.util.regex.Pattern VERSION_PATTERN =
            java.util.regex.Pattern.compile("^version:\\s*[\"']?([^\"'\\s]+)[\"']?", java.util.regex.Pattern.MULTILINE);

    /**
     * 从 classpath Resource (SKILL.md) 的 frontmatter 中提取 version 字段
     */
    private String parseFrontmatterVersion(Resource resource) {
        try (InputStream is = resource.getInputStream()) {
            String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return extractVersionFromContent(content);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从文件路径 (SKILL.md) 的 frontmatter 中提取 version 字段
     */
    private String parseFrontmatterVersionFromPath(Path path) {
        try {
            String content = Files.readString(path);
            return extractVersionFromContent(content);
        } catch (IOException e) {
            return null;
        }
    }

    private String extractVersionFromContent(String content) {
        // 只读 frontmatter 部分（--- 之间）
        if (!content.startsWith("---")) return null;
        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) return null;
        String frontmatter = content.substring(0, endIdx);
        var matcher = VERSION_PATTERN.matcher(frontmatter);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 语义版本比较：newer > older 返回 true
     * <p>
     * 例：1.1.0 > 1.0.0, 2.0.0 > 1.9.9, older=null 视为 0.0.0
     */
    static boolean isNewerVersion(String newer, String older) {
        if (newer == null) return false;
        if (older == null) return true;

        String[] nParts = newer.split("\\.");
        String[] oParts = older.split("\\.");
        int len = Math.max(nParts.length, oParts.length);
        for (int i = 0; i < len; i++) {
            int n = i < nParts.length ? parseSegment(nParts[i]) : 0;
            int o = i < oParts.length ? parseSegment(oParts[i]) : 0;
            if (n > o) return true;
            if (n < o) return false;
        }
        return false; // equal
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 工具方法 ====================

    private String sanitizeName(String name) {
        // 移除不安全字符，只保留字母数字、下划线、短横线、点
        return name.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }

    private String buildDefaultSkillMd(String skillName) {
        return """
                ---
                name: %s
                description: ""
                ---

                # %s

                """.formatted(skillName, skillName);
    }
}
