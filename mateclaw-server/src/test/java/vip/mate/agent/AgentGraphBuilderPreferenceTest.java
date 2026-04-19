package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RFC-009 PR-3 — verifies the agent-preference reorder used by
 * {@link AgentGraphBuilder#buildFallbackChain}: listed providers move to the
 * front in their declared order; unlisted providers keep their original
 * relative order; missing/duplicate preferences are ignored gracefully.
 */
class AgentGraphBuilderPreferenceTest {

    private static ModelProviderEntity p(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        return p;
    }

    private static List<String> ids(List<ModelProviderEntity> ps) {
        return ps.stream().map(ModelProviderEntity::getProviderId).toList();
    }

    @Test
    @DisplayName("Empty preferences: original order preserved")
    void noPreferences() {
        var input = List.of(p("openai"), p("anthropic"), p("dashscope"));
        var out = AgentGraphBuilder.reorderByPreferences(input, List.of());
        assertEquals(List.of("openai", "anthropic", "dashscope"), ids(out));
    }

    @Test
    @DisplayName("Single preference: preferred provider moves to front, rest follow original order")
    void singlePreferenceFront() {
        var input = List.of(p("openai"), p("anthropic"), p("dashscope"));
        var out = AgentGraphBuilder.reorderByPreferences(input, List.of("dashscope"));
        assertEquals(List.of("dashscope", "openai", "anthropic"), ids(out));
    }

    @Test
    @DisplayName("Multiple preferences: preferred order matches declaration, rest stable")
    void multiplePreferencesOrder() {
        var input = List.of(p("openai"), p("anthropic"), p("dashscope"), p("kimi"));
        var out = AgentGraphBuilder.reorderByPreferences(input, List.of("kimi", "anthropic"));
        // kimi → anthropic → (rest in original order: openai, dashscope)
        assertEquals(List.of("kimi", "anthropic", "openai", "dashscope"), ids(out));
    }

    @Test
    @DisplayName("Preference references unknown provider: silently skipped")
    void preferenceReferencesUnknown() {
        var input = List.of(p("openai"), p("anthropic"));
        var out = AgentGraphBuilder.reorderByPreferences(input, List.of("ghost", "anthropic"));
        assertEquals(List.of("anthropic", "openai"), ids(out));
    }

    @Test
    @DisplayName("Duplicate preferences: each provider appears at most once")
    void duplicatePreferencesDeduped() {
        var input = List.of(p("openai"), p("anthropic"));
        var out = AgentGraphBuilder.reorderByPreferences(input, List.of("openai", "openai", "anthropic"));
        assertEquals(List.of("openai", "anthropic"), ids(out));
    }

    @Test
    @DisplayName("All providers preferred: input pure-reordered, no drops")
    void allProvidersPreferred() {
        var input = List.of(p("openai"), p("anthropic"), p("dashscope"));
        var out = AgentGraphBuilder.reorderByPreferences(input,
                List.of("dashscope", "openai", "anthropic"));
        assertEquals(List.of("dashscope", "openai", "anthropic"), ids(out));
    }
}
