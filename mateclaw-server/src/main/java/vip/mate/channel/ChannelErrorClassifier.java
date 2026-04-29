package vip.mate.channel;

import org.springframework.stereotype.Component;

/**
 * Single source of truth for "is this assistant reply actually an error
 * surface?" used across the channel layer.
 *
 * <p>Why this matters: an LLM-side 400 (DashScope's "Bad request, please
 * check input", DeepSeek thinking-mode "reasoning_content must be passed
 * back", Anthropic "does not support assistant message prefill") is rendered
 * as a normal-looking assistant string by {@code NodeStreamingChatHelper}.
 * If that string is persisted with {@code status='completed'}, the next
 * turn's history feeds it back to the LLM as a real assistant turn and the
 * 400 self-replicates indefinitely.
 *
 * <p>The fix has two layers:
 * <ol>
 *   <li>This classifier flips the persisted status to {@code 'error'} so
 *       {@code BaseAgent.sanitizeForLlm} filters it from history.</li>
 *   <li>The "[错误] " content prefix kept on disk is the legacy backup
 *       filter — both work together.</li>
 * </ol>
 *
 * <p>Heuristics are kept in sync with the error-message templates emitted
 * by {@code NodeStreamingChatHelper.buildErrorResultWithType} and friends.
 * Adding a new error template there means adding the matching probe here.
 */
@Component
public class ChannelErrorClassifier {

    /**
     * @return {@code true} if the reply text matches one of the known
     *         LLM-side error surfaces and should NOT be treated as a real
     *         assistant turn for memory / history purposes.
     */
    public boolean isErrorReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        return reply.startsWith("[错误] ")
                || reply.contains("Bad request:")
                || reply.contains("LLM 调用失败:")
                || reply.contains("LLM 调用超时")
                || reply.contains("LLM 调用被中断")
                || reply.contains("Prompt 过长:")
                || reply.contains("认证失败:")
                || reply.contains("LLM 返回空响应");
    }

    /** Map a classification result to the {@code mate_message.status} value. */
    public String statusFor(String reply) {
        return isErrorReply(reply) ? "error" : "completed";
    }
}
