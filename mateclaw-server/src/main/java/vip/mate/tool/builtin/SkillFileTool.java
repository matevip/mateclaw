package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.skill.runtime.SkillCatalogSort;
import vip.mate.skill.runtime.SkillCatalogSorter;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.usage.SkillUsageService;

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
    private final SkillUsageService usageService;

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
        String filePath,

        @Nullable ToolContext ctx
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
                log.info("Skill loaded: skill={}, path=SKILL.md, bytes={}, estimatedTokens={}",
                        skillName, skill.getContent().length(), TokenEstimator.estimateTokens(skill.getContent()));
                recordLoaded(skill, "SKILL.md", skill.getContent(), ctx);
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
            log.info("Skill loaded: skill={}, path={}, bytes={}, estimatedTokens={}",
                    skillName, filePath, content.length(), TokenEstimator.estimateTokens(content));
            recordLoaded(skill, filePath, content, ctx);
            return content;

        } catch (Exception e) {
            log.error("Failed to read skill file {}/{}: {}", skillName, filePath, e.getMessage());
            return "Error: Failed to read file: " + e.getMessage();
        }
    }

    private void recordLoaded(ResolvedSkill skill, String filePath, String content, @Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        usageService.recordLoaded(
                skill,
                origin.agentId(),
                origin.conversationId(),
                filePath,
                TokenEstimator.estimateTokens(content));
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
        List all currently available Skills (documentation packages).

        IMPORTANT: Skills are NOT directly callable as tools. Each name
        returned here is a `skillName` argument, not a tool name. To use
        a skill, call `readSkillFile(skillName="<name>", filePath="SKILL.md")`
        first to read its instructions, then follow what SKILL.md tells you.
        Calling a skill name as a tool will fail with "Tool not found".

        Note: this returns Skills (vendor-installable docs), not Agents.
        For Agents, use `listAvailableAgents`.

        Returns: A formatted list of active skills with name, icon, and description.
        """)
    public String listAvailableSkills(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional keyword matched against skill name or description")
        String keyword,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional source filter: all, builtin, dynamic, mcp, acp")
        String source,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional status filter: all, ready, setup_needed, disabled, blocked")
        String status,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Maximum number of skills to return, default 20, max 50")
        Integer limit
    ) {
        log.info("Listing available skills");

        int safeLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 50);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<ResolvedSkill> activeSkills = SkillCatalogSorter.sortResolved(
                runtimeService.getActiveSkills().stream()
                        .filter(s -> SkillCatalogSorter.sourceMatches(s, source))
                        .filter(s -> SkillCatalogSorter.runtimeMatches(s, status))
                        .filter(s -> kw.isEmpty()
                                || containsIgnoreCase(s.getName(), kw)
                                || containsIgnoreCase(s.getDescription(), kw))
                        .toList(),
                SkillCatalogSort.RECOMMENDED);

        if (activeSkills.isEmpty()) {
            return "No skills are currently available.";
        }

        // Issue #46: render as a table with the call pattern stated up front,
        // instead of a `- **Name** — desc` list that primes the LLM to call
        // the names directly as tools.
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️  These are Skills (documentation packages), NOT directly callable tools.\n");
        sb.append("To use any of them, call:\n");
        sb.append("  readSkillFile(skillName=\"<name from below>\", filePath=\"SKILL.md\")\n");
        sb.append("then follow what SKILL.md tells you (typically `runSkillScript`).\n\n");
        sb.append("| Skill name | Status | Description |\n");
        sb.append("|------------|--------|-------------|\n");
        for (ResolvedSkill skill : activeSkills.stream().limit(safeLimit).toList()) {
            sb.append("| `").append(skill.getName()).append("`");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            sb.append(" | ").append(statusToken(skill)).append(" | ");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription();
                if (desc.length() > 120) {
                    desc = desc.substring(0, 120) + "...";
                }
                sb.append(desc.replace("|", "\\|").replace("\n", " "));
            }
            sb.append(" |\n");
        }
        sb.append("\nShowing: ").append(Math.min(safeLimit, activeSkills.size()))
                .append(" of ").append(activeSkills.size()).append(" skill(s).");
        return sb.toString();
    }

    private static boolean containsIgnoreCase(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase().contains(lowerCaseNeedle);
    }

    private static String statusToken(ResolvedSkill skill) {
        if (skill.isSecurityBlocked()) return "blocked";
        if (!skill.isEnabled()) return "disabled";
        if (!SkillRuntimeService.passesActiveGate(skill)) return "setup-needed";
        return "ready";
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
