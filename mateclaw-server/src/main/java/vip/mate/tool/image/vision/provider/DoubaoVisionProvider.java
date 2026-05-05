package vip.mate.tool.image.vision.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;

/**
 * Volcengine Ark Doubao vision provider — uses
 * {@code doubao-seed-1-8-251228} (the seeded multimodal flagship)
 * via the OpenAI-compatible endpoint at {@code /api/v3/chat/completions}.
 *
 * <p>Attaches to the {@code volcengine} model provider; the
 * {@code volcengine-plan} sibling (coding subscription tier) is
 * intentionally not consumed here because the multimodal model
 * isn't part of that plan.
 */
@Component
public class DoubaoVisionProvider extends OpenAiCompatibleVisionProvider {

    private static final String PROVIDER_ID = "doubao-vision";
    private static final String VOLCENGINE_PROVIDER_KEY = "volcengine";
    private static final String DEFAULT_MODEL = "doubao-seed-1-8-251228";
    private static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    public DoubaoVisionProvider(ModelProviderService modelProviderService,
                                 ObjectMapper objectMapper) {
        super(modelProviderService, objectMapper);
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String label() {
        return "Volcano Doubao Vision";
    }

    @Override
    public int autoDetectOrder() {
        return 30;
    }

    @Override
    protected String providerKey() {
        return VOLCENGINE_PROVIDER_KEY;
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
