package vip.mate.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt text files from {@code classpath:/prompts/} with a thread-safe
 * lazy cache.
 *
 * <p>Single-language by design: prompts are written in the system's default
 * language and the LLM is trusted to follow the user's input language for
 * its output. The previous {@code loadPrompt(name, locale)} overload and
 * {@code prompts/{locale}/...} fallback chain were never wired up by any
 * caller and have been removed.</p>
 */
@Slf4j
public final class PromptLoader {

    private static final String PROMPT_PATH_PREFIX = "prompts/";

    private static final ConcurrentHashMap<String, String> promptCache = new ConcurrentHashMap<>();

    private PromptLoader() {}

    /**
     * Load a prompt file's contents.
     *
     * @param promptName file name without the {@code prompts/} prefix or {@code .txt} suffix
     *                   (e.g. {@code "graph/summarize-system"})
     * @return file text content
     * @throws RuntimeException when the file is missing or unreadable; the loader never
     *                          silently returns an empty string
     */
    public static String loadPrompt(String promptName) {
        return promptCache.computeIfAbsent(promptName, PromptLoader::readPromptFile);
    }

    private static String readPromptFile(String name) {
        String fileName = PROMPT_PATH_PREFIX + name + ".txt";
        try (InputStream inputStream = PromptLoader.class.getClassLoader().getResourceAsStream(fileName)) {
            if (inputStream == null) {
                throw new RuntimeException("Prompt file not found: " + fileName);
            }
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to load prompt: " + name, e);
        }
    }

    /** Drop the entire cache. Useful for tests and hot-reload tooling. */
    public static void clearCache() {
        promptCache.clear();
    }

    /** Number of prompts currently cached. */
    public static int getCacheSize() {
        return promptCache.size();
    }
}
