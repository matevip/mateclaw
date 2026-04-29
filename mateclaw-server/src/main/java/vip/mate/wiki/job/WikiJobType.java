package vip.mate.wiki.job;

/**
 * RFC-030: Types of wiki processing jobs.
 */
public enum WikiJobType {
    HEAVY_INGEST,
    LIGHT_ENRICH,
    LOCAL_REPAIR
}
