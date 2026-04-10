package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能文件读取工具
 * 允许 Agent 在运行时读取 skill 内部文件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillFileTool {

    private final SkillRuntimeService runtimeService;
    private final SkillFileAccessPolicy accessPolicy;

    @Tool(description = """
        Read a file from a skill's directory (SKILL.md, references/, or scripts/).
        Use this when you need to access skill documentation or reference files.

        Parameters:
        - skillName: Name of the skill (e.g., "channel_message")
        - filePath: Relative path within skill directory, must start with "references/" or "scripts/"
                    (e.g., "references/config.md", "scripts/helper.py")
                    To read SKILL.md itself, use "SKILL.md" as filePath

        Returns: File content as string, or error message if file not found or access denied.

        Security: Only files under references/ and scripts/ can be accessed. Path traversal is blocked.
        """)
    public String readSkillFile(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Skill name")
        String skillName,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Relative file path (e.g., 'references/doc.md' or 'scripts/run.py')")
        String filePath
    ) {
        log.info("Reading skill file: skill={}, path={}", skillName, filePath);

        // 查找 active skill
        ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
        if (skill == null) {
            return "Error: Skill '" + skillName + "' not found or not enabled";
        }

        // 特殊处理：读取 SKILL.md
        if ("SKILL.md".equals(filePath)) {
            if (skill.getContent() != null && !skill.getContent().isBlank()) {
                return skill.getContent();
            }
            return "Error: SKILL.md content not available";
        }

        // 目录型 skill
        if (skill.getSkillDir() == null) {
            return "Error: Skill '" + skillName + "' is database-based, no file system access available";
        }

        // 验证路径安全性
        Path resolvedPath = accessPolicy.validateAndResolve(skill.getSkillDir(), filePath);
        if (resolvedPath == null) {
            return "Error: Invalid or unsafe file path: " + filePath;
        }

        // 读取文件
        try {
            if (!Files.exists(resolvedPath)) {
                return "Error: File not found: " + filePath;
            }

            if (!Files.isRegularFile(resolvedPath)) {
                return "Error: Path is not a file: " + filePath;
            }

            String content = Files.readString(resolvedPath);
            log.info("Successfully read skill file: {} bytes", content.length());
            return content;

        } catch (Exception e) {
            log.error("Failed to read skill file {}/{}: {}", skillName, filePath, e.getMessage());
            return "Error: Failed to read file: " + e.getMessage();
        }
    }

    @Tool(description = """
        List all files in a skill's references/ and scripts/ directories.
        Use this to explore what files are available in a skill before reading them.

        Parameters:
        - skillName: Name of the skill (e.g., "channel_message")

        Returns: A tree listing of files under references/ and scripts/.
        """)
    public String listSkillFiles(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Skill name")
        String skillName
    ) {
        log.info("Listing skill files: skill={}", skillName);

        ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
        if (skill == null) {
            return "Error: Skill '" + skillName + "' not found or not enabled";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(skillName).append("\n\n");

        if (skill.getSkillDir() != null) {
            sb.append("Source: directory (").append(skill.getSkillDir()).append(")\n\n");
        } else {
            sb.append("Source: database (no file system directory)\n\n");
        }

        // References
        sb.append("references/\n");
        if (skill.getReferences() != null && !skill.getReferences().isEmpty()) {
            formatTree(sb, skill.getReferences(), "  ");
        } else {
            sb.append("  (empty)\n");
        }

        // Scripts
        sb.append("\nscripts/\n");
        if (skill.getScripts() != null && !skill.getScripts().isEmpty()) {
            formatTree(sb, skill.getScripts(), "  ");
        } else {
            sb.append("  (empty)\n");
        }

        return sb.toString();
    }

    @Tool(description = """
        列出所有当前可用的技能（Skills），包括名称、图标和描述。
        使用此工具查看系统中有哪些已启用且可运行的技能。
        注意：这里列出的是技能（Skills），不是 Agent。如需列出可用 Agent，请使用 listAvailableAgents。

        Returns: A formatted list of active skills with name, icon, and description.
        """)
    public String listAvailableSkills() {
        log.info("Listing available skills");

        List<ResolvedSkill> activeSkills = runtimeService.getActiveSkills();

        if (activeSkills.isEmpty()) {
            return "当前没有可用的技能（Skills）。";
        }

        StringBuilder sb = new StringBuilder("可用技能（Skills）列表：\n\n");
        for (ResolvedSkill skill : activeSkills) {
            sb.append("- **").append(skill.getName()).append("**");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription();
                if (desc.length() > 200) {
                    desc = desc.substring(0, 200) + "...";
                }
                sb.append(" — ").append(desc);
            }
            sb.append("\n");
        }

        sb.append("\n共 ").append(activeSkills.size()).append(" 个可用技能。");
        sb.append("\n\n使用 `readSkillFile` 读取技能详情，使用 `runSkillScript` 执行技能脚本。");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void formatTree(StringBuilder sb, Map<String, Object> tree, String indent) {
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                sb.append(indent).append(name).append("/\n");
                formatTree(sb, (Map<String, Object>) value, indent + "  ");
            } else {
                sb.append(indent).append(name).append("\n");
            }
        }
    }
}
