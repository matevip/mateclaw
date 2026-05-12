package vip.mate.tool.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DelegationContext} stack-based context management.
 * Covers: single-layer enter/exit, nested two-layer restore, depth consistency,
 * and ThreadLocal cleanup.
 */
class DelegationContextTest {

    @AfterEach
    void cleanup() {
        // Ensure ThreadLocal is cleared after each test
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
    }

    // ===== Single-layer enter/exit =====

    @Test
    @DisplayName("Top-level enter/exit cleans up all state")
    void topLevelEnterExitCleansUp() {
        DelegationContext.enter("conv-parent", Set.of("toolA"));

        assertEquals(1, DelegationContext.currentDepth());
        assertEquals("conv-parent", DelegationContext.parentConversationId());
        assertEquals(Set.of("toolA"), DelegationContext.childDeniedTools());

        DelegationContext.exit();

        assertEquals(0, DelegationContext.currentDepth());
        assertNull(DelegationContext.parentConversationId());
        assertEquals(Set.of(), DelegationContext.childDeniedTools());
    }

    @Test
    @DisplayName("No-arg enter sets null parentConversationId and empty deniedTools")
    void noArgEnterDefaults() {
        DelegationContext.enter();

        assertEquals(1, DelegationContext.currentDepth());
        assertNull(DelegationContext.parentConversationId());
        assertEquals(Set.of(), DelegationContext.childDeniedTools());

        DelegationContext.exit();
        assertEquals(0, DelegationContext.currentDepth());
    }

    // ===== Nested two-layer enter/exit =====

    @Test
    @DisplayName("Nested exit restores previous parentConversationId")
    void nestedExitRestoresParentConversationId() {
        // Layer 1
        DelegationContext.enter("conv-L1", Set.of("toolA"));
        assertEquals("conv-L1", DelegationContext.parentConversationId());

        // Layer 2
        DelegationContext.enter("conv-L2", Set.of("toolB"));
        assertEquals(2, DelegationContext.currentDepth());
        assertEquals("conv-L2", DelegationContext.parentConversationId());

        // Exit layer 2 → should restore layer 1
        DelegationContext.exit();
        assertEquals(1, DelegationContext.currentDepth());
        assertEquals("conv-L1", DelegationContext.parentConversationId());

        // Exit layer 1 → should be clean
        DelegationContext.exit();
        assertEquals(0, DelegationContext.currentDepth());
        assertNull(DelegationContext.parentConversationId());
    }

    @Test
    @DisplayName("Nested exit restores previous deniedTools")
    void nestedExitRestoresDeniedTools() {
        Set<String> layer1Tools = Set.of("delegateToAgent", "delegateParallel");
        Set<String> layer2Tools = Set.of("searchWeb");

        DelegationContext.enter("conv-1", layer1Tools);
        DelegationContext.enter("conv-2", layer2Tools);

        assertEquals(layer2Tools, DelegationContext.childDeniedTools());

        DelegationContext.exit();
        assertEquals(layer1Tools, DelegationContext.childDeniedTools());

        DelegationContext.exit();
        assertEquals(Set.of(), DelegationContext.childDeniedTools());
    }

    // ===== Depth consistency =====

    @Test
    @DisplayName("Depth tracks push/pop correctly across 3 layers")
    void depthTracksCorrectly() {
        assertEquals(0, DelegationContext.currentDepth());

        DelegationContext.enter("a", null);
        assertEquals(1, DelegationContext.currentDepth());

        DelegationContext.enter("b", null);
        assertEquals(2, DelegationContext.currentDepth());

        DelegationContext.enter("c", null);
        assertEquals(3, DelegationContext.currentDepth());

        DelegationContext.exit();
        assertEquals(2, DelegationContext.currentDepth());

        DelegationContext.exit();
        assertEquals(1, DelegationContext.currentDepth());

        DelegationContext.exit();
        assertEquals(0, DelegationContext.currentDepth());
    }

    @Test
    @DisplayName("Exit on empty stack is a safe no-op")
    void exitOnEmptyStackIsNoOp() {
        assertEquals(0, DelegationContext.currentDepth());
        DelegationContext.exit(); // should not throw
        assertEquals(0, DelegationContext.currentDepth());
    }

    // ===== ThreadLocal isolation =====

    @Test
    @DisplayName("Separate threads have independent delegation contexts")
    void threadLocalIsolation() throws Exception {
        DelegationContext.enter("main-thread-conv", Set.of("toolX"));

        Thread otherThread = new Thread(() -> {
            assertEquals(0, DelegationContext.currentDepth());
            assertNull(DelegationContext.parentConversationId());
        });
        otherThread.start();
        otherThread.join();

        // Main thread state should be unaffected
        assertEquals(1, DelegationContext.currentDepth());
        assertEquals("main-thread-conv", DelegationContext.parentConversationId());
    }
}
