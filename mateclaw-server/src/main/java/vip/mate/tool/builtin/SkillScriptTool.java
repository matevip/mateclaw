package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.SkillScriptExecutionService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.secret.SkillSecretService;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 技能脚本执行工具
 * 允许 Agent 在运行时执行 skill 内部脚本
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillScriptTool {

    private final SkillRuntimeService runtimeService;
    private final SkillFileAccessPolicy accessPolicy;
    private final SkillScriptExecutionService executionService;
    private final SkillSecretService skillSecretService;

    @vip.mate.tool.ConcurrencyUnsafe("script execution can have arbitrary side effects on the host process and filesystem")
    @Tool(description = """
        Execute a script from a skill's scripts/ directory.
        Use this when you need to run skill-provided automation or utilities.

        Parameters:
        - skillName: Name of the skill
        - scriptPath: Relative path to script under scripts/ directory (e.g., "scripts/run.py")
        - args: Optional list of script arguments. Each element is passed as a separate
                CLI argument exactly as written — no shell interpretation, no splitting.
                For a JSON payload, wrap it as a single-element list, e.g.
                ["{\\"date\\":\\"2026-05-12\\",\\"topic\\":\\"meeting\\"}"].

        Returns: JSON with exitCode, stdout, stderr

        Security: Only scripts under scripts/ directory can be executed. Path traversal is blocked.
        Timeout: 30 seconds per script execution.
        """)
    public String runSkillScript(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Skill name")
        String skillName,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Script path relative to skill directory (e.g., 'scripts/run.py')")
        String scriptPath,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional list of script arguments. Each element is passed as one CLI arg verbatim. Wrap a JSON payload as a single-element list.")
        List<String> args
    ) {
        log.info("Executing skill script: skill={}, script={}, args={}", skillName, scriptPath, args);

        // Look up active skill.
        ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
        if (skill == null) {
            return formatError("Skill '" + skillName + "' not found or not enabled");
        }

        // Must be a directory-backed skill.
        if (skill.getSkillDir() == null) {
            return formatError("Skill '" + skillName + "' is database-based, no script execution available");
        }

        // Validate script path (must live under scripts/).
        Path resolvedPath = accessPolicy.validateScriptPath(skill.getSkillDir(), scriptPath);
        if (resolvedPath == null) {
            return formatError("Invalid or unsafe script path: " + scriptPath);
        }

        // Pass args straight through. No splitting — arbitrary delimiters
        // (notably commas inside JSON payloads) used to shatter a single
        // logical argument into multiple positional args, which broke any
        // skill expecting a JSON-encoded payload.
        List<String> argList = (args == null || args.isEmpty()) ? null : args;

        // RFC-091 settings bridge — pull this skill's stored secrets
        // (e.g. AIRTABLE_API_KEY) and inject them as env vars for the
        // subprocess. Decryption happens here, on the way to the child
        // process; the plaintext never lives in the rendered SKILL.md.
        Map<String, String> envVars = skill.getId() != null
                ? skillSecretService.getDecrypted(skill.getId())
                : Collections.emptyMap();

        // 执行脚本
        try {
            SkillScriptExecutionService.ScriptResult result = executionService.execute(resolvedPath, argList, envVars);
            return formatResult(result);

        } catch (Exception e) {
            log.error("Failed to execute skill script /{}: {}", skillName, scriptPath, e.getMessage());
            return formatError("Execution failed: " + e.getMessage());
        }
    }

    private String formatResult(SkillScriptExecutionService.ScriptResult result) {
        return String.format(
            "{\n  \"exitCode\": %d,\n  \"stdout\": %s,\n  \"stderr\": %s\n}",
            result.getExitCode(),
            jsonEscape(result.getStdout()),
            jsonEscape(result.getStderr())
        );
    }

    private String formatError(String message) {
        return String.format(
            "{\n  \"exitCode\": -1,\n  \"stdout\": \"\",\n  \"stderr\": %s\n}",
            jsonEscape(message)
        );
    }

    private String jsonEscape(String str) {
        if (str == null || str.isEmpty()) {
            return "\"\"";
        }
        return "\"" + str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }
}
