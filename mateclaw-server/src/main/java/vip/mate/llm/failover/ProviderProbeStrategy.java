package vip.mate.llm.failover;

import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

/**
 * Strategy contract for actively probing one provider's reachability.
 *
 * <p>One implementation per {@link ModelProtocol}. {@code ProviderInitProbe}
 * dispatches by {@link #supportedProtocol()}. New protocols add a new
 * {@code @Component} implementing this interface — no edits to
 * {@code ProviderInitProbe} required.</p>
 *
 * <p>Implementations should:</p>
 * <ul>
 *   <li>Prefer free endpoints (e.g., {@code GET /v1/models} for OpenAI / Anthropic)
 *       over chat completions whenever possible — keeps probe cost at zero.</li>
 *   <li>Use a short HTTP timeout (~5s) so a stalled probe doesn't block
 *       the parallel batch.</li>
 *   <li>Never throw — wrap exceptions into {@link ProbeResult#fail}.</li>
 * </ul>
 */
public interface ProviderProbeStrategy {

    /** The protocol this probe handles; {@code ProviderInitProbe} routes by this key. */
    ModelProtocol supportedProtocol();

    /**
     * Probe the given provider. Must complete (success or fail) within the
     * caller's timeout budget. Implementations are expected to honor a
     * conservative HTTP timeout (~5s) internally.
     */
    ProbeResult probe(ModelProviderEntity provider);
}
