package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * classification tests for the new error types
 * ({@link NodeStreamingChatHelper.ErrorType#BILLING},
 * {@link NodeStreamingChatHelper.ErrorType#MODEL_NOT_FOUND}).
 *
 * <p>These two are split out from {@code AUTH_ERROR} / {@code CLIENT_ERROR}
 * because the right action is to switch provider, not to terminate.
 * Mis-classifying a billing error as auth would break the whole call chain.</p>
 */
class ErrorClassificationTest {

    private static NodeStreamingChatHelper.ErrorType classify(Throwable t) throws Exception {
        Method m = NodeStreamingChatHelper.class.getDeclaredMethod("classifyError", Throwable.class);
        m.setAccessible(true);
        return (NodeStreamingChatHelper.ErrorType) m.invoke(null, t);
    }

    // ===== BILLING =====

    @Test
    @DisplayName("HTTP 402 → BILLING")
    void status402IsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("402 Payment Required")));
    }

    @Test
    @DisplayName("OpenAI 'insufficient_quota' → BILLING")
    void openaiQuotaIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("Error code: insufficient_quota — please check your plan")));
    }

    @Test
    @DisplayName("Anthropic 'credit balance is too low' → BILLING")
    void anthropicCreditIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("Your credit balance is too low to access the API")));
    }

    @Test
    @DisplayName("'You exceeded your current quota' → BILLING")
    void quotaExceededIsBilling() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.BILLING,
                classify(new RuntimeException("You exceeded your current quota, please check your plan")));
    }

    // ===== MODEL_NOT_FOUND =====

    @Test
    @DisplayName("'Model not exist' → MODEL_NOT_FOUND")
    void modelNotExistIsModelNotFound() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("Model not exist: gpt-99")));
    }

    @Test
    @DisplayName("'model_not_found' → MODEL_NOT_FOUND")
    void modelNotFoundCodeIsModelNotFound() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("Error: model_not_found")));
    }

    @Test
    @DisplayName("DashScope '[InvalidParameter] url error' → MODEL_NOT_FOUND (not CLIENT_ERROR)")
    void dashscopeInvalidParameterIsModelNotFound() throws Exception {
        // Despite the wording, DashScope returns this when the model id is unknown
        // — the right action is to try a fallback provider, not terminate as 400.
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("[InvalidParameter] url error, please check url")));
    }

    @Test
    @DisplayName("Anthropic 'model does not exist' → MODEL_NOT_FOUND")
    void anthropicDoesNotExistIsModelNotFound() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.MODEL_NOT_FOUND,
                classify(new RuntimeException("model claude-99 does not exist")));
    }

    // ===== Regression: existing classifications still work =====

    @Test
    @DisplayName("HTTP 401 still classifies as AUTH_ERROR (not billing)")
    void status401StillAuth() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR,
                classify(new RuntimeException("401 Unauthorized: Invalid API Key")));
    }

    @Test
    @DisplayName("HTTP 429 still classifies as RATE_LIMIT")
    void status429StillRateLimit() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.RATE_LIMIT,
                classify(new RuntimeException("429 Too Many Requests")));
    }

    @Test
    @DisplayName("Plain 400 Bad Request still classifies as CLIENT_ERROR")
    void status400StillClientError() throws Exception {
        assertEquals(NodeStreamingChatHelper.ErrorType.CLIENT_ERROR,
                classify(new RuntimeException("400 Bad Request: malformed JSON")));
    }
}
