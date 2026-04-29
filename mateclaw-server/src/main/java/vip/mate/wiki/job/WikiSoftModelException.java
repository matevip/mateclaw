package vip.mate.wiki.job;

/**
 * RFC-031: Thrown when a wiki processing step encounters a transient
 * model error that can be retried.
 */
public class WikiSoftModelException extends RuntimeException {

    private final String errorCode;

    public WikiSoftModelException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
