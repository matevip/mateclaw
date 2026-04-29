package vip.mate.wiki.job;

/**
 * RFC-030: Stage state machine for wiki processing jobs.
 */
public enum WikiJobStage {
    QUEUED, ROUTING,
    PHASE_A_RUNNING, PHASE_A_DONE,
    PHASE_B_RUNNING,
    ENRICHING, EMBEDDING,
    COMPLETED, FAILED, PARTIAL, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == PARTIAL || this == CANCELLED;
    }
}
