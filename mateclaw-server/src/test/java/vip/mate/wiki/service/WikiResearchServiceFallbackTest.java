package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.i18n.I18nService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link WikiResearchService} surfaces fallback strings via
 * {@link I18nService} (RFC: prompt-cleanup E2) instead of literal Chinese
 * hardcodes when the LLM produces empty output. The plan-stage failure path
 * is exercised because it is the cleanest to mock — a null LLM response
 * causes {@code planStage} to return an empty list, which triggers the
 * {@code research.fallback.no_plan} branch.
 */
class WikiResearchServiceFallbackTest {

    private HybridRetriever hybridRetriever;
    private WikiRawMaterialService rawService;
    private ModelConfigService modelConfigService;
    private AgentGraphBuilder agentGraphBuilder;
    private ChatStreamTracker streamTracker;
    private I18nService i18n;
    private ChatModel chatModel;
    private WikiResearchService service;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridRetriever.class);
        rawService = mock(WikiRawMaterialService.class);
        modelConfigService = mock(ModelConfigService.class);
        agentGraphBuilder = mock(AgentGraphBuilder.class);
        streamTracker = mock(ChatStreamTracker.class);
        i18n = mock(I18nService.class);
        chatModel = mock(ChatModel.class);

        ModelConfigEntity model = mock(ModelConfigEntity.class);
        when(modelConfigService.getDefaultModel()).thenReturn(model);
        when(agentGraphBuilder.buildRuntimeChatModel(any(), any())).thenReturn(chatModel);
        // Empty-response stub: planStage will return List.of() and trigger the no_plan fallback.
        ChatResponse empty = mock(ChatResponse.class);
        when(empty.getResult()).thenReturn(null);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(empty);

        when(i18n.msg(anyString())).thenAnswer(inv -> "I18N[" + inv.getArgument(0) + "]");
        when(i18n.msg(anyString(), any())).thenAnswer(inv -> "I18N[" + inv.getArgument(0) + "]");

        service = new WikiResearchService(
                hybridRetriever, rawService, modelConfigService,
                agentGraphBuilder, streamTracker, i18n);
    }

    @Test
    @DisplayName("Empty plan response → report comes from i18n, not literal Chinese")
    void noPlanFallback_usesI18n() {
        WikiResearchService.ResearchResult result = service.research(1L, "Test topic", "sess-1", 5);

        assertEquals("I18N[research.fallback.no_plan]", result.report(),
                "report must come from i18n.msg(\"research.fallback.no_plan\")");
        assertTrue(result.sections().isEmpty());

        // Also verify the broadcast went through i18n (not a Chinese literal).
        verify(i18n).msg("research.broadcast.no_plan");
        verify(i18n).msg("research.fallback.no_plan");
        // Critical regression guard: never emit the old literal.
        assertFalse(result.report().contains("无法为该主题生成研究计划"),
                "literal Chinese fallback must not leak through after the i18n migration");
    }

    // Note: the research() catch-block fallback (research.fallback.failed) is not unit-tested
    // here because callLlm internally swallows LLM exceptions and returns null, so they never
    // propagate up to research()'s try/catch. Triggering that branch cleanly would require
    // mocking an exception inside draftStage (post planStage). Coverage is left to the
    // end-to-end smoke test described in the RFC.

    @Test
    @DisplayName("Compose-fallback path uses neutral [Q]/[M] tokens (no '### 子问题' / '- 材料')")
    void composeFallbackUsesNeutralTokens() throws Exception {
        // Reach into composeStage via reflection with a non-empty section list so the
        // built-in fallback string is exercised. The compose LLM call returns null
        // (chatModel mocked above), so composeStage falls through to the manual concat.
        var section = new WikiResearchService.Section(
                "What is X?",
                "Answer body referencing [M1].",
                List.of(new WikiResearchService.MaterialRef(1, 100L, 200L, "Source A")));

        java.lang.reflect.Method compose = WikiResearchService.class.getDeclaredMethod(
                "composeStage", String.class, List.class);
        compose.setAccessible(true);
        String fallbackReport = (String) compose.invoke(service, "Topic", List.of(section));

        // E3 regression guard: the assembled fallback report must use neutral tokens.
        assertFalse(fallbackReport.contains("### 子问题"), "must not contain Chinese assembly tag '### 子问题'");
        assertFalse(fallbackReport.contains("- 材料"), "must not contain Chinese assembly tag '- 材料'");
        assertTrue(fallbackReport.contains("[Q1]"), "must contain neutral [Q1] token");
    }
}
