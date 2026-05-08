package vip.mate.workflow.runtime;

/**
 * SPI for the {@code write_memory} step. Hides the workspace-file storage
 * implementation behind a small surface so tests can stub the file side
 * effect without booting WorkspaceFileService. Production binding lives in
 * {@link DefaultMemoryWriter}.
 */
public interface MemoryWriter {

    /**
     * Apply {@code mergeStrategy} to {@code content} against the existing
     * file body for {@code (workspaceId, employeeId, file)} and persist the
     * result. Returns a {@link Result} carrying a short summary so the step
     * row's {@code output_summary} captures what changed.
     */
    Result write(long workspaceId, String employeeId, String file,
                 String mergeStrategy, String content);

    record Result(boolean success, String summary, String errorMessage) {
        public static Result ok(String summary) { return new Result(true, summary, null); }
        public static Result fail(String error) { return new Result(false, null, error); }
    }
}
