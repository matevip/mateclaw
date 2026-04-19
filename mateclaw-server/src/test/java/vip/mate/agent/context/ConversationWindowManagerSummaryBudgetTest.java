package vip.mate.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.workspace.conversation.ConversationService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the RFC: prompt-cleanup D bug — the iterative-update
 * branch of {@link ConversationWindowManager#generateSummary} previously
 * used the raw {@code STRUCTURED_SUMMARY_SYSTEM} template without
 * substituting {@code {summary_budget}}, leaking the literal placeholder
 * into the LLM prompt.
 *
 * <p>Both branches must now produce a SystemMessage where {@code {summary_budget}}
 * is replaced by the configured budget number.</p>
 */
class ConversationWindowManagerSummaryBudgetTest {

    private ConversationWindowManager manager;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        ConversationWindowProperties props = new ConversationWindowProperties();
        MemoryManager memory = mock(MemoryManager.class);
        ConversationService conv = mock(ConversationService.class);
        manager = new ConversationWindowManager(props, memory, conv);

        chatModel = mock(ChatModel.class);
        // Return a non-null, non-empty response so generateSummary stores the result.
        Generation gen = new Generation(new org.springframework.ai.chat.messages.AssistantMessage("STUB SUMMARY"),
                ChatGenerationMetadata.NULL);
        ChatResponse response = new ChatResponse(List.of(gen));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    @DisplayName("First-compression branch: {summary_budget} is substituted in SystemMessage")
    void firstCompressionReplacesBudget() throws Exception {
        Prompt sentPrompt = invokeGenerateSummaryAndCapture("conv-first", null);
        SystemMessage system = (SystemMessage) sentPrompt.getInstructions().stream()
                .filter(m -> m instanceof SystemMessage).findFirst().orElseThrow();
        String text = system.getText();
        assertFalse(text.contains("{summary_budget}"),
                "first-compression: literal placeholder must not leak into the SystemMessage");
        assertTrue(text.matches("(?s).*\\d{2,}.*"),
                "first-compression: SystemMessage should contain a numeric budget after substitution");
    }

    @Test
    @DisplayName("Iterative-update branch: {summary_budget} is substituted in SystemMessage")
    void iterativeUpdateReplacesBudget() throws Exception {
        // Seed previousSummaries so generateSummary takes the iterative-update path.
        Field f = ConversationWindowManager.class.getDeclaredField("previousSummaries");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> prev = (ConcurrentHashMap<String, String>) f.get(manager);
        prev.put("conv-iter", "PRIOR SUMMARY (placeholder for the iterative-update branch test)");

        Prompt sentPrompt = invokeGenerateSummaryAndCapture("conv-iter", null);
        SystemMessage system = (SystemMessage) sentPrompt.getInstructions().stream()
                .filter(m -> m instanceof SystemMessage).findFirst().orElseThrow();
        String text = system.getText();
        assertFalse(text.contains("{summary_budget}"),
                "iterative-update: literal placeholder must not leak into the SystemMessage (the bug regression guard)");
    }

    /**
     * Reflectively invoke the private {@code generateSummary} method and capture
     * the {@link Prompt} sent to the mocked {@link ChatModel}.
     */
    private Prompt invokeGenerateSummaryAndCapture(String conversationId, String memoryExtra) throws Exception {
        // Two synthetic user messages so serializeForSummary produces non-empty content.
        List<Message> oldMessages = List.of(
                new UserMessage("hello"),
                new UserMessage("world"));

        Method m = ConversationWindowManager.class.getDeclaredMethod(
                "generateSummary", List.class, ChatModel.class, String.class, int.class, String.class);
        m.setAccessible(true);
        m.invoke(manager, oldMessages, chatModel, conversationId, 1500, memoryExtra);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(chatModel).call(captor.capture());
        return captor.getValue();
    }
}
