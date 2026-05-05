package vip.mate.skill.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 技能脚本执行服务
 * 安全执行 scripts/ 目录下的脚本
 * <p>
 * 输出重定向到临时文件，确保 timeout 不被管道阻塞失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillScriptExecutionService {

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 50_000;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /**
     * 执行脚本（兼容签名 — 不注入额外 env vars）
     *
     * @param scriptPath 脚本绝对路径（已验证安全）
     * @param args 脚本参数
     * @return 执行结果
     */
    public ScriptResult execute(Path scriptPath, List<String> args) {
        return execute(scriptPath, args, Collections.emptyMap());
    }

    /**
     * 执行脚本，附加 env vars 到子进程环境（RFC-091 settings bridge）。
     * 用于把 skill 在 mate_skill_secret 里存的解密 secret 注入到脚本运行
     * 时环境，让 SKILL.md 里 {@code $AIRTABLE_API_KEY} 等引用自然解析。
     *
     * <p>{@code envVars} 中的键值会 OVERRIDE 父进程同名环境变量；空值跳过。
     *
     * @param scriptPath 脚本绝对路径（已验证安全）
     * @param args 脚本参数
     * @param envVars 要注入子进程环境的额外键值对，可为空
     * @return 执行结果
     */
    public ScriptResult execute(Path scriptPath, List<String> args, Map<String, String> envVars) {
        if (!Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            return ScriptResult.error(-1, "Script not found: " + scriptPath);
        }

        Path stdoutFile = null;
        Path stderrFile = null;

        try {
            // 构建命令（结构化参数，避免 shell 注入）
            List<String> command = new ArrayList<>();

            // 根据文件扩展名选择解释器（跨平台适配）
            String fileName = scriptPath.getFileName().toString();
            if (fileName.endsWith(".py")) {
                // Windows 通常只有 python，没有 python3
                command.add(IS_WINDOWS ? "python" : "python3");
            } else if (fileName.endsWith(".sh")) {
                if (IS_WINDOWS) {
                    return ScriptResult.error(-1,
                            "Shell scripts (.sh) are not supported on Windows. " +
                            "Consider providing a .bat or .ps1 alternative.");
                }
                command.add("bash");
            } else if (fileName.endsWith(".bat") || fileName.endsWith(".cmd")) {
                if (!IS_WINDOWS) {
                    return ScriptResult.error(-1,
                            "Batch scripts (.bat/.cmd) are only supported on Windows.");
                }
                command.add("cmd.exe");
                command.add("/D");
                command.add("/C");
            } else if (fileName.endsWith(".ps1")) {
                command.add("powershell");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
            } else if (fileName.endsWith(".js")) {
                command.add("node");
            } else {
                if (!IS_WINDOWS && !Files.isExecutable(scriptPath)) {
                    return ScriptResult.error(-1, "Script not executable: " + fileName);
                }
            }

            command.add(scriptPath.toString());
            if (args != null) {
                command.addAll(args);
            }

            // 重定向到临时文件，使 waitFor(timeout) 不被管道阻塞
            stdoutFile = Files.createTempFile("mc_script_out_", ".tmp");
            stderrFile = Files.createTempFile("mc_script_err_", ".tmp");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(scriptPath.getParent().toFile());
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());
            // RFC-091: inject per-skill secrets / settings as env vars.
            // pb.environment() inherits the parent process env; putAll
            // OVERRIDES same-named entries with the supplied values.
            // Null / blank values are skipped to avoid clearing
            // legitimate parent env vars.
            if (envVars != null && !envVars.isEmpty()) {
                Map<String, String> processEnv = pb.environment();
                for (Map.Entry<String, String> e : envVars.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    if (e.getValue() == null) continue;
                    processEnv.put(e.getKey(), e.getValue());
                }
            }

            Process process = pb.start();

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                killProcess(process);
                String stdout = readFileTruncated(stdoutFile, MAX_OUTPUT_BYTES);
                String stderr = readFileTruncated(stderrFile, MAX_OUTPUT_BYTES);
                String timeoutMsg = "[timeout after " + DEFAULT_TIMEOUT_SECONDS + "s]";
                stderr = stderr.isEmpty() ? timeoutMsg : stderr + "\n" + timeoutMsg;
                return new ScriptResult(-1, stdout, stderr);
            }

            int exitCode = process.exitValue();
            String stdout = readFileTruncated(stdoutFile, MAX_OUTPUT_BYTES);
            String stderr = readFileTruncated(stderrFile, MAX_OUTPUT_BYTES);
            return new ScriptResult(exitCode, stdout, stderr);

        } catch (Exception e) {
            log.error("Failed to execute script {}: {}", scriptPath, e.getMessage());
            return ScriptResult.error(-1, "Execution error: " + e.getMessage());
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    private static void killProcess(Process process) {
        if (IS_WINDOWS) {
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(process.pid()))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                process.destroyForcibly();
            }
        } else {
            process.destroyForcibly();
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readFileTruncated(Path file, int maxBytes) {
        try {
            if (file == null || !Files.exists(file)) return "";
            long size = Files.size(file);
            if (size == 0) return "";

            boolean truncated = size > maxBytes;
            try (InputStream is = Files.newInputStream(file)) {
                byte[] data = is.readNBytes(maxBytes);
                String content = new String(data, StandardCharsets.UTF_8);
                if (truncated) {
                    content += "\n... [输出已截断，超过 " + maxBytes + " 字节限制]";
                }
                return content;
            }
        } catch (IOException e) {
            return "[读取输出失败: " + e.getMessage() + "]";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ScriptResult {
        private int exitCode;
        private String stdout;
        private String stderr;

        public static ScriptResult error(int code, String message) {
            return new ScriptResult(code, "", message);
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
