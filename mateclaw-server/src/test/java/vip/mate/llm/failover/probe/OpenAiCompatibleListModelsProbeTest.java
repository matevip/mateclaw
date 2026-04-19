package vip.mate.llm.failover.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks down the URL-resolution rule for {@link OpenAiCompatibleListModelsProbe}:
 *
 * <ul>
 *   <li>Vendors that point at the API root (OpenAI / Kimi / DeepSeek) get
 *       {@code /v1/models} appended.</li>
 *   <li>Vendors that include a {@code /vN} segment in their Base URL
 *       (LMStudio's {@code /v1}, ZhipuAI's {@code /v4}, etc.) get only
 *       {@code /models} appended — preventing the {@code /v1/v1/models} or
 *       {@code /v4/v1/models} 404s the original implementation produced.</li>
 * </ul>
 */
class OpenAiCompatibleListModelsProbeTest {

    @Test
    @DisplayName("API-root base URL → append /v1/models (OpenAI / DeepSeek / Kimi)")
    void apiRootBaseGetsV1Models() {
        assertEquals("/v1/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("https://api.openai.com"));
        assertEquals("/v1/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("https://api.deepseek.com"));
        assertEquals("/v1/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("https://api.moonshot.cn"));
    }

    @Test
    @DisplayName("Base URL ends in /v1 → append only /models (LMStudio)")
    void v1SuffixGetsOnlyModels() {
        assertEquals("/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("http://localhost:1234/v1"));
    }

    @Test
    @DisplayName("Base URL ends in /v4 → append only /models (ZhipuAI)")
    void v4SuffixGetsOnlyModels() {
        assertEquals("/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("https://open.bigmodel.cn/api/paas/v4"));
    }

    @Test
    @DisplayName("Base URL ends in /v2 (hypothetical) → append only /models")
    void otherVersionSuffixGetsOnlyModels() {
        assertEquals("/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("https://example.com/api/v2"));
        assertEquals("/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("https://example.com/v3"));
    }

    @Test
    @DisplayName("Base URL contains /vN mid-path but doesn't end with it → append /v1/models")
    void midPathVersionDoesNotMatch() {
        assertEquals("/v1/models",
                OpenAiCompatibleListModelsProbe.resolveModelsPath("https://api.example.com/v1/proxy"));
    }

    @Test
    @DisplayName("Edge: null / blank base URL falls back to /v1/models (caller validates emptiness separately)")
    void nullOrBlankBase() {
        assertEquals("/v1/models", OpenAiCompatibleListModelsProbe.resolveModelsPath(null));
        assertEquals("/v1/models", OpenAiCompatibleListModelsProbe.resolveModelsPath(""));
    }
}
