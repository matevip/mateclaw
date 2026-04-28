package vip.mate.llm.model;

/**
 * RFC-073: runtime liveness state of a provider, surfaced to the UI so the
 * dropdown / settings page can show truth instead of "configured = available".
 *
 * <p>Computed by {@code ModelProviderService.computeLiveness} from three
 * orthogonal signals: configuration completeness, init-probe progress, and
 * pool / cooldown membership. The five values are mutually exclusive.</p>
 */
public enum Liveness {
    /** In pool, not in cooldown. The default healthy state. */
    LIVE,
    /** In pool but in transient cooldown (consecutive failures tripped the threshold). */
    COOLDOWN,
    /** Probed and removed from pool with a HARD reason (auth, billing, model-not-found, init-probe failed). */
    REMOVED,
    /** Not yet probed — startup window or no probe strategy registered for this protocol. */
    UNPROBED,
    /** User-side configuration is incomplete (missing api_key / oauth token / base_url). */
    UNCONFIGURED
}
