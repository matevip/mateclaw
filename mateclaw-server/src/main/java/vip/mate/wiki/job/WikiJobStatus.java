package vip.mate.wiki.job;

/**
 * RFC-030: Top-level status of a wiki processing job.
 */
public enum WikiJobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, PARTIAL, CANCELLED
}
