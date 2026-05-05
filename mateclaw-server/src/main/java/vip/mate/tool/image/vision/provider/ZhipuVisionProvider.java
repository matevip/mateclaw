package vip.mate.tool.image.vision.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;

/**
 * Zhipu vision provider — uses {@code glm-5v-turbo} via the
 * OpenAI-compatible endpoint at {@code /api/paas/v4/chat/completions}.
 *
 * <p>Attaches to the {@code zhipu-cn} model provider. Operators with
 * the international tier should configure the {@code zhipu-cn} entry
 * with the international base URL or wait for an explicit intl
 * sibling provider — the seeded {@code zhipu-intl} differs only in
 * base URL.
 */
@Component
public class ZhipuVisionProvider extends OpenAiCompatibleVisionProvider {

    private static final String PROVIDER_ID = "zhipu-vision";
    private static final String ZHIPU_PROVIDER_KEY = "zhipu-cn";
    private static final String DEFAULT_MODEL = "glm-5v-turbo";
    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

    public ZhipuVisionProvider(ModelProviderService modelProviderService,
                                ObjectMapper objectMapper) {
        super(modelProviderService, objectMapper);
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String label() {
        return "Zhipu GLM-V";
    }

    @Override
    public int autoDetectOrder() {
        return 20;
    }

    @Override
    protected String providerKey() {
        return ZHIPU_PROVIDER_KEY;
    }

    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    @Override
    protected String defaultModel() {
        return DEFAULT_MODEL;
    }
}
