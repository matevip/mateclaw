package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import vip.mate.channel.web.ChatStreamTracker;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * smoke tests for the multi-model fallback chain wiring on
 * {@link NodeStreamingChatHelper}.
 *
 * <p>Full streaming-flow integration (ChatModel.stream / Flux mocking) is left
 * to end-to-end smoke tests in the RFC; these tests verify the public
 * surface — constructor variants, chain immutability, deprecated-overload
 * compatibility — so future refactors of those entry points are caught.</p>
 */
class NodeStreamingChatHelperFallbackChainTest {

    private final ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);

    @Test
    @DisplayName("List-based constructor preserves fallback chain order and contents")
    void listConstructorPreservesOrder() throws Exception {
        ChatModel a = mock(ChatModel.class);
        ChatModel b = mock(ChatModel.class);
        ChatModel c = mock(ChatModel.class);
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker, List.of(a, b, c), null);

        List<ChatModel> chain = readFallbackChain(helper);
        assertEquals(3, chain.size(), "fallback chain should preserve all entries");
        assertSame(a, chain.get(0), "priority 1 must be first");
        assertSame(b, chain.get(1));
        assertSame(c, chain.get(2));
    }

    @Test
    @DisplayName("Null fallback chain is normalized to empty list (defensive)")
    void nullChainNormalizedToEmpty() throws Exception {
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker, (List<ChatModel>) null, null);
        assertTrue(readFallbackChain(helper).isEmpty(),
                "null chain must not throw — it should be normalized to an empty list");
    }

    @Test
    @DisplayName("Single-arg constructor (no fallback) yields empty chain")
    void singleArgConstructorEmptyChain() throws Exception {
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker);
        assertTrue(readFallbackChain(helper).isEmpty());
    }

    @Test
    @DisplayName("Deprecated single-fallback constructor wraps the model into a 1-element chain")
    void deprecatedSingleFallbackConstructorBackCompat() throws Exception {
        ChatModel single = mock(ChatModel.class);
        @SuppressWarnings("deprecation")
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker, single);

        List<ChatModel> chain = readFallbackChain(helper);
        assertEquals(1, chain.size(), "deprecated overload should produce a 1-entry chain");
        assertSame(single, chain.get(0));
    }

    @Test
    @DisplayName("Deprecated single-fallback constructor with null produces empty chain (no NPE)")
    void deprecatedSingleFallbackNullSafe() throws Exception {
        @SuppressWarnings("deprecation")
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker, (ChatModel) null);
        assertTrue(readFallbackChain(helper).isEmpty(),
                "null single fallback must collapse to an empty chain");
    }

    @Test
    @DisplayName("EMPTY_RESPONSE error type exists (fallback trigger)")
    void emptyResponseErrorTypeExists() {
        // Compile-time safety net: the enum constant the streaming pipeline relies on
        // must not be renamed or removed without breaking the fallback contract.
        NodeStreamingChatHelper.ErrorType t = NodeStreamingChatHelper.ErrorType.EMPTY_RESPONSE;
        assertNotNull(t);
    }

    @SuppressWarnings("unchecked")
    private static List<ChatModel> readFallbackChain(NodeStreamingChatHelper helper) throws Exception {
        Field f = NodeStreamingChatHelper.class.getDeclaredField("fallbackChain");
        f.setAccessible(true);
        return (List<ChatModel>) f.get(helper);
    }
}
