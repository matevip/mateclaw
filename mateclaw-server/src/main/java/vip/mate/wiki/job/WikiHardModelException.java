package vip.mate.wiki.job;

/**
 * RFC-031: Thrown when a wiki processing step encounters a hard/fatal
 * model error that warrants fallback to a different model.
 */
public class WikiHardModelException extends RuntimeException {

    private final String errorCode;

    public WikiHardModelException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
