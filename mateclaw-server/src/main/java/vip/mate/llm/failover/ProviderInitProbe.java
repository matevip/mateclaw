package vip.mate.llm.failover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.repository.ModelProviderMapper;
import vip.mate.llm.service.ModelProviderService;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * RFC-009 Phase 4 — startup-time provider liveness check.
 *
 * <p>On {@link ApplicationReadyEvent} (after Flyway, all beans, and
 * {@link ModelProviderService} are ready), enumerates every configured
 * provider and probes it in parallel via the protocol-specific
 * {@link ProviderProbeStrategy} bean. Healthy providers are added to
 * {@link AvailableProviderPool}; failed ones are removed with
 * {@link AvailableProviderPool.RemovalSource#INIT_PROBE}.</p>
 *
 * <p><b>Fail-open semantics</b> — the whole batch is bounded by
 * {@link #BATCH_TIMEOUT_MS} (10 s). Any provider whose probe is still in
 * flight at that point is added to the pool by default; the chat path
 * will validate it on first request. This avoids an SSL hang on one
 * provider gating user-visible chat.</p>
 *
 * <p><b>What's NOT done here</b> — periodic re-probing. The plan calls
 * for re-add triggers only on (a) restart (this class), (b) a
 * {@code ModelConfigChangedEvent} (PR-1e), or (c) the manual reprobe
 * REST endpoint (PR-1e). Once a provider is HARD-removed at runtime,
 * nothing in this class brings it back automatically.</p>
 */
@Slf4j
@Component
public class ProviderInitProbe {

    /** Hard ceiling on the parallel batch — stalled probes don't block startup beyond this. */
    private static final long BATCH_TIMEOUT_MS = 10_000L;

    private final ModelProviderMapper providerMapper;
    private final ModelProviderService providerService;
    private final AvailableProviderPool pool;
    private final Map<ModelProtocol, ProviderProbeStrategy> strategies;

    public ProviderInitProbe(ModelProviderMapper providerMapper,
                             ModelProviderService providerService,
                             AvailableProviderPool pool,
                             List<ProviderProbeStrategy> probeStrategies) {
        this.providerMapper = providerMapper;
        this.providerService = providerService;
        this.pool = pool;
        Map<ModelProtocol, ProviderProbeStrategy> map = new EnumMap<>(ModelProtocol.class);
        for (ProviderProbeStrategy s : probeStrategies) {
            ProviderProbeStrategy prev = map.put(s.supportedProtocol(), s);
            if (prev != null) {
                throw new IllegalStateException("Duplicate ProviderProbeStrategy for protocol "
                        + s.supportedProtocol() + ": " + prev.getClass().getSimpleName()
                        + " vs " + s.getClass().getSimpleName());
            }
        }
        this.strategies = map;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        probeAllConfigured();
    }

    /**
     * Probe every configured provider in parallel. Public so PR-1e's manual
     * reprobe / config-changed listener can trigger a full refresh, not just
     * a single-provider one.
     */
    public void probeAllConfigured() {
        List<ModelProviderEntity> providers = listConfiguredProviders();
        if (providers.isEmpty()) {
            log.info("[ProviderInitProbe] no configured providers — nothing to probe");
            return;
        }
        log.info("[ProviderInitProbe] probing {} configured provider(s)...", providers.size());

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(providers.size(), 8),
                r -> {
                    Thread t = new Thread(r, "provider-init-probe");
                    t.setDaemon(true);
                    return t;
                });
        Map<String, Future<ProbeResult>> futures = new ConcurrentHashMap<>();
        try {
            for (ModelProviderEntity provider : providers) {
                ProviderProbeStrategy strategy = strategies.get(resolveProtocol(provider));
                if (strategy == null) {
                    // No probe strategy registered for this protocol — fail-open: assume usable.
                    log.debug("[ProviderInitProbe] no probe strategy for {} (protocol={}), defaulting to in-pool",
                            provider.getProviderId(), provider.getChatModel());
                    pool.add(provider.getProviderId());
                    continue;
                }
                futures.put(provider.getProviderId(),
                        executor.submit(() -> strategy.probe(provider)));
            }

            long deadline = System.currentTimeMillis() + BATCH_TIMEOUT_MS;
            int passed = 0, failed = 0, deferred = 0;
            for (Map.Entry<String, Future<ProbeResult>> e : futures.entrySet()) {
                String id = e.getKey();
                long remaining = deadline - System.currentTimeMillis();
                ProbeResult result = null;
                if (remaining > 0) {
                    try {
                        result = e.getValue().get(remaining, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        // fall through to deferred
                    } catch (Exception ex) {
                        result = ProbeResult.fail(0, "probe threw: " + ex.getClass().getSimpleName());
                    }
                }
                if (result == null) {
                    // Fail-open: didn't finish within the batch budget; assume usable so chat
                    // isn't gated on a slow probe. First real request will validate.
                    pool.add(id);
                    deferred++;
                    log.warn("[ProviderInitProbe] provider={} probe deferred (still running at {}ms cap) — fail-open into pool",
                            id, BATCH_TIMEOUT_MS);
                } else if (result.success()) {
                    pool.add(id);
                    passed++;
                    log.info("[ProviderInitProbe] provider={} OK ({} ms)", id, result.latencyMs());
                } else {
                    pool.remove(id, AvailableProviderPool.RemovalSource.INIT_PROBE,
                            "init probe failed: " + result.errorMessage());
                    failed++;
                    log.warn("[ProviderInitProbe] provider={} FAIL ({} ms): {}",
                            id, result.latencyMs(), result.errorMessage());
                }
            }
            log.info("[ProviderInitProbe] done — passed={}, failed={}, deferred={}, pool size={}",
                    passed, failed, deferred, pool.snapshot().size());
        } finally {
            // Outstanding futures are interrupted; threads are daemon and won't hold shutdown.
            executor.shutdownNow();
        }
    }

    /**
     * Probe one provider on demand. Used by PR-1e's manual reprobe endpoint
     * and {@code ModelConfigChangedEvent} listener.
     *
     * @return the {@link ProbeResult}; pool state is also updated as a side effect.
     */
    public ProbeResult probeOne(String providerId) {
        if (!StringUtils.hasText(providerId)) {
            return ProbeResult.fail(0, "providerId is blank");
        }
        ModelProviderEntity provider = providerMapper.selectById(providerId);
        if (provider == null) {
            return ProbeResult.fail(0, "provider not found: " + providerId);
        }
        if (!providerService.isProviderConfigured(providerId)) {
            ProbeResult r = ProbeResult.fail(0, "provider not configured");
            pool.remove(providerId, AvailableProviderPool.RemovalSource.INIT_PROBE, r.errorMessage());
            return r;
        }
        ProviderProbeStrategy strategy = strategies.get(resolveProtocol(provider));
        if (strategy == null) {
            // No strategy for this protocol — fail-open: assume usable.
            pool.add(providerId);
            return ProbeResult.ok(0);
        }
        ProbeResult result = strategy.probe(provider);
        if (result.success()) {
            pool.add(providerId);
        } else {
            pool.remove(providerId, AvailableProviderPool.RemovalSource.INIT_PROBE,
                    "reprobe failed: " + result.errorMessage());
        }
        return result;
    }

    private List<ModelProviderEntity> listConfiguredProviders() {
        return providerMapper.selectList(null).stream()
                .filter(p -> providerService.isProviderConfigured(p.getProviderId()))
                .toList();
    }

    private static ModelProtocol resolveProtocol(ModelProviderEntity provider) {
        return ModelProtocol.fromChatModel(provider.getChatModel());
    }
}
