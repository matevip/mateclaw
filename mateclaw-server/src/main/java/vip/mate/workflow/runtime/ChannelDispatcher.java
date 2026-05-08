package vip.mate.workflow.runtime;

/**
 * SPI for "deliver this rendered content to a target on this channel". Kept
 * thin so unit tests can stub channel side effects without booting the full
 * channel adapter graph; production binding lives in
 * {@link DefaultChannelDispatcher} and delegates to {@code ChannelManager}.
 */
public interface ChannelDispatcher {

    /**
     * Send {@code content} to {@code targetId} on the channel identified by
     * {@code channelType} (e.g. {@code "feishu"}, {@code "dingtalk"}). Returns
     * an {@link DispatchResult} so the step adapter can build a per-channel
     * report; throwing is reserved for programmer errors.
     */
    DispatchResult dispatch(long workspaceId, String channelType, String targetId, String content);

    /**
     * Per-channel dispatch outcome. {@code success=false} entries are turned
     * into a step failure by the calling adapter; the message field surfaces
     * to the run-step row's error column.
     */
    record DispatchResult(boolean success, String message) {
        public static DispatchResult ok() { return new DispatchResult(true, null); }
        public static DispatchResult ok(String message) { return new DispatchResult(true, message); }
        public static DispatchResult fail(String message) { return new DispatchResult(false, message); }
    }
}
