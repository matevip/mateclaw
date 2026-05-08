package vip.mate.wiki.job;

/**
 * Thrown by {@link vip.mate.wiki.service.WikiEmbeddingService#embedMissingChunks(Long)}
 * when the embedding provider has failed N batches in a row, where N is
 * controlled by {@code mate.wiki.embedding-consecutive-failure-threshold}.
 *
 * <p>Without this circuit, a misconfigured or unavailable provider (out
 * of credits, wrong API key, network partition) silently churns through
 * every pending chunk one batch at a time — producing log noise but no
 * actual progress, and consuming wall-clock time the user sees as a
 * stuck "task in loop". The circuit lets the embedding pass abort fast
 * so the user can fix configuration and retry.
 *
 * <p>This is a soft failure: the next call into {@code embedMissingChunks}
 * starts a fresh counter and will retry the provider, so once the user
 * has corrected the configuration the embedding pass picks up where it
 * left off without manual intervention.
 */
public class WikiEmbeddingProviderFailingException extends RuntimeException {

    private final int consecutiveFailures;
    private final int remainingChunks;

    public WikiEmbeddingProviderFailingException(String message,
                                                  int consecutiveFailures,
                                                  int remainingChunks) {
        super(message);
        this.consecutiveFailures = consecutiveFailures;
        this.remainingChunks = remainingChunks;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public int getRemainingChunks() {
        return remainingChunks;
    }
}
