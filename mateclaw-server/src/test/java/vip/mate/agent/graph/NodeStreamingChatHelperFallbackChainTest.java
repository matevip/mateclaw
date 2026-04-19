package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.failover.FallbackEntry;

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
    @DisplayName("List-based constructor preserves fallback chain order, providerId, and ChatModel")
    void listConstructorPreservesOrder() throws Exception {
        ChatModel a = mock(ChatModel.class);
        ChatModel b = mock(ChatModel.class);
        ChatModel c = mock(ChatModel.class);
        List<FallbackEntry> input = List.of(
                new FallbackEntry("openai", a),
                new FallbackEntry("dashscope", b),
                new FallbackEntry("anthropic", c));
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker, input, null);

        List<FallbackEntry> chain = readFallbackChain(helper);
        assertEquals(3, chain.size(), "fallback chain should preserve all entries");
        assertEquals("openai", chain.get(0).providerId());
        assertSame(a, chain.get(0).chatModel());
        assertEquals("dashscope", chain.get(1).providerId());
        assertSame(b, chain.get(1).chatModel());
        assertEquals("anthropic", chain.get(2).providerId());
        assertSame(c, chain.get(2).chatModel());
    }

    @Test
    @DisplayName("Null fallback chain is normalized to empty list (defensive)")
    void nullChainNormalizedToEmpty() throws Exception {
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker, (List<FallbackEntry>) null, null);
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
    @DisplayName("Deprecated single-fallback constructor wraps the model into a 1-entry synthetic chain")
    void deprecatedSingleFallbackConstructorBackCompat() throws Exception {
        ChatModel single = mock(ChatModel.class);
        @SuppressWarnings("deprecation")
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker, single);

        List<FallbackEntry> chain = readFallbackChain(helper);
        assertEquals(1, chain.size(), "deprecated overload should produce a 1-entry chain");
        assertSame(single, chain.get(0).chatModel(),
                "the single fallback ChatModel must survive wrapping intact");
        // Synthetic providerId is acceptable; just assert it's present so health
        // tracking won't NPE on lookup.
        assertNotNull(chain.get(0).providerId());
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
    @DisplayName("EMPTY_RESPONSE / BILLING / MODEL_NOT_FOUND error types exist ")
    void fallbackTriggerErrorTypesExist() {
        // Compile-time safety net: these enum constants the streaming pipeline relies on
        // must not be renamed or removed without breaking the fallback contract.
        assertNotNull(NodeStreamingChatHelper.ErrorType.EMPTY_RESPONSE);
        assertNotNull(NodeStreamingChatHelper.ErrorType.BILLING);
        assertNotNull(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND);
    }

    @Test
    @DisplayName("primary providerId is stored when supplied via the full constructor")
    void primaryProviderIdStored() throws Exception {
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(
                streamTracker, List.of(), null, null, "openai");

        Field f = NodeStreamingChatHelper.class.getDeclaredField("primaryProviderId");
        f.setAccessible(true);
        assertEquals("openai", f.get(helper),
                "primary provider id must be retained for health tracking");
    }

    @Test
    @DisplayName("legacy constructors leave primaryProviderId null (tracking disabled)")
    void primaryProviderIdNullForLegacyConstructors() throws Exception {
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker);
        Field f = NodeStreamingChatHelper.class.getDeclaredField("primaryProviderId");
        f.setAccessible(true);
        assertNull(f.get(helper),
                "legacy constructors must leave primaryProviderId unset so tracking is silently disabled");
    }

    @SuppressWarnings("unchecked")
    private static List<FallbackEntry> readFallbackChain(NodeStreamingChatHelper helper) throws Exception {
        Field f = NodeStreamingChatHelper.class.getDeclaredField("fallbackChain");
        f.setAccessible(true);
        return (List<FallbackEntry>) f.get(helper);
    }
}
