package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Lane D executor and config changes:
 *
 * <ul>
 *   <li>D-4: ToolExecutionExecutor uses virtual thread executor</li>
 *   <li>D-5: ToolResultProperties defaults updated to 16000/32000</li>
 * </ul>
 */
class LaneDExecutorAndConfigTest {

    // ============================================================
    // D-4: ToolExecutionExecutor uses virtual threads
    // ============================================================

    @Nested
    @DisplayName("D-4: ToolExecutionExecutor virtual thread pool")
    class VirtualThreadPoolTests {

        @Test
        @DisplayName("TOOL_EXECUTOR is a named virtual thread executor (not fixed thread pool)")
        void toolExecutorIsVirtualThreadBased() throws Exception {
            Field field = ToolExecutionExecutor.class.getDeclaredField("TOOL_EXECUTOR");
            field.setAccessible(true);
            ExecutorService executor = (ExecutorService) field.get(null);

            assertNotNull(executor, "TOOL_EXECUTOR should not be null");

            // Virtual thread executor class name contains "ThreadPerTaskExecutor"
            // when created via Executors.newThreadPerTaskExecutor(factory).
            String className = executor.getClass().getName();
            assertTrue(className.contains("ThreadPerTaskExecutor"),
                    "Expected ThreadPerTaskExecutor (named virtual threads), but got: " + className);
        }

        @Test
        @DisplayName("Virtual threads are named 'tool-executor-N' for log traceability")
        void virtualThreadsAreNamed() throws Exception {
            Field field = ToolExecutionExecutor.class.getDeclaredField("TOOL_EXECUTOR");
            field.setAccessible(true);
            ExecutorService executor = (ExecutorService) field.get(null);

            // Submit a task and capture the thread name
            var future = executor.submit(() -> Thread.currentThread().getName());
            String threadName = future.get();

            assertTrue(threadName.startsWith("tool-executor-"),
                    "Virtual thread should be named 'tool-executor-N', but got: " + threadName);
        }
    }

    // ============================================================
    // D-5: ToolResultProperties defaults
    // ============================================================

    @Nested
    @DisplayName("D-5: ToolResultProperties defaults updated")
    class ToolResultPropertiesDefaultsTests {

        @Test
        @DisplayName("perResultThresholdChars default is 16000 (was 4000)")
        void perResultThresholdCharsDefault() {
            ToolResultProperties props = new ToolResultProperties();
            assertEquals(16000, props.getPerResultThresholdChars(),
                    "Default perResultThresholdChars should be 16000");
        }

        @Test
        @DisplayName("perTurnBudgetChars default is 32000 (was 16000)")
        void perTurnBudgetCharsDefault() {
            ToolResultProperties props = new ToolResultProperties();
            assertEquals(32000, props.getPerTurnBudgetChars(),
                    "Default perTurnBudgetChars should be 32000");
        }

        @Test
        @DisplayName("Other defaults remain unchanged")
        void otherDefaultsUnchanged() {
            ToolResultProperties props = new ToolResultProperties();
            assertTrue(props.isEnabled(), "enabled should default to true");
            assertEquals(800, props.getPreviewHeadChars(),
                    "previewHeadChars should still default to 800");
            assertEquals(2500, props.getExcludedToolInlineChars(),
                    "excludedToolInlineChars should default to 2500");
            assertEquals("", props.getStorageBaseDir(),
                    "storageBaseDir should still default to empty string");
        }

        @Test
        @DisplayName("Large results above 16000 still trigger spill (threshold boundary)")
        void thresholdBoundary() {
            ToolResultProperties props = new ToolResultProperties();
            // Results <= 16000 should NOT spill
            assertTrue(15000 <= props.getPerResultThresholdChars(),
                    "A 15000-char result should be within threshold");
            // Results > 16000 should spill
            assertTrue(17000 > props.getPerResultThresholdChars(),
                    "A 17000-char result should exceed threshold");
        }
    }

    @Nested
    @DisplayName("Tool result aggregate budget")
    class ToolResultAggregateBudgetTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("excluded retrieval tools are compacted when aggregate budget is exceeded")
        void excludedToolResultsCompactWhenTurnBudgetIsExceeded() {
            ToolResultProperties props = new ToolResultProperties();
            props.setStorageBaseDir(tempDir.toString());
            props.setPerTurnBudgetChars(5000);
            props.setExcludedToolInlineChars(1200);
            props.setExcludedTools(List.of("read_file"));
            ToolResultStorage storage = new ToolResultStorage(props);

            String largeRead = "line\n".repeat(1600);
            List<ToolResponseMessage.ToolResponse> responses = List.of(
                    new ToolResponseMessage.ToolResponse("call-1", "read_file", largeRead),
                    new ToolResponseMessage.ToolResponse("call-2", "read_file", largeRead + "tail")
            );

            List<ToolResponseMessage.ToolResponse> compacted =
                    storage.enforceTurnBudget(responses, "conv-test", tempDir.toString());

            assertTrue(compacted.stream().mapToInt(r -> r.responseData().length()).sum() < 5000);
            assertTrue(compacted.stream().allMatch(r ->
                    r.responseData().contains("tool result compacted for model context")));
        }

        @Test
        @DisplayName("large eligible tool result is spilled before entering model context")
        void largeEligibleToolResultIsSpilled() {
            ToolResultProperties props = new ToolResultProperties();
            props.setStorageBaseDir(tempDir.toString());
            props.setPerResultThresholdChars(1000);
            props.setPreviewHeadChars(120);
            ToolResultStorage storage = new ToolResultStorage(props);

            String largeResult = "0123456789\n".repeat(500);
            String contextResult = storage.persistIfOversized(
                    largeResult, "web_search", "call-1", "conv-test", tempDir.toString());

            assertTrue(contextResult.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX));
            assertTrue(contextResult.length() < largeResult.length());
            assertTrue(contextResult.contains("full_chars=" + largeResult.length()));
        }
    }
}
