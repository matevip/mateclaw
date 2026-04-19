package vip.mate.wiki.job;

/**
 * RFC-030: Logical steps within a wiki processing job,
 * used for per-step model routing.
 */
public enum WikiJobStep {
    ROUTE, CREATE_PAGE, MERGE_PAGE, ENRICH, SUMMARY
}
