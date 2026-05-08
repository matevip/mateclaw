package vip.mate.trigger.dispatch;

/**
 * Outcome of a trigger fire. The dispatcher used to return either a
 * {@code WorkflowRunResult} or {@code null}, which led the ingest and
 * scheduler paths to treat null as "fired" — incrementing
 * {@code fireCount} / {@code lastFiredAt} even when the dispatch was
 * a no-op or an error. This record makes the outcome explicit so each
 * caller can update bookkeeping honestly.
 *
 * <ul>
 *   <li>{@link Kind#FIRED} — a workflow run row was actually created.
 *       {@link #runId()} carries its id; {@link #reason()} is null.</li>
 *   <li>{@link Kind#SKIPPED} — pre-flight rejected the dispatch
 *       (no published revision, unsupported target type, payload render
 *       failed). {@link #reason()} carries the human-readable cause;
 *       {@link #runId()} is null.</li>
 *   <li>{@link Kind#FAILED} — runner threw / persisted with an error
 *       state. {@link #reason()} is the failure message; {@link #runId()}
 *       may be set if a row was created before the failure.</li>
 * </ul>
 */
public record DispatchResult(Kind kind, Long runId, String reason) {

    public enum Kind { FIRED, SKIPPED, FAILED }

    public boolean fired() { return kind == Kind.FIRED; }

    public static DispatchResult fired(Long runId) {
        return new DispatchResult(Kind.FIRED, runId, null);
    }

    public static DispatchResult skipped(String reason) {
        return new DispatchResult(Kind.SKIPPED, null, reason);
    }

    public static DispatchResult failed(String message) {
        return new DispatchResult(Kind.FAILED, null, message);
    }

    public static DispatchResult failed(Long runId, String message) {
        return new DispatchResult(Kind.FAILED, runId, message);
    }
}
