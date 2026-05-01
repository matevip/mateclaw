package vip.mate.llm.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelCapabilityService.Modality;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RFC-090 §9.2 调整 C — diagnostics-first ProviderRouter.
 *
 * <p>This first iteration does not yet rewrite the fallback chain order
 * (the existing {@code AgentBindingService.getPreferredProviderIds} +
 * {@link vip.mate.agent.AgentGraphBuilder#buildFallbackChain} flow is
 * already in place). Instead it:
 *
 * <ol>
 *   <li>Aggregates {@code requires-model} from the agent's bound skills'
 *       manifests.</li>
 *   <li>Compares the union against the primary model's resolved
 *       capability set ({@link ModelCapabilityService#resolve}).</li>
 *   <li>Logs a clear WARN if a capability is missing — surfacing the
 *       same gap RFC-085's "ready" badge would render in UI.</li>
 * </ol>
 *
 * <p>Promoting this to actual chain re-ordering (i.e. "prefer providers
 * that satisfy modelNeeds") is straightforward once we have the data
 * for it: add a phase between {@code reorderByPreferences} and the
 * model build loop. That phase is intentionally not in this commit so
 * we can ship the diagnostics path independently and watch it in dev.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderRouter {

    private final SkillRuntimeService skillRuntimeService;
    private final AgentBindingService bindingService;
    private final ModelCapabilityService capabilityService;
    private final ModelConfigService modelConfigService;

    /**
     * Compute the union of capability requirements declared by the
     * skills bound to {@code agentId}. Returns an empty set when no
     * bindings exist or no skill declares {@code requires-model}.
     */
    public Set<String> aggregateModelNeeds(Long agentId) {
        if (agentId == null || skillRuntimeService == null) return Set.of();
        Set<Long> boundSkillIds = bindingService.getBoundSkillIds(agentId);
        if (boundSkillIds == null || boundSkillIds.isEmpty()) return Set.of();
        List<ResolvedSkill> all = skillRuntimeService.resolveAllSkillsStatus();
        Set<String> needs = new LinkedHashSet<>();
        for (ResolvedSkill r : all) {
            if (r == null || r.getId() == null) continue;
            if (!boundSkillIds.contains(r.getId())) continue;
            SkillManifest m = r.getManifest();
            if (m == null) continue;
            List<String> declared = m.getRequiresModel();
            if (declared == null || declared.isEmpty()) continue;
            needs.addAll(declared);
        }
        return needs;
    }

    /**
     * Diagnostic check: does the chosen primary model satisfy every
     * skill-declared capability? Logs a single WARN per gap.
     *
     * <p>Intentionally never throws — this is observability, not policy.
     */
    public void diagnosePrimary(Long agentId, ModelConfigEntity primary) {
        if (primary == null || agentId == null) return;
        Set<String> needs = aggregateModelNeeds(agentId);
        if (needs.isEmpty()) return;
        EnumSet<Modality> resolved = capabilityService.resolve(
                primary.getModelName(), primary.getModalities());
        for (String need : needs) {
            Modality required = mapToModality(need);
            if (required == null) continue; // capability we can't translate (e.g. function_calling) — skip
            if (!resolved.contains(required)) {
                log.warn("[ProviderRouter] agent={} primary={}/{} missing capability '{}' " +
                                "required by bound skills (resolved: {})",
                        agentId, primary.getProvider(), primary.getModelName(),
                        need, resolved);
            }
        }
    }

    /**
     * Translate a manifest {@code requires-model} token to a
     * {@link Modality}. Tokens that don't map (e.g. {@code function_calling},
     * {@code long_context_100k}) return null — the caller skips diagnostics
     * for them rather than emit a noisy warning we can't act on yet.
     */
    private Modality mapToModality(String need) {
        if (need == null) return null;
        String n = need.trim().toLowerCase();
        return switch (n) {
            case "vision", "image", "vl" -> Modality.VISION;
            case "video" -> Modality.VIDEO;
            case "audio", "speech" -> Modality.AUDIO;
            default -> null;
        };
    }

    /**
     * Convenience for tests / health checks: return the current model's
     * capability resolution as a structured summary (provider/model →
     * modalities).
     */
    public String summarize(Long agentId) {
        try {
            ModelConfigEntity primary = modelConfigService.getDefaultModel();
            EnumSet<Modality> resolved = capabilityService.resolve(
                    primary.getModelName(), primary.getModalities());
            Set<String> needs = aggregateModelNeeds(agentId);
            return String.format("primary=%s/%s modalities=%s needs=%s",
                    primary.getProvider(), primary.getModelName(), resolved, needs);
        } catch (Exception e) {
            return "ProviderRouter summary unavailable: " + e.getMessage();
        }
    }

    /** {@code @Lazy} hint — kept for symmetry if AgentBindingService is constructed first. */
    @Lazy
    public void noopForLazyHint() { /* no-op */ }
}
